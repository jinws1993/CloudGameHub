package com.nasgame.data.download

import android.content.Context
import android.util.Log
import com.nasgame.data.api.Game
import com.nasgame.data.api.NasGameApi
import com.nasgame.data.api.RomInfoResponse
import com.nasgame.data.prefs.PrefsStore
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
 * 关键设计：
 * 1. **顺序 + 信号量限流** - 同时最多 N 个 (默认 2, 用户可配) 大文件下载
 * 2. **115 CDN 直链** - 服务端返回 JSON {source, url, size, filename}, 客户端用**裸 OkHttp**
 *    下载 (无 AuthInterceptor, 避免 115 CDN 拒签), 不走服务器流量
 * 3. **进度回调** - Flow<DownloadProgress> 暴露给 UI
 * 4. **断点续传** - 校验已下载文件大小, 不匹配重新拉; 未来可加 Range header
 * 5. **缓存路径可配置** - 默认 <app-external>/roms/<plat>, 用户可改成外置 SD / 自定义路径
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    private val TAG = "DownloadManager"

    /**
     * 裸 OkHttp client: 用来下载 115 CDN 直链, 不带 Authorization header.
     *
     * 关键点:
     * - followRedirects(true): 115 CDN 偶尔会重定向到子域名
     * - retryOnConnectionFailure(true): "unexpected end of stream" 通常是 CDN 连接被重置,
     *   OkHttp 内置 retry 大部分情况可恢复
     * - 长 timeout: 大 ROM 慢
     * - **不** add AuthInterceptor: 115 CDN 不认我们 NAS 的 token, 带 Authorization 会 401
     */
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

    /** 每个游戏同时只允许一个下载任务 */
    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** 顺序 + 信号量限流 */
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

    /**
     * ROM 存储根路径.
     * - 用户可在 Settings 改成外置 SD
     * - 默认 <app-external>/roms (卸载 App 自动清理)
     */
    suspend fun romRootDir(): File {
        val p = prefs.romStoragePath()
        val dir = File(p)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 兼容: 当前 ROM 路径 (不 suspend). 默认用 PrefsStore 缓存的路径. */
    fun romRootDirCached(): File {
        val p = prefs.defaultRomStoragePath()
        val dir = File(p)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun romCacheDir(platformCode: String): File {
        val dir = File(romRootDirCached(), platformCode)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 检查 ROM 是否本地已有 + 大小匹配 */
    fun isLocal(game: Game): Boolean {
        val f = romCacheDir(game.platform?.code ?: "misc").resolve(game.romFilename)
        return f.exists() && f.length() == game.romSize && game.romSize > 0
    }

    fun localPath(game: Game): File =
        romCacheDir(game.platform?.code ?: "misc").resolve(game.romFilename)

    /**
     * 探查 ROM 来源 — 让 UI 显示"从哪下"
     *
     * 服务端 /api/games/{id}/rom 行为:
     * - 本地有 → 流式返回 (binary)
     * - 本地无 + 115 → JSON {source:"remote_115", url, size, filename}
     * - 都无 → 404
     */
    @Suppress("UNUSED_PARAMETER")
    fun probeSource(api: NasGameApi, game: Game): RomSource {
        return if (isLocal(game)) RomSource.LOCAL
        else if (game.cloudSource == "115") RomSource.REMOTE_115
        else RomSource.UNKNOWN
    }

    /** 启动一个下载任务. 已经在跑就跳过. */
    fun startDownload(api: NasGameApi, game: Game, onComplete: (File?) -> Unit = {}): Boolean {
        if (inflight.containsKey(game.id.toLong())) {
            Log.d(TAG, "Already downloading ${game.id}")
            return false
        }

        val dest = localPath(game)
        if (dest.exists() && dest.length() == game.romSize && game.romSize > 0) {
            Log.d(TAG, "Already cached: $dest")
            onComplete(dest)
            return false
        }

        addToQueue(game)

        val job = scope.launch {
            sem.withPermit {
                doDownloadWork(api, game, dest, onComplete)
            }
        }
        inflight[game.id.toLong()] = job
        job.invokeOnCompletion { inflight.remove(game.id.toLong()); removeFromQueue(game.id.toLong()) }
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
     * 1. 调 /api/games/{id}/rom
     * 2. 服务端根据是否有本地文件返回:
     *    - 200 + binary body (本地)
     *    - 200 + JSON (115, 客户端跟裸 OkHttp 下载 CDN URL)
     *    - 404 (都没有)
     *
     * 用 Retrofit 调是因为有 auth interceptor (调 server 需要 token).
     * 用裸 OkHttp 下载 115 CDN URL 是因为 115 CDN 不认我们 NAS token.
     */
    private suspend fun doDownloadWork(
        api: NasGameApi,
        game: Game,
        dest: File,
        onComplete: (File?) -> Unit,
    ) {
        val gid = game.id.toLong()
        val platCode = game.platform?.code ?: "misc"
        Log.i(TAG, "Start download: $platCode/${game.romFilename} (${game.romSize} bytes)")

        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")

        try {
            // 1. 拿到下载信息 (本地 or 115)
            val info = api.romInfo(gid)
            Log.i(TAG, "RomInfo: source=${info.source}, size=${info.size}, url=${info.url.take(80)}...")

            val total = info.size.takeIf { it > 0 } ?: game.romSize
            val filename = info.filename.ifBlank { game.romFilename }

            // 2. 进度占位
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid,
                romFilename = filename,
                platform = platCode,
                downloaded = 0,
                total = total,
                speedBps = 0,
                done_ = false,
            ))

            // 3. 下载 body
            when (info.source) {
                "local" -> {
                    // 服务端流式返回 (走 Retrofit + Auth header)
                    val body = api.downloadRom(gid)
                    writeToFile(body.byteStream(), part, total, gid, filename, platCode)
                }
                "remote_115" -> {
                    // 115 CDN — 裸 OkHttp, 不带 Authorization (避免 115 CDN 401)
                    if (info.url.isBlank()) {
                        throw IOException("服务端返回空 URL")
                    }
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
                    try {
                        writeToFile(body.byteStream(), part, total, gid, filename, platCode)
                    } finally {
                        body.close()
                    }
                }
                else -> throw IOException("未知 source: ${info.source}")
            }

            if (part.length() != total && total > 0) {
                throw IOException("下载不完整: ${part.length()} / $total")
            }

            if (dest.exists()) dest.delete()
            part.renameTo(dest)

            Log.i(TAG, "Done: $dest (${dest.length()} bytes)")
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid,
                romFilename = filename,
                platform = platCode,
                downloaded = total,
                total = total,
                speedBps = 0,
                done_ = true,
            ))
            onComplete(dest)
        } catch (e: CancellationException) {
            part.delete()
            Log.w(TAG, "Cancelled: ${game.romFilename}")
            _active.value = _active.value - gid
            onComplete(null)
        } catch (e: Exception) {
            part.delete()
            Log.e(TAG, "Download failed: ${game.romFilename}", e)
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid,
                romFilename = game.romFilename,
                platform = platCode,
                downloaded = 0,
                total = game.romSize,
                speedBps = 0,
                done_ = false,
                error = humanError(e),
            ))
            onComplete(null)
        }
    }

    /** 把 input 流写到 part 文件, 同时推送进度 */
    private suspend fun writeToFile(
        input: java.io.InputStream,
        part: File,
        total: Long,
        gid: Long,
        filename: String,
        platCode: String,
    ) = withContext(Dispatchers.IO) {
        part.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastEmit = 0L
            var lastBytes = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - lastEmit > 250 || done == total) {
                    val dt = (now - lastEmit).coerceAtLeast(1)
                    val speed = ((done - lastBytes) * 1000 / dt).coerceAtLeast(0)
                    _active.value = _active.value + (gid to DownloadProgress(
                        gameId = gid,
                        romFilename = filename,
                        platform = platCode,
                        downloaded = done,
                        total = total,
                        speedBps = speed,
                        done_ = done >= total,
                    ))
                    lastEmit = now
                    lastBytes = done
                }
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

enum class RomSource {
    LOCAL,       // 服务器本地
    REMOTE_115, // 115 CDN (裸 OkHttp 下载)
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