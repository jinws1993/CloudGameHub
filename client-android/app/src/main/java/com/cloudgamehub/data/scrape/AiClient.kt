package com.cloudgamehub.data.scrape

import android.util.Log
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 刮削结果 */
data class ScrapeResult(
    val game: Game,
    val source: String,     // "ai" | "libretro" | "ai+libretro" | "failed"
    val ok: Boolean,
    val error: String? = null,
)

/**
 * AI 刮削。走 OpenAI 兼容的 `/chat/completions` 接口, 所以 DeepSeek /
 * Kimi / 通义 / MiniMax / 本地 Ollama 都能用, 只要 base_url 对得上。
 */
@Singleton
class AiClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 不带密钥也能用的本地模型 (Ollama / LM Studio) 也能连 */
    fun isReady(baseUrl: String, apiKey: String): Boolean =
        baseUrl.isNotBlank() && (apiKey.isNotBlank() || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1"))

    private val systemPrompt = """
你是资深怀旧游戏数据库编辑。给我一个杂乱的 ROM 文件名, 你要认出它到底是哪个游戏,
然后返回结构化信息。

**只输出一个 JSON 对象**, 不要 markdown 代码块, 不要任何解释文字。

字段:
{
  "title_en": "官方英文名",
  "title_jp": "日文原名, 不知道就空字符串",
  "title_zh": "中文常用名 (务必用玩家圈通行的叫法, 比如「最终幻想7」而不是「太空战士7」)",
  "year": "1996",
  "developer": "Square",
  "publisher": "Square",
  "genre": "RPG",
  "players": "1",
  "description_zh": "2-3 句简体中文简介",
  "description_en": "2-3 sentences in English",
  "confidence": 0.95
}

规则:
- 不确定就把 confidence 调低, 不要瞎编。
- 不知道的字段一律空字符串, 宁可空着也别猜。
- platform_hint 给了就以它为准, 不要推翻。
- 中文名优先用大陆玩家最熟的那个译名。
""".trimIndent()

    /**
     * 认一个游戏。返回 null 表示失败 (调用方应回退到别的源)。
     */
    suspend fun identify(
        baseUrl: String,
        apiKey: String,
        model: String,
        filename: String,
        platformHint: String,
    ): Game? = withContext(Dispatchers.IO) {
        if (!isReady(baseUrl, apiKey)) return@withContext null
        val url = baseUrl.trimEnd('/') + "/chat/completions"

        val userMsg = buildString {
            append("ROM 文件名: ").append(filename).append("\n")
            if (platformHint.isNotBlank()) {
                append("平台: ").append(platformHint).append("\n")
                Platform.of(platformHint)?.let {
                    append("（该平台有这些扩展名: ").append(it.extensions.joinToString(",")).append("）\n")
                }
            }
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("stream", false)
            put(
                "messages",
                org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userMsg))
                }
            )
        }

        try {
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "AI HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@use null
                }
                val text = JSONObject(resp.body!!.string())
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                parseAiJson(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI identify failed for $filename: ${e.message}")
            null
        }
    }

    /**
     * 从模型回复里抠出 JSON。
     *
     * 两个坑:
     * 1. 推理模型 (DeepSeek-R1 / MiniMax-M2 / o1) 会先吐一整段 `<think>...</think>`,
     *    里面常有没闭合的 JSON 片段, 不剥掉直接 parse 必挂。
     * 2. 有些模型会给 JSON 套 markdown 代码块。
     */
    private fun parseAiJson(raw: String): Game? {
        if (raw.isBlank()) return null
        var content = raw.replace(Regex("<think>.*?</think>", RegexOption.DOTALL), "").trim()
        if (content.isBlank()) {
            Log.w(TAG, "AI 只返回了 <think> 块")
            return null
        }
        val json = extractJson(content) ?: return null
        return try {
            val o = JSONObject(json)
            Game(
                titleEn = o.optString("title_en").ifBlank { o.optString("title").ifBlank { o.optString("name") } },
                titleZh = o.optString("title_zh"),
                releaseDate = o.optString("year").ifBlank { o.optString("releaseYear") },
                developer = o.optString("developer"),
                publisher = o.optString("publisher"),
                genre = o.optString("genre"),
                players = o.optString("players"),
                description = o.optString("description_zh").ifBlank { o.optString("description_en") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "AI JSON 解析失败: ${content.take(200)}")
            null
        }
    }

    private fun extractJson(text: String): String? {
        // 先试直接 parse
        if (text.startsWith("{")) {
            val end = balancedEnd(text, 0)
            if (end > 0) return text.substring(0, end + 1)
        }
        // 再试 markdown 围栏
        Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOTALL).find(text)?.let {
            return it.groupValues[1]
        }
        // 最后找第一个 { 到配对的 }
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = balancedEnd(text, start)
        return if (end > 0) text.substring(start, end + 1) else null
    }

    /** 找与 [start] 处 '{' 配对的 '}' 的下标 (考虑字符串里的括号) */
    private fun balancedEnd(text: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    companion object { private val TAG = "AiClient" }
}

