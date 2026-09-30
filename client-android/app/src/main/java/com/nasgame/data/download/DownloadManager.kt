package com.nasgame.data.download

import android.content.Context
import android.util.Log
import com.nasgame.data.api.Game
import com.nasgame.data.api.NasGameApi
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
 * 1. **顺序 + 信号量限流** - 同时最多 N 个 (默认 2, 用户可配) 大文件下载, 避免手机带宽 / 内存爆掉
 * 2. **跟 302 跳转** - OkHttp 默认 follow redirect. 服务端会 302 到 115 CDN, 直连拉文件不走服务器流量
 * 3. **进度回调** - Flow<DownloadProgress> 暴露给 UI
 * 4. **断点续传** - 校验已下载文件大小, 不匹配重新拉; 未来可加 Range header
 * 5. **缓存路径** - getExternalFilesDir() /roms/<plat_code>/<filename>, 用户卸载 App 自动清理
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    private val TAG = "DownloadManager"

    /** 手动注入 OkHttp (用单独客户端, 大文件 timeout 长) */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)   // 大 ROM 可能慢
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

    /** 重新配置并发数 (清掉旧的 + 用新的) */
    fun updateConcurrency(n: Int) {
        scope.launch {
            sem = Semaphore(n.coerceAtLeast(1))
            prefs.setConcurrentDownloads(n)
        }
    }

    /** 暴露一个独立的 OkHttp 实例给 Raw 304/Range 调用 */
    fun rawHttpClient(): OkHttpClient = client

    fun romCacheDir(platformCode: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "roms/$platformCode")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 检查 ROM 是否本地已有 + 大小匹配 */
    fun isLocal(game: Game): Boolean {
        val f = romCacheDir(game.platform?.code ?: "misc").resolve(game.romFilename)
        return f.exists() && f.length() == game.romSize && game.romSize > 0
    }

    /** 本地 ROM 路径 (不管是否完整) */
    fun localPath(game: Game): File =
        romCacheDir(game.platform?.code ?: "misc").resolve(game.romFilename)

    /**
     * 探查 ROM 来源 — 让 UI 显示"从哪下"
     *
     * 服务端 /api/games/{id}/rom 的行为：
     * - 本地有 → 流式返回 (Content-Length 已知)
     * - 本地无 + 115 启用 → 302 到 CDN (Network 跟 redirect 后, Content-Length 已知)
     * - 本地无 + 115 未启用 → 404
     *
     * UI 显示策略：调用方只需知道 ROM 是不是本地已有。
     * 1. isLocal() = true → 走本地
     * 2. isLocal() = false 但 cloudSource == "115" 且 server 启用 → 走 115 CDN
     * 3. 其他 → 下载失败
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

        // 加队列
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

    /** 取消 */
    fun cancel(gameId: Long) {
        inflight.remove(gameId)?.cancel()
        removeFromQueue(gameId)
    }

    /** 取消全部 */
    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        _queue.value = emptyList()
    }

    private suspend fun doDownloadWork(
        api: NasGameApi,
        game: Game,
        dest: File,
        onComplete: (File?) -> Unit,
    ) {
        val gid = game.id.toLong()
        val platCode = game.platform?.code ?: "misc"
        Log.i(TAG, "Start download: $platCode/${game.romFilename} (${game.romSize} bytes)")

        // 临时文件 (.part) — 下载完 rename 上去, 防中断留半成品
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")

        try {
            val body = api.downloadRom(gid)
            val total = body.contentLength().takeIf { it > 0 } ?: game.romSize

            // 进度
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        // 每 200ms 推送一次进度, 避免 UI 风暴
                        if (now - lastEmit > 200 || done == total) {
                            lastEmit = now
                            val speed = if (lastEmit > 0) (n.toLong() * 1000 / 200.coerceAtLeast(now - lastEmit + 200)) else 0L
                            _active.value = _active.value + (gid to DownloadProgress(
                                gameId = gid,
                                romFilename = game.romFilename,
                                platform = platCode,
                                downloaded = done,
                                total = total,
                                speedBps = speed,
                                done_ = done >= total,
                            ))
                        }
                    }
                }
            }

            if (part.length() != total && total > 0) {
                throw IOException("下载不完整: ${part.length()} / $total")
            }

            // 重命名为正式文件
            if (dest.exists()) dest.delete()
            part.renameTo(dest)

            Log.i(TAG, "Done: $dest (${dest.length()} bytes)")
            _active.value = _active.value + (gid to DownloadProgress(
                gameId = gid,
                romFilename = game.romFilename,
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
                error = e.message ?: e.javaClass.simpleName,
            ))
            onComplete(null)
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
    REMOTE_115, // 115 CDN (302 redirect)
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