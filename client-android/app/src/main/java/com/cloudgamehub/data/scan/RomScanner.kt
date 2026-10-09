package com.cloudgamehub.data.scan

import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.model.Platform
import com.cloudgamehub.data.pan115.Pan115Client
import com.cloudgamehub.data.pan115.Pan115Item

/**
 * 把 115 上的杂乱文件名变成结构化信息。
 *
 * 现实中的 ROM 文件名长这样:
 * `Chrono Trigger (USA) (Rev 1) [b].sfc`
 * `洛克人传说 4 (强化版) (Disc 1).iso`
 * 这里把它们拆成 标题 / 地区 / 语言 / 标签 / 光盘号。
 */
object RomNameParser {

    /** 地区优先选中文/港台版本, 因为目标用户是中文玩家 */
    private val REGION_RANK = mapOf(
        "China" to 0, "TW" to 1, "HK" to 1, "Japan" to 2, "Asia" to 3,
        "Korea" to 4, "USA" to 5, "Europe" to 6, "World" to 7,
    )

    private val TAG_RE = Regex(
        "\\s*[(\\[](" +
            "USA|Europe|Japan|World|Asia|China|Korea|HK|TW" +
            "|En|Ja|Zh|Fr|De|Es|It|Pt|Ru|Ko" +
            "|Rev\\s*\\d+|v\\d+\\.\\d+|Beta|Proto|Sample|Demo|Unl|Hack" +
            "|Disc\\s*\\d+|Disk\\s*\\d+|Side\\s*[AB]|Part\\s*\\d+" +
            "|[!]" +
            ")[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    private val REGIONS = setOf("USA", "Europe", "Japan", "World", "Asia", "China", "Korea", "HK", "TW")
    private val LANGS = setOf("En", "Ja", "Zh", "Fr", "De", "Es", "It", "Pt", "Ru", "Ko")

    data class Parsed(
        val rawTitle: String,
        val title: String,
        val region: String,
        val languages: List<String>,
        val tags: List<String>,
        val disc: Int?,
        val year: String = "",
    )

    fun parse(filename: String): Parsed {
        val stem = Pan115Client.stemOf(filename)
        val tags = ArrayList<String>()
        val langs = ArrayList<String>()
        val regions = ArrayList<String>()
        var disc: Int? = null

        for (m in TAG_RE.findAll(stem)) {
            val v = m.groupValues[1]
            when {
                v.startsWith("Disc", true) -> {
                    disc = v.substringAfter(' ').trim().toIntOrNull()
                    tags += v
                }
                v.startsWith("Disk", true) || v.startsWith("Side", true) || v.startsWith("Part", true) ->
                    tags += v
                v in REGIONS -> regions += v.uppercase()
                v in LANGS -> langs += v
                else -> tags += v
            }
        }

        // 多个地区时挑优先级最高的当主地区
        val region = regions.minByOrNull { REGION_RANK[it] ?: 99 } ?: ""

        var title = TAG_RE.replace(stem, "")
        title = Regex("\\s+-\\s*$").replace(title, "")
        title = Regex("\\s{2,}").replace(title, " ")
        title = title.trim(' ', '-', '_', '.', '(', ')', '[', ']')

        return Parsed(
            rawTitle = stem,
            title = title.ifBlank { stem },
            region = region,
            languages = langs,
            tags = tags,
            disc = disc,
            year = extractYear(stem),
        )
    }

    /** 从文件名里捞年份: "Final Fantasy VII (1997)" → 1997 */
    fun extractYear(text: String): String =
        Regex("\\b(19[89]\\d|20[0-4]\\d)\\b").find(text)?.value ?: ""
}

/**
 * 扩展名 / 目录名 → 平台。
 *
 * 单靠扩展名有歧义 (iso / bin / cue / chd 十几个平台都在用), 所以策略是
 * **先看目录名, 再看扩展名** —— 多数人 115 上的目录结构是 `/roms/PS1/...` 这种,
 * 目录名比扩展名靠谱得多。
 */
object PlatformDetector {

    /** 有歧义、必须靠目录名才能定的扩展名 */
    private val AMBIGUOUS = setOf("iso", "bin", "cue", "chd", "img", "ccd", "mds", "zip", "gcm", "ciso")

    private val FOLDER_HINTS: Map<String, String> = buildMap {
        // 目录名 (小写) → 平台
        listOf(
            "fc", "nes", "红白机", "小霸王", "famicom", "nesgames" to "FC",
            "sfc", "snes", "super famicom", "super nintendo", "超级任天堂", "超任" to "SFC",
            "n64", "nintendo 64" to "N64",
            "gb", "gameboy", "game boy" to "GB",
            "gbc", "game boy color" to "GBC",
            "gba", "game boy advance" to "GBA",
            "nds", "ds", "nintendo ds" to "NDS",
            "3ds" to "3DS",
            "md", "mdp", "genesis", "mega drive", "megadrive", "魂斗罗" to "MD",
            "saturn", "ss", "sega saturn", "土星" to "SATURN",
            "dc", "dreamcast", "gdrom" to "DC",
            "ps", "ps1", "psx", "playstation", "psone" to "PS1",
            "ps2", "playstation2" to "PS2",
            "psp", "playstation portable", "掌机" to "PSP",
            "wii", "wii u" to "WII",
            "gc", "gamecube" to "GC",
            "pce", "pc engine", "turbo grafx", "tg16" to "PCE",
            "neogeo", "neo geo", "snk" to "NEOGEO",
            "mame", "arcade", "街机", "capcom", "cps1", "cps2", "cps3" to "ARCADE",
            "j2me", "java", "手机游戏" to "J2ME",
            "dos", "pc" to "DOS",
            "flash", "swf", "flash游戏" to "FLASH",
            "html", "网页游戏", "html5" to "HTML",
        ).forEach { (keys, code) -> keys.forEach { put(it, code) } }
    }