/**
 * libretro 官方缩略图。免费、无需 key, 是 AI 之外的可靠兜底。
 *
 * `https://thumbnails.libretro.com/<System>/Named_Boxarts/<rom name>.png`
 */
@Singleton
class LibretroThumbnails @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 我们的平台 code → libretro 系统目录名 */
    private val SYSTEM_FOLDERS = mapOf(
        "FC" to "Nintendo - Nintendo Entertainment System",
        "SFC" to "Nintendo - Super Nintendo Entertainment System",
        "N64" to "Nintendo - Nintendo 64",
        "GB" to "Nintendo - Game Boy",
        "GBC" to "Nintendo - Game Boy Color",
        "GBA" to "Nintendo - Game Boy Advance",
        "NDS" to "Nintendo - Nintendo DS",
        "3DS" to "Nintendo - 3DS",
        "MD" to "Sega - Mega Drive - Genesis",
        "SATURN" to "Sega - Saturn",
        "DC" to "Sega - Dreamcast",
        "PS1" to "Sony - PlayStation",
        "PS2" to "Sony - PlayStation 2",
        "PSP" to "Sony - PlayStation Portable",
        "WII" to "Nintendo - Wii",
        "GC" to "Nintendo - GameCube",
        "PCE" to "NEC - PC Engine - TurboGrafx 16",
        "NEOGEO" to "SNK - Neo Geo",
        "MAME" to "MAME",
        "ARCADE" to "MAME",
    )

    /** No-Intro 风格的标签, 去掉之后更容易命中 */
    private val TAG_RE = Regex(
        "\\s*[(\\[](" +
            "USA|Europe|Japan|World|Asia|China|Korea|HK|TW" +
            "|En|Ja|Zh|Fr|De|Es|It|Pt|Ru|Ko" +
            "|Rev\\s*\\d+|v\\d+\\.\\d+|Beta|Proto|Sample|Demo|Unl|Hack" +
            "|Disc\\s*\\d+|Disk\\s*\\d+|Side\\s*[AB]|Part\\s*\\d+|[!]" +
            ")[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 按多个候选名去抓封面, 第一个命中的写进 [dest]。
     * @return 命中的文件名 (即 game.coverFile), 没命中返回 null
     */
    suspend fun fetchBoxArt(
        platformCode: String, game: Game, mediaDir: File,
    ): String? = withContext(Dispatchers.IO) {
        val system = SYSTEM_FOLDERS[platformCode.uppercase()] ?: return@withContext null
        val stem = com.cloudgamehub.data.pan115.Pan115Client.stemOf(game.romFilename)

        val candidates = buildList {
            add(stem)                                       // 原名
            add(TAG_RE.replace(stem, "").trim().ifBlank { null })   // 去标签
            add(stem.replace(Regex("\\s+"), " "))
            // 去掉末尾的 " (Disc 1)" 之类
            add(stem.replace(Regex("\\s*\\(Disc\\s*\\d+\\)$", RegexOption.IGNORE_CASE), ""))
        }.filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct()

        for (name in candidates) {
            val url = "https://thumbnails.libretro.com/$system/Named_Boxarts/" +
                name.replace(" ", "%20").replace("#", "%23") + ".png"
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 200) return@use false
                    val body = resp.body ?: return@use false
                    val ct = body.contentType()?.type().orEmpty()
                    if (ct != "image") return@use false
                    val bytes = body.bytes()
                    if (bytes.size < 1024) return@use false    // 多半是占位图
                    if (!mediaDir.exists()) mediaDir.mkdirs()
                    val file = File(mediaDir, "cover_${game.platformCode}_${System.currentTimeMillis()}.png")
                    file.writeBytes(bytes)
                    return@withContext file.name
                }
            } catch (_: Exception) {
                // 试下一个候选名
            }
        }
        null
    }
}
}
