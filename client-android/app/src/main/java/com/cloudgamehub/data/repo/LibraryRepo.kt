package com.cloudgamehub.data.repo

import android.util.Log
import com.cloudgamehub.data.db.CloudDb
import com.cloudgamehub.data.media.MediaStore
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.model.Platform
import com.cloudgamehub.data.model.ScanState
import com.cloudgamehub.data.pan115.Pan115Client
import com.cloudgamehub.data.pan115.Pan115Item
import com.cloudgamehub.data.prefs.PrefsStore
import com.cloudgamehub.data.scan.PlatformDetector
import com.cloudgamehub.data.scan.RomNameParser
import com.cloudgamehub.data.scrape.ScrapeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 库的读写 + 扫描/刮削的编排。
 *
 * 一次"扫描"是这样走的:
 *
 * ```
 * 1. 用户在 115 里挑一个 ROM 根目录 (记 cid)
 * 2. 递归 walk → 只收 ROM 扩展名的文件 (或全收)
 * 3. 按 目录名 + 扩展名 归类到 25 个平台
 * 4. 文件名清洗成标题, 入库 (pickcode 去重, 已有的跳过)
 * 5. 可选: 自动刮削 (AI → 缩略图)
 * ```
 */
@Singleton
class LibraryRepo @Inject constructor(
    private val db: CloudDb,
    private val pan: Pan115Client,
    private val prefs: PrefsStore,
    private val scraper: ScrapeEngine,
    private val media: MediaStore,
) {
    private val TAG = "LibraryRepo"

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    // ==================== 读 ====================

    fun game(id: Long): Game? = db.gameById(id)

    fun query(
        platform: String? = null, search: String? = null,
        favoriteOnly: Boolean = false, sort: String = "title",
        page: Int = 1, pageSize: Int = 60,
    ): List<Game> = db.queryGames(platform, search, favoriteOnly, sort, page, pageSize)

    fun countAll(): Int = db.countGames()
    fun countByPlatform(): Map<String, Int> = db.countByPlatform()
    fun platforms(): List<Platform> = db.platforms()
    fun pendingScrapeCount(): Int = db.countPendingScrape()
    fun totalRomBytes(): Long = db.totalRomBytes()
    fun localBytes(): Long = db.totalLocalBytes()
    fun localCount(): Int = db.localCount()

    /** 库列表用的统一过滤条件 (刮削完的优先排前面) */
    fun displayedCount(platform: String? = null, search: String? = null): Int =
        query(platform = platform, search = search, pageSize = Int.MAX_VALUE.coerceAtMost(100000)).size

    suspend fun toggleFavorite(game: Game): Boolean = withContext(Dispatchers.IO) {
        db.toggleFavorite(game.id)
    }

    // ==================== 115 目录浏览 ====================

    /** 115 目录树, 给选目录页面用 */
    suspend fun browse115(cid: String): List<Pan115Item> = pan.listDir(cid, 200)

    suspend fun breadcrumb(cid: String): List<Pair<String, String>> = pan.pathOf(cid)

    suspend fun loginCheck(): String = pan.checkLogin()

    // ==================== 扫描 ====================

    /**
     * 扫描 + 建库 + (可选) 刮削。整个过程的状态放在 [scan] 里给 UI 观察。
     *
     * @param onFinished 全部完成后的回调
     */
    suspend fun scanAndImport(
        rootCid: String,
        rootPath: String,
        autoScrape: Boolean,
        onFinished: (added: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        _scan.value = ScanState(running = true, phase = "连接 115", current = rootPath)
        try {
            val depth = prefs.scanDepth()
            val allExt = prefs.scanAllExtensions()
            val exts = if (allExt) null else PlatformDetector.romExtensions()

            _scan.value = _scan.value.copy(phase = "扫描目录", message = "深度上限 $depth 层")

            val items = pan.walkFiles(rootCid, exts, depth) { found, path ->
                _scan.value = _scan.value.copy(
                    scanned = found,
                    current = path,
                    phase = "扫描目录",
                )
            }
            Log.i(TAG, "扫描到 ${items.size} 个候选文件")

            // ---- 入库 ----
            _scan.value = _scan.value.copy(phase = "建立游戏库", total = items.size)
            val existing = db.allPickcodes()
            var added = 0
            val batch = ArrayList<Game>(512)
            for ((idx, item) in items.withIndex()) {
                if (item.pickcode.isNotBlank() && item.pickcode in existing) continue
                batch += buildGame(item, rootPath)
                if (batch.size >= 500) {
                    added += db.upsertGames(batch); batch.clear()
                }
                if (idx % 50 == 0) {
                    _scan.value = _scan.value.copy(
                        scanned = added, total = items.size, phase = "建立游戏库",
                    )
                }
            }
            if (batch.isNotEmpty()) added += db.upsertGames(batch)
            Log.i(TAG, "新增 $added 个游戏")

            _scan.value = _scan.value.copy(phase = "完成", scanned = added, total = items.size)

            // ---- 刮削 ----
            if (autoScrape) {
                scrapeAllPending { done, total ->
                    _scan.value = _scan.value.copy(
                        phase = "刮削游戏信息",
                        scraped = done, total = total, scanned = added,
                        message = "AI 刮削要花点时间, 可以放着不管",
                    )
                }
            }

            _scan.value = _scan.value.copy(
                running = false, done = true, phase = "完成",
                message = "新增 $added 个游戏",
            )
            onFinished(added, items.size)
        } catch (e: CancellationException) {
            _scan.value = _scan.value.copy(running = false, phase = "已取消", error = "扫描已取消")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "扫描失败", e)
            _scan.value = _scan.value.copy(
                running = false, phase = "失败",
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun buildGame(item: Pan115Item, rootPath: String): Game {
        val parsed = RomNameParser.parse(item.name)
        val fullPath = if (rootPath.isBlank()) item.name else "$rootPath/${item.name}"
        // 先只看文件名判断, 再用完整路径 (含目录) 判断, 目录优先
        val byPath = PlatformDetector.detect(fullPath, item.name)
        val code = if (byPath != "misc") byPath else PlatformDetector.detect(item.name, item.name)
        return Game(
            platformCode = code,
            pickcode = item.pickcode,
            pathDisplay = fullPath,
            romFilename = item.name,
            romSize = item.size,
            sha1 = item.sha1,
            titleRaw = parsed.rawTitle,
            title = parsed.title,
            releaseDate = parsed.year,
            scrapeStatus = "pending",
        )
    }

    // ==================== 刮削 ====================

    /** 把库里所有还没刮的游戏刮一遍 */
    suspend fun scrapeAllPending(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val total = db.countPendingScrape()
        var done = 0
        var batch = db.pendingGames(limit = 30)
        while (batch.isNotEmpty()) {
            scraper.scrapeBatch(batch) {
                done++
                onProgress(done, total)
            }
            // 继续取下一批 (刚刮完的已经不算 pending 了)
            if (db.countPendingScrape() <= 0) break
            val next = db.pendingGames(limit = 30)
            if (next.isEmpty() || next.map { it.id } == batch.map { it.id }) break
            batch = next
        }
    }

    /** 重新刮某一个 (详情页手动触发) */
    suspend fun rescrape(game: Game) = withContext(Dispatchers.IO) { scraper.rescrape(game) }

    /** 手动上传封面 */
    suspend fun setManualCover(game: Game, bytes: ByteArray, ext: String): Boolean =
        withContext(Dispatchers.IO) {
            val name = media.saveManualCover(game.id, bytes, ext) ?: return@withContext false
            val updated = game.copy(coverFile = name, scrapeStatus = "done", scrapeSource = "manual")
            db.updateMetadata(game.id, updated)
            true
        }

    /** 清理没被引用的封面 */
    suspend fun gcMedia(): Int = withContext(Dispatchers.IO) {
        val keep = HashSet<String>()
        var page = 1
        while (true) {
            val batch = db.queryGames(page = page, pageSize = 200)
            if (batch.isEmpty()) break
            batch.forEach { if (it.coverFile.isNotBlank()) keep += it.coverFile }
            page++
            if (page > 200) break
        }
        media.gc(keep)
    }

    fun resetScanState() {
        _scan.value = ScanState()
    }
}
