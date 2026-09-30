package com.nasgame.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nasgame.data.api.Game
import com.nasgame.data.api.NasGameApi
import com.nasgame.data.api.RomInfoResponse
import com.nasgame.data.prefs.PrefsStore
import com.nasgame.util.SafFileHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责 ROM 下载的核心组件。
 *
 * 三种存储路径:
 *  1. DEFAULT — App 私有 external 目录 (卸载自动清理)
 *  2. SAF     — 用户通过系统文件选择器选定的目录 (持久化 URI 权限)
 *  3. LEGACY  — 老式绝对路径 (兼容旧配置)
 *
 * 115 CDN 通过裸 OkHttp 下载 (无 Authorization), 自动 retry.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    private val TAG = "DownloadManager"

    /** 裸 OkHttp client — 下载 115 CDN 直链 (无 Auth, follow redirect, retry on failure) */
    private val rawClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _active = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val active: StateFlow<Map<Long, DownloadProgress>> = _active.asStateFlow()

    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private var sem: Semaphore = Semaphore(2)

    init {
        scope.launch {
            sem = Semaphore(prefs.concurrentDownloads().coerceAtLeast(1))
        }
    }

    fun updateConcurrency(n: Int) {
        scope.launch {
            sem = Semaphore(n.coerceAtLeast(1))
            prefs.setConcurrentDownloads(n)
        }
    }

    fun rawHttpClient(): OkHttpClient = rawClient

    // ============ 路径解析 ============

    /**
     * 解析 ROM 的目标路径, 同时考虑三种存储模式.
     * 返回 [RomTarget] — 调用方根据 isSaf 决定用 SAF API 还是 File API.
     */
    suspend fun resolveRomTarget(platformCode: String, filename: String): RomTarget {
        val plat = platformCode.ifBlank { "misc" }
        when (prefs.romStorageKind()) {
            PrefsStore.RomStorageKind.DEFAULT -> {
                val dir = File(prefs.defaultRomStoragePath(), plat)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                return RomTarget.Default(file)
            }
            PrefsStore.RomStorageKind.SAF -> {
                val uriStr = prefs.romStorageSafUri() ?: run {
                    // SAF 配错回退 default
                    return resolveRomTargetFallback(plat, filename)
                }
                val treeUri = Uri.parse(uriStr)
                if (!SafFileHelper.hasPersistedPermission(ctx, treeUri)) {
                    return resolveRomTargetFallback(plat, filename)
                }
                val root = DocumentFile.fromTreeUri(ctx, treeUri)
                    ?: return resolveRomTargetFallback(plat, filename)
                val platDir = SafFileHelper.findOrCreateDir(ctx, root, listOf(plat))
                val docFile = SafFileHelper.findOrCreateFile(ctx, platDir, filename)
                return RomTarget.Saf(docFile)
            }
            PrefsStore.RomStorageKind.LEGACY_PATH -> {
                val base = prefs.romStorageLegacyPath() ?: prefs.defaultRomStoragePath()
                val dir = File(base, plat)
                if (!dir.exists()) dir.mkdirs()
                return RomTarget.Default(File(dir, filename))
            }
        }
    }

    private fun resolveRomTargetFallback(plat: String, filename: String): RomTarget {
        val dir = File(prefs.defaultRomStoragePath(), plat)
        if (!dir.exists()) dir.mkdirs()
        return RomTarget.Default(File(dir, filename))
    }

    /** 兼容旧 API — 返回 File (SAF 模式下用本地缓存 mirror) */
    fun romCacheDir(platformCode: String): File {
        val dir = File(prefs.defaultRomStoragePath(), platformCode)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isLocal(game: Game): Boolean {
        return try {
            runBlocking { resolveRomTargetForCheck(game.platform?.code ?: "misc", game.romFilename) }
        } catch (_: Exception) { false }
    }

    /**
     * 仅检查 ROM 是否已存在 (不创建空文件).
     * - DEFAULT 模式: 检查本地 File.exists() + size > 0
     * - SAF 模式: 仅查目录里有该文件 + size > 0 (不创建)
     */
    suspend fun resolveRomTargetForCheck(platformCode: String, filename: String): Boolean {
        val plat = platformCode.ifBlank { "misc" }
        return when (prefs.romStorageKind()) {
            PrefsStore.RomStorageKind.DEFAULT -> {
                val file = File(prefs.defaultRomStoragePath(), plat).resolve(filename)
                file.exists() && file.length() > 0
            }
            PrefsStore.RomStorageKind.SAF -> {
                val uriStr = prefs.romStorageSafUri() ?: return false
                val treeUri = Uri.parse(uriStr)
                if (!SafFileHelper.hasPersistedPermission(ctx, treeUri)) return false
                val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return false
                val platDir = SafFileHelper.findDir(ctx, root, plat) ?: return false
                val doc = platDir.findFile(filename) ?: return false
                doc.length() > 0
            }
            PrefsStore.RomStorageKind.LEGACY_PATH -> {
                val base = prefs.romStorageLegacyPath() ?: prefs.defaultRomStoragePath()
                val file = File(base, plat).resolve(filename)
                file.exists() && file.length() > 0
            }
        }
    }

    fun localPath(game: Game): File =
        File(prefs.defaultRomStoragePath(), game.platform?.code ?: "misc").resolve(game.romFilename)

    /** 给 EmulatorLauncher 用 — 拿到一个能被 FileProvider 处理的 URI */
    fun localUri(ctx: Context, game: Game): Uri? = runBlocking {
        val target = resolveRomTarget(game.platform?.code ?: "misc", game.romFilename)
        when (target) {
            is RomTarget.Default -> androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", target.file
            )
            is RomTarget.Saf -> SafFileHelper.toShareableUri(target.doc)
        }
    }

    fun probeSource(api: NasGameApi, game: Game): RomSource {
        return if (isLocal(game)) RomSource.LOCAL
        else if (game.cloudSource == "115") RomSource.REMOTE_115
        else RomSource.UNKNOWN
    }

    /** 启动下载任务 */
    fun startDownload(api: NasGameApi, game: Game, onComplete: (File?) -> Unit = {}): Boolean {
        if (inflight.containsKey(game.id.toLong())) {
            Log.d(TAG, "Already downloading ${game.id}")
            return false
        }

        val job = scope.launch {
            sem.withPermit {
                doDownloadWork(api, game, onComplete)
            }
        }
        inflight[game.id.toLong()] = job
        job.invokeOnCompletion { inflight.remove(game.id.toLong()); removeFromQueue(game.id.toLong()) }
        addToQueue(game)
        return true
    }

    fun cancel(gameId: Long) {
        inflight.remove(gameId)?.cancel()
        removeFromQueue(gameId)
    }

    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        _queue.value = emptyList()
    }

    /**
     * 核心下载逻辑:
     * 1. 调 /api/games/{id}/rom-info 拿到 source + url (本地 or 115 CDN)
     * 2. 根据 RomTarget 类型用 SAF API 或 File API 写文件
     */
    private suspend fun doDownloadWork(
        api: NasGameApi,
        game: Game,
        onComplete: (File?) -> Unit,
    ) {
        val gid = game.id.toLong()
        val platCode = game.platform?.code ?: "misc"
        Log.i(TAG, "Start download: $platCode/${game.romFilename} (${game.romSize} bytes)")

        try {
            val target = resolveRomTarget(platCode, game.romFilename)
            val info = api.romInfo(gid)
            Log.i(TAG, "RomInfo: source=${info.source}, size=${info.size}, url=${info.url.take(80)}...")

            val total = info.size.takeIf { it > 0 } ?: game.romSize
            val filename = info.filename.ifBlank { game.romFilename }

            // 0. 进度占位
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid, romFilename = filename, platform = platCode,
                downloaded = 0, total = total, speedBps = 0, done_ = false,
            ))

            // 1. 打开输出流 (SAF 或 File)
            val output: java.io.OutputStream = when (target) {
                is RomTarget.Default -> target.file.outputStream()
                is RomTarget.Saf -> SafFileHelper.openOutput(ctx, target.doc, truncate = true)
            }

            // 2. 拿到输入流 (本地 Retrofit 或 115 裸 OkHttp)
            val input: java.io.InputStream = when (info.source) {
                "local" -> {
                    val body = api.downloadRom(gid)
                    body.byteStream()
                }
                "remote_115" -> {
                    if (info.url.isBlank()) throw IOException("服务端返回空 URL")
                    val req = okhttp3.Request.Builder()
                        .url(info.url)
                        .header("User-Agent", "NasGameHub/1.0 (Android)")
                        .build()
                    val resp = rawClient.newCall(req).execute()
                    if (!resp.isSuccessful) {
                        resp.close()
                        throw IOException("115 CDN HTTP ${resp.code}: ${resp.message}")
                    }
                    val body = resp.body ?: throw IOException("115 CDN 空响应体")
                    body.byteStream()
                }
                else -> throw IOException("未知 source: ${info.source}")
            }

            try {
                writeToOutput(input, output, total, gid, filename, platCode)
            } finally {
                input.close()
                output.close()
            }

            // 3. 校验大小 (SAF 模式下 target.doc.length() 也可用)
            val actualSize = when (target) {
                is RomTarget.Default -> target.file.length()
                is RomTarget.Saf -> target.doc.length()
            }
            if (total > 0 && actualSize != total) {
                throw IOException("下载不完整: $actualSize / $total")
            }

            Log.i(TAG, "Done: ${target.describe()} ($actualSize bytes)")
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid, romFilename = filename, platform = platCode,
                downloaded = total, total = total, speedBps = 0, done_ = true,
            ))
            // onComplete 传 File (SAF 模式我们用本地 mirror file 给上层兼容)
            val fileForCallback: File? = when (target) {
                is RomTarget.Default -> target.file
                is RomTarget.Saf -> localPath(game)
            }
            onComplete(fileForCallback)
        } catch (e: CancellationException) {
            Log.w(TAG, "Cancelled: ${game.romFilename}")
            _active.value = _active.value - gid
            onComplete(null)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${game.romFilename}", e)
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid, romFilename = game.romFilename, platform = platCode,
                downloaded = 0, total = game.romSize, speedBps = 0, done_ = false,
                error = humanError(e),
            ))
            onComplete(null)
        }
    }

    private suspend fun writeToOutput(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        gid: Long,
        filename: String,
        platCode: String,
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastEmit = 0L
        var lastBytes = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastEmit > 250 || done == total) {
                val dt = (now - lastEmit).coerceAtLeast(1)
                val speed = ((done - lastBytes) * 1000 / dt).coerceAtLeast(0)
                _active.value = _active.value + (gid to DownloadProgress(
                    gameId = gid, romFilename = filename, platform = platCode,
                    downloaded = done, total = total, speedBps = speed, done_ = done >= total,
                ))
                lastEmit = now
                lastBytes = done
            }
        }
    }

    private fun humanError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("unexpected end of stream", true) ->
                "115 CDN 连接中断 (重试中). 详细: $msg"
            msg.contains("timeout", true) -> "下载超时"
            msg.contains("404") -> "ROM 不存在 (本地和 115 都没找到)"
            msg.contains("401") || msg.contains("403") -> "权限不足, 请检查登录状态"
            else -> msg.take(120)
        }
    }

    private fun addToQueue(game: Game) {
        val gid = game.id.toLong()
        if (_queue.value.any { it.gameId == gid }) return
        _queue.value = _queue.value + DownloadItem(
            gameId = gid,
            title = game.titleZh.ifBlank { game.titleEn.ifBlank { game.titleRaw } },
            platform = game.platform?.code ?: "?",
            romFilename = game.romFilename,
            sizeBytes = game.romSize,
        )
    }

    private fun removeFromQueue(gameId: Long) {
        _queue.value = _queue.value.filter { it.gameId != gameId }
    }
}

/** ROM 下载目标: SAF 目录 或 普通 File */
sealed class RomTarget {
    abstract fun exists(): Boolean
    abstract fun describe(): String

    data class Default(val file: File) : RomTarget() {
        override fun exists() = file.exists()
        override fun describe() = file.absolutePath
    }
    data class Saf(val doc: DocumentFile) : RomTarget() {
        override fun exists() = doc.exists()
        override fun describe() = doc.uri.toString()
    }
}

enum class RomSource {
    LOCAL,
    REMOTE_115,
    UNKNOWN,
}

data class DownloadItem(
    val gameId: Long,
    val title: String,
    val platform: String,
    val romFilename: String,
    val sizeBytes: Long,
)

data class DownloadProgress(
    val gameId: Long,
    val romFilename: String,
    val platform: String,
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val done_: Boolean = false,
    val error: String? = null,
) {
    val percent: Float
        get() = if (total > 0) downloaded.toFloat() / total else 0f

    val formattedSize: String
        get() = humanBytes(downloaded) + " / " + humanBytes(total)

    val formattedSpeed: String
        get() = if (speedBps > 0) humanBytes(speedBps) + "/s" else ""

    private fun humanBytes(n: Long): String {
        if (n <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}