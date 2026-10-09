package com.cloudgamehub.data.scrape

import android.util.Log
import com.cloudgamehub.data.db.CloudDb
import com.cloudgamehub.data.media.MediaStore
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.prefs.PrefsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 刮削引擎。把 115 上的裸文件名变成"有封面有简介"的游戏库。
 *
 * 策略是**多源兜底**, 任何一环挂了都不会让整个流程停摆:
 *
 * ```
 * 1. AI (OpenAI 兼容)     → 标题/年份/厂商/类型/简介/中文名
 * 2. libretro 缩略图        → 封面 (免费无 key)
 * 3. 没有 AI 也无所谓       → 至少文件名清洗后的标题能用
 * ```
 *
 * AI 是**可选**的: 没配 AI key 也能跑, 只是少了简介和中文译名。
 * 这点很重要 —— 国内不少用户没有可用的 AI 接口, 不能因此卡住。
 */
@Singleton
class ScrapeEngine @Inject constructor(
    private val db: CloudDb,
    private val prefs: PrefsStore,
    private val ai: AiClient,
    private val thumbs: LibretroThumbnails,
    private val media: MediaStore,
) {
    private val TAG = "ScrapeEngine"

    /** 刮削单个游戏 */
    suspend fun scrape(game: Game): ScrapeResult = withContext(Dispatchers.IO) {
        var current = game
        val sources = ArrayList<String>()

        // ---- 1. AI 元数据 (可选) ----
        val baseUrl = prefs.aiBaseUrl()
        val apiKey = prefs.aiApiKey()
        if (ai.isReady(baseUrl, apiKey) && prefs.aiEnabled()) {
            val model = prefs.aiModel()
            val enriched = ai.identify(baseUrl, apiKey, model, current.romFilename, current.platformCode)
            if (enriched != null) {
                current = current.copy(
                    title = current.title.ifBlank { enriched.titleEn },
                    titleEn = enriched.titleEn.ifBlank { current.titleEn },
                    titleZh = enriched.titleZh,
                    releaseDate = enriched.releaseDate.ifBlank { current.releaseDate },
                    developer = enriched.developer,
                    publisher = enriched.publisher,
                    genre = enriched.genre,
                    players = enriched.players,
                    description = enriched.description.ifBlank { current.description },
                )
                sources += "ai"
            } else {
                Log.w(TAG, "AI 未命中: ${current.romFilename}")
            }
        }

        // ---- 2. 封面 ----
        if (current.coverFile.isBlank() || !media.exists(current.coverFile)) {
            val cover = thumbs.fetchBoxArt(current.platformCode, current, media.dir())
            if (cover != null) {
                current = current.copy(coverFile = cover)
                sources += "libretro"
            }
        }

        // ---- 3. 收尾 ----
        if (current.title.isBlank()) current = current.copy(title = current.titleRaw)

        val ok = current.titleEn.isNotBlank() || current.titleZh.isNotBlank() || current.title.isNotBlank()
        val final = current.copy(
            scrapeStatus = if (ok) "done" else "failed",
            scrapeSource = sources.joinToString("+").ifBlank { "filename" },
        )
        if (ok) db.updateMetadata(final.id, final)
        ScrapeResult(final, final.scrapeSource, ok, if (ok) null else "没认出这个游戏")
    }

    /**
     * 批量刮削。一批一批来, 每批之间让出线程, 别把 UI 卡死。
     *
     * @param onEach 每个游戏刮完回调 (成功/失败都会走)
     */
    suspend fun scrapeBatch(
        batch: List<Game>,
        onEach: (ScrapeResult) -> Unit,
        shouldStop: () -> Boolean = { false },
    ) {
        for (g in batch) {
            if (shouldStop()) return
            try {
                onEach(scrape(g))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scrape ${g.romFilename} 失败: ${e.message}")
                onEach(ScrapeResult(g, "failed", false, e.message))
            }
        }
    }

    /** 重新刮某一个 (用户手动触发) */
    suspend fun rescrape(game: Game): ScrapeResult {
        val fresh = game.copy(
            scrapeStatus = "pending", coverFile = "", titleEn = "", titleZh = "", description = "",
        )
        return scrape(fresh)
    }
}