    /** 全部扩展名 → 平台 (不含歧义扩展名) */
    private val EXT_MAP: Map<String, String> = buildMap {
        Platform.ALL.forEach { p -> p.extensions.forEach { ext -> put(ext.lowercase(), p.code) } }
        // 消歧: 这些扩展名默认归属
        put("nes", "FC"); put("fds", "FC")
        put("smc", "SFC"); put("fig", "SFC"); put("sfc", "SFC"); put("swc", "SFC")
        put("gb", "GB"); put("gmb", "GB")
        put("gbc", "GBC"); put("cgb", "GBC")
        put("gba", "GBA"); put("agb", "GBA")
        put("nds", "NDS"); put("dsi", "NDS")
        put("3ds", "3DS"); put("cia", "3DS")
        put("md", "MD"); put("gen", "MD"); put("smd", "MD")
        put("gdi", "DC"); put("cdi", "DC")
        put("pbp", "PSP"); put("cso", "PSP")
        put("wad", "WII"); put("wbfs", "WII"); put("rvz", "WII"); put("nkit", "WII")
        put("gcm", "GC"); put("ciso", "GC")
        put("pce", "PCE"); put("sgx", "PCE")
        put("neo", "NEOGEO")
        put("jar", "J2ME")
        put("exe", "WIN"); put("msi", "WIN")
        put("bat", "DOS"); put("com", "DOS")
        put("swf", "FLASH")
        put("html", "HTML"); put("htm", "HTML")
    }

    /** 常见打包格式 */
    private val ARCHIVE_EXTS = setOf("zip", "7z", "rar", "tar", "gz")

    /**
     * 判断一个文件属于哪个平台。
     *
     * @param pathDisplay 完整路径 (含目录), 用来读目录名做消歧
     * @return 平台 code; 判断不出来返回 "misc"
     */
    fun detect(pathDisplay: String, filename: String): String {
        val ext = Pan115Client.extOf(filename).lowercase()

        // 1. 目录名优先 (从最深往上找, 越具体的目录越可信)
        val dirs = pathDisplay.split('/').dropLast(1)
        for (i in dirs.indices.reversed()) {
            val d = normalize(dirs[i])
            FOLDER_HINTS[d]?.let { return it }
        }

        // 2. 扩展名
        if (ext.isNotBlank()) {
            // 压缩包先看内部扩展名 (xxx.nes.zip 这种太常见了)
            if (ext in ARCHIVE_EXTS) {
                val inner = filename.substringBeforeLast('.').substringAfterLast('.', "")
                if (inner.isNotBlank()) {
                    val innerExt = inner.lowercase()
                    if (EXT_MAP[innerExt] != null && innerExt !in AMBIGUOUS) return EXT_MAP[innerExt]!!
                }
            }
            if (ext !in AMBIGUOUS) EXT_MAP[ext]?.let { return it }
            if (ext == "cue") return "SATURN"
            if (ext == "zip") return "MAME"
            if (ext == "iso") return "PS1"     // 目录也没提示时的最终兜底
        }
        return "misc"
    }

    /** 是不是"可能是个 ROM"的扩展名 (扫描时用来过滤, 避免把整个网盘的文件都扫进来) */
    fun looksLikeRom(filename: String): Boolean {
        val ext = Pan115Client.extOf(filename).lowercase()
        if (ext.isBlank()) return false
        if (ext in ARCHIVE_EXTS) {
            val inner = filename.substringBeforeLast('.').substringAfterLast('.', "").lowercase()
            if (inner.isNotBlank() && EXT_MAP.containsKey(inner)) return true
            // 纯 zip 也可能是 MAME 街机集
            return ext == "zip"
        }
        return EXT_MAP.containsKey(ext)
    }

    /** 该文件可接受的全部扩展名 (给 Pan115Client.walkFiles 用) */
    fun romExtensions(): Set<String> =
        (EXT_MAP.keys + ARCHIVE_EXTS).toMutableSet()

    private fun normalize(dirName: String): String =
        dirName.trim().lowercase().replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ")

    // ================= 115 条目 → Game =================

    /**
     * 把 115 上扫到的文件转成 Game。
     * 封面、简介这些都留空, 等刮削引擎来填。
     */
    fun toGame(item: Pan115Item, rootPath: String): Game {
        val parsed = RomNameParser.parse(item.name)
        val code = detect(item.name, item.name)   // 先只看文件名
        val pathCode = detect(item.name, fullPathOf(item, rootPath))
        val finalCode = if (pathCode != "misc") pathCode else code

        return Game(
            platformCode = finalCode,
            pickcode = item.pickcode,
            pathDisplay = fullPathOf(item, rootPath),
            romFilename = item.name,
            romSize = item.size,
            sha1 = item.sha1,
            titleRaw = parsed.rawTitle,
            title = parsed.title,
            titleEn = "",
            titleZh = "",
            releaseDate = parsed.year,
            description = "",
            scrapeStatus = "pending",
        )
    }

    private fun fullPathOf(item: Pan115Item, rootPath: String): String =
        if (rootPath.isBlank()) item.name else "$rootPath/${item.name}"
}
