package com.cloudgamehub.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cloudgamehub.data.db.CloudDb
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.pan115.Pan115Client
import com.cloudgamehub.data.pan115.Pan115Exception
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.emulator.RomHandle
import com.cloudgamehub.util.SafFileHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ROM 下载引擎。
 *
 * 没有服务器了, 所以链路短得多:
 *
 * ```
 * games.pickcode ──> 115 /files/download ──> CDN 直链 (带 cookie)
 *                                              │
 *                                     手机直接 GET 115 CDN
 *                                     (115 的 302 就是这里)
 *                                              ↓
 *                                    写到手机本地 (SAF / 公共目录)
 * ```
 *
 * **115 的 302 就是 phone → webapi 拿直链 → phone 跟 302 落到 CDN 这一步**,
 * 全程没有任何中间服务器, 也不吃 NAS 带宽。
 *
 * ## 断点续传
 *
 * 手机上 4G/地铁/WiFi 抖动是常态, 一个 PS2 碟 4GB 传到一半断掉很常见。
 * 所以:
 * - 下到 `xxx.rom.part`, 完成后再改名
 * - 重连时带 `Range: bytes=<已有>-`, 115 CDN 认这个
 * - 115 的直链有时效, 断了要**重新取一次直链**再续 (旧直链的 token 过期了)
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
    private val pan: Pan115Client,
    private val db: CloudDb,
) {
    private val TAG = "DownloadManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _active = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val active: StateFlow<Map<Long, DownloadProgress>> = _active.asStateFlow()

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val inflight = ConcurrentHashMap<Long, Job>()
    private var sem: Semaphore = Semaphore(1)

    init {
        scope.launch { sem = Semaphore(prefs.concurrentDownloads()) }
    }

    fun updateConcurrency(n: Int) {
        scope.launch {
            sem = Semaphore(n.coerceIn(1, 4))
            prefs.setConcurrentDownloads(n)
        }
    }

    fun cancel(gameId: Long) {
        inflight.remove(gameId)?.cancel()
        _queue.value = _queue.value.filter { it.gameId != gameId }
    }

    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        _queue.value = emptyList()
    }

    // ==================== 路径 ====================

    /**
     * 算出这个 ROM 在手机上的落点。
     *
     * ⚠️ **默认是 SAF 而不是 App 私有目录**: RetroArch 在 Android 11+ 上读不到
     * `/sdcard/Android/data/<别的app>/files/...`, 放在那儿等于下了也白下。
     * 所以首次使用时如果还没选目录, 我们走 SAF 让用户挑一个公共目录。
     */
    suspend fun resolveTarget(game: Game): RomTarget {
        val plat = game.platformCode.ifBlank { "misc" }
        return when (prefs.romStorageKind()) {
            PrefsStore.RomStorageKind.DEFAULT -> {
                val dir = File(prefs.defaultRomStoragePath(), plat)
                if (!dir.exists()) dir.mkdirs()
                RomTarget.File(File(dir, game.romFilename))
            }
            PrefsStore.RomStorageKind.SAF -> {
                val uriStr = prefs.romStorageSafUri()
                    ?: return resolveFallback(game)
                val treeUri = Uri.parse(uriStr)
                if (!SafFileHelper.hasPersistedPermission(ctx, treeUri)) return resolveFallback(game)
                val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return resolveFallback(game)
                val platDir = SafFileHelper.findOrCreateDir(ctx, root, listOf(plat))
                val doc = SafFileHelper.findOrCreateFile(ctx, platDir, game.romFilename)
                RomTarget.Saf(treeUri, doc)
            }
            PrefsStore.RomStorageKind.LEGACY_PATH -> {
                val base = prefs.romStorageLegacyPath() ?: return resolveFallback(game)
                val dir = File(base, plat)
                if (!dir.exists()) dir.mkdirs()
                RomTarget.File(File(dir, game.romFilename))
            }
        }
    }

    private fun resolveFallback(game: Game): RomTarget {
        val dir = File(prefs.defaultRomStoragePath(), game.platformCode.ifBlank { "misc" })
        if (!dir.exists()) dir.mkdirs()
        return RomTarget.File(File(dir, game.romFilename))
    }

    /**
     * 拿到可以直接喂给模拟器的句柄。
     *
     * RetroArch 这类只认**绝对路径**, 拿不到就必须告诉用户换目录, 而不是
     * 让它打开一片空白 (这正是老版本"点了没反应"的根因之一)。
     */
    suspend fun localHandle(game: Game): RomHandle? {
        val target = resolveTarget(game)
        if (!target.exists() || target.currentLength() <= 0) return null
        val name = game.romFilename
        return when (target) {
            is RomTarget.File -> {
                val f = target.file
                val uri = runCatching {
                    androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", f
                    )
                }.getOrElse { Uri.fromFile(f) }
                val readable = f.canRead() && !isAppPrivate(f.absolutePath)
                RomHandle(uri, if (readable) f.absolutePath else null, name)
            }
            is RomTarget.Saf -> {
                val real = SafFileHelper.fileRealPath(target.treeUri, game.platformCode, name)
                RomHandle(target.doc.uri, real, name)
            }
        }
    }

    private fun isAppPrivate(path: String): Boolean =
        path.startsWith("/data/") ||
            path.contains("/Android/data/com.cloudgamehub/") ||
            path.contains("/Android/obb/com.cloudgamehub/")

    /** 清掉"库里说有本地、实际文件没了"的记录 */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) { db.reconcileLocalFiles() }

    // ==================== 下载 ====================

    /**
     * 开始下载。返回 false 表示已经在队列里了。
     *
     * @param onDone 下载完成回调 (成功时带落地路径)
     */
    fun start(game: Game, onDone: (Boolean, String?) -> Unit = { _, _ -> }): Boolean {
        val id = game.id
        if (inflight.containsKey(id)) return false
        addToQueue(game)
        val job = scope.launch {
            sem.withPermit { runCatching { doWork(game, onDone) } }
        }
        inflight[id] = job
        job.invokeOnCompletion {
            inflight.remove(id)
            _queue.value = _queue.value.filter { it.gameId != id }
        }
        return true
    }

    private suspend fun doWork(game: Game, onDone: (Boolean, String?) -> Unit) {
        val target = resolveTarget(game)
        val total = game.romSize

        // 已经下完了
        if (total > 0 && target.currentLength() == total) {
            finish(game, target, total)
            onDone(true, target.describe())
            return
        }

        emit(game.id, DownloadProgress(game.id, game.romFilename, game.platformCode, 0, total, 0, false))
        var lastError: Exception? = null

        // 最多试 3 次。115 直链有时会 403/超时, 重新取一次直链往往就好了。
        repeat(3) { attempt ->
            try {
                val done = downloadOnce(game, target, total)
                val size = target.currentLength()
                if (total > 0 && size != total) {
                    throw IOException("下载不完整: $size / $total")
                }
                Log.i(TAG, "done ${game.romFilename} (${done} bytes, 第 ${attempt + 1} 次)")
                finish(game, target, size)
                onDone(true, target.describe())
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "下载尝试 ${attempt + 1}/3 失败: ${e.message}")
            }
        }

        val msg = humanError(lastError)
        emit(game.id, DownloadProgress(game.id, game.romFilename, game.platformCode,
            target.currentLength(), total, 0, false, msg))
        onDone(false, msg)
    }

    private suspend fun downloadOnce(game: Game, target: RomTarget, total: Long): Long =
        withContext(Dispatchers.IO) {
            // 115 直链有时效, 每次重试都重新取
            val link = pan.directLink(game.pickcode)
            val partName = if (target is RomTarget.File) target.file.name + ".part" else game.romFilename
            val resumeFrom = currentPartLength(target, partName)

            val req = okhttp3.Request.Builder().url(link.url).apply {
                link.headers.forEach { (k, v) -> header(k, v) }
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
            }.build()

            pan.downloadClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    in listOf(200, 206) -> {}
                    403 -> throw Pan115Exception("115 CDN 拒了这次请求 (IP 限流), 换网络再试")
                    404 -> throw Pan115Exception("115 上找不到这个文件了")
                    416 -> throw Pan115Exception("本地已下完但校验不过, 删除后重试")
                    else -> throw IOException("115 返回 ${resp.code}")
                }
                // 服务端忽略了 Range (200) → 必须截断重写, 不能追加
                val serverResumed = resp.code == 206
                val body = resp.body ?: throw IOException("空响应体")
                val startAt = if (serverResumed) resumeFrom else 0L
                val stream: InputStream = body.byteStream()
                val out = openOutput(target, partName, append = serverResumed)
                try {
                    pump(stream, out, game, startAt, if (total > 0) total else body.contentLength())
                } finally {
                    runCatching { stream.close() }
                    runCatching { out.close() }
                }
                // 成功: .part 改名成正式文件
                commit(target, partName)
                target.currentLength()
            }
        }

    private fun pump(
        input: InputStream, output: OutputStream,
        game: Game, startAt: Long, total: Long,
    ) {
        val buf = ByteArray(256 * 1024)
        var done = startAt
        var lastEmit = System.currentTimeMillis()
        var lastBytes = done
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastEmit > 300) {
                val dt = (now - lastEmit).coerceAtLeast(1)
                val speed = ((done - lastBytes) * 1000 / dt).coerceAtLeast(0)
                emit(game.id, DownloadProgress(game.id, game.romFilename, game.platformCode,
                    done, total, speed, false))
                lastEmit = now
                lastBytes = done
            }
        }
        output.flush()
    }

    // ==================== 落盘细节 ====================

    private fun currentPartLength(target: RomTarget, partName: String): Long = when (target) {
        is RomTarget.File -> {
            val part = File(target.file.parentFile, partName)
            part.takeIf { it.exists() }?.length() ?: 0L
        }
        is RomTarget.Saf -> {
            // SAF 下没有独立 .part 文件, 直接看目标文件已有多大
            target.currentLength()
        }
    }

    private fun openOutput(target: RomTarget, partName: String, append: Boolean): OutputStream =
        when (target) {
            is RomTarget.File -> {
                val file = if (append) target.file else File(target.file.parentFile, partName)
                file.parentFile?.mkdirs()
                if (append) java.io.FileOutputStream(target.file, true) else file.outputStream()
            }
            is RomTarget.Saf -> SafFileHelper.openOutput(ctx, target.doc, truncate = !append)
        }

    /** 把 .part 改名成正式文件名 */
    private fun commit(target: RomTarget, partName: String) {
        if (target is RomTarget.File) {
            val part = File(target.file.parentFile, partName)
            if (part.exists() && part.absolutePath != target.file.absolutePath) {
                part.renameTo(target.file)
            }
        }
    }

    private fun finish(game: Game, target: RomTarget, size: Long) {
        val path = when (target) {
            is RomTarget.File -> target.file.absolutePath
            is RomTarget.Saf -> SafFileHelper.fileRealPath(
                target.treeUri, game.platformCode, game.romFilename
            ) ?: target.doc.uri.toString()
        }
        db.setLocalPath(game.id, path, size)
        emit(game.id, DownloadProgress(game.id, game.romFilename, game.platformCode, size, size, 0, true))
    }

    /** 删除本地 ROM */
    suspend fun deleteLocal(game: Game): Boolean = withContext(Dispatchers.IO) {
        val target = resolveTarget(game)
        val ok = when (target) {
            is RomTarget.File -> {
                val part = File(target.file.parentFile, target.file.name + ".part")
                part.delete()
                target.file.delete()
            }
            is RomTarget.Saf -> target.doc.delete()
        }
        db.clearLocalPath(game.id)
        ok
    }

    suspend fun localBytesUsed(): Long = withContext(Dispatchers.IO) {
        val root = File(prefs.defaultRomStoragePath())
        if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private fun humanError(e: Exception?): String {
        val msg = e?.message ?: e?.javaClass?.simpleName ?: "未知错误"
        return when {
            msg.contains("403") -> "115 限流了 (IP 风控), 换个网络或等几分钟"
            msg.contains("401") -> "115 登录已过期, 去设置里重新登录"
            msg.contains("timeout", true) -> "下载超时, 网络太差了"
            msg.contains("unexpected end of stream", true) -> "连接中断 (可以重新点播放续传)"
            msg.contains("Space", true) || msg.contains("存储", true) -> "手机存储空间不足"
            else -> msg.take(140)
        }
    }

    private fun emit(id: Long, p: DownloadProgress) {
        _active.value = _active.value + (id to p)
    }

    private fun addToQueue(game: Game) {
        if (_queue.value.any { it.gameId == game.id }) return
        _queue.value = _queue.value + DownloadItem(
            gameId = game.id, title = game.displayTitle,
            platform = game.platformCode, filename = game.romFilename, sizeBytes = game.romSize,
        )
    }
}

/** ROM 落点: 普通文件 或 SAF DocumentFile */
sealed class RomTarget {
    abstract fun exists(): Boolean
    abstract fun currentLength(): Long
    abstract fun describe(): String

    data class File(val file: java.io.File) : RomTarget() {
        override fun exists() = file.exists()
        override fun currentLength() = file.takeIf { it.exists() }?.length() ?: 0L
        override fun describe() = file.absolutePath
    }

    data class Saf(val treeUri: Uri, val doc: DocumentFile) : RomTarget() {
        override fun exists() = doc.exists()
        override fun currentLength() = doc.takeIf { it.exists() }?.length() ?: 0L
        override fun describe() = doc.uri.toString()
    }
}

data class DownloadItem(
    val gameId: Long,
    val title: String,
    val platform: String,
    val filename: String,
    val sizeBytes: Long,
)

data class DownloadProgress(
    val gameId: Long,
    val filename: String,
    val platform: String,
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val done: Boolean = false,
    val error: String? = null,
) {
    val percent: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    val formattedSize: String get() = "${human(downloaded)} / ${human(total)}"
    val formattedSpeed: String get() = if (speedBps > 0) "${human(speedBps)}/s" else ""

    companion object {
        fun human(n: Long): String {
            if (n <= 0) return "0 B"
            val u = arrayOf("B", "KB", "MB", "GB")
            var v = n.toDouble(); var i = 0
            while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
            return "%.1f %s".format(v, u[i])
        }
    }
}
