package com.nasgame.data.model

/**
 * 平台目录。25 个平台的定义直接内置, 不依赖任何服务器。
 *
 * code 是内部标识, 跟 libretro 核心映射 / ROM 扩展名归类共用同一套。
 */
data class Platform(
    val code: String,
    val name: String,
    val nameEn: String,
    val folder: String,
    val extensions: List<String>,
    val sortOrder: Int,
    val enabled: Boolean = true,
) {
    val extSet: Set<String> get() = extensions.map { it.lowercase() }.toSet()

    companion object {
        val ALL: List<Platform> = listOf(
            Platform("FC", "红白机", "Nintendo Entertainment System", "fc",
                listOf("nes", "fds"), 10),
            Platform("SFC", "超级任天堂", "Super Nintendo", "sfc",
                listOf("smc", "fig", "sfc", "swc"), 20),
            Platform("N64", "任天堂64", "Nintendo 64", "n64",
                listOf("n64", "z64", "v64"), 30),
            Platform("GB", "Game Boy", "Game Boy", "gb",
                listOf("gb", "gmb"), 40),
            Platform("GBC", "Game Boy Color", "Game Boy Color", "gbc",
                listOf("gbc", "cgb"), 50),
            Platform("GBA", "GBA", "Game Boy Advance", "gba",
                listOf("gba", "agb"), 60),
            Platform("NDS", "NDS", "Nintendo DS", "nds",
                listOf("nds", "dsi"), 70),
            Platform("3DS", "3DS", "Nintendo 3DS", "3ds",
                listOf("3ds", "cia"), 80),
            Platform("MD", "Mega Drive", "Sega Mega Drive", "md",
                listOf("md", "gen", "smd", "bin"), 90),
            Platform("SATURN", "土星", "Sega Saturn", "saturn",
                listOf("cue", "mds", "img", "ccd"), 100),
            Platform("DC", "Dreamcast", "Sega Dreamcast", "dc",
                listOf("iso", "chd", "gdi", "cdi"), 110),
            Platform("PS1", "PlayStation", "PlayStation", "ps1",
                listOf("cue", "pbp", "chd", "img"), 120),
            Platform("PS2", "PlayStation 2", "PlayStation 2", "ps2",
                listOf("iso", "cso", "mdf", "nrg", "chd"), 130),
            Platform("PSP", "PSP", "PlayStation Portable", "psp",
                listOf("iso", "cso", "pbp"), 140),
            Platform("WII", "Wii", "Nintendo Wii", "wii",
                listOf("iso", "wad", "wbfs", "rvz", "nkit"), 150),
            Platform("GC", "GameCube", "Nintendo GameCube", "gc",
                listOf("iso", "gcm", "ciso", "rvz", "nkit"), 160),
            Platform("PCE", "PC Engine", "PC Engine", "pce",
                listOf("pce", "sgx", "ccd", "cue"), 170),
            Platform("NEOGEO", "Neo Geo", "Neo Geo", "neogeo",
                listOf("neo", "zip"), 180),
            Platform("MAME", "街机", "Arcade (MAME)", "mame",
                listOf("zip"), 190),
            Platform("ARCADE", "通用街机", "Arcade", "arcade",
                listOf("zip"), 200),
            Platform("J2ME", "Java手游", "J2ME Mobile", "j2me",
                listOf("jar"), 210),
            Platform("DOS", "DOS", "DOS", "dos",
                listOf("exe", "bat", "com", "iso", "img"), 220),
            Platform("WIN", "Windows", "Windows", "win",
                listOf("exe", "msi"), 230),
            Platform("FLASH", "Flash游戏", "Flash", "flash",
                listOf("swf"), 240),
            Platform("HTML", "网页游戏", "HTML5", "html",
                listOf("html", "htm"), 250),
        )

        private val byCode: Map<String, Platform> = ALL.associateBy { it.code }
        fun of(code: String?): Platform? = code?.let { byCode[it.uppercase()] }
    }
}

/**
 * 一个游戏。ROM 本体永远不存 —— 本体在 115 上, 我们只存 pickcode;
 * 下载到手机后把本地路径写回 [localPath]。
 */
data class Game(
    val id: Long = 0,
    val platformCode: String = "misc",
    /** 115 的 pickcode, 下载直链的凭据 */
    val pickcode: String = "",
    /** 115 里的完整路径, 给人看的 */
    val pathDisplay: String = "",
    val romFilename: String = "",
    val romSize: Long = 0,
    val sha1: String = "",

    val titleRaw: String = "",
    val title: String = "",
    val titleEn: String = "",
    val titleZh: String = "",

    val releaseDate: String = "",
    val developer: String = "",
    val publisher: String = "",
    val genre: String = "",
    val players: String = "",
    val rating: Float = 0f,
    val description: String = "",

    /** 封面文件名 (存在 app 私有 media 目录里, 不用绝对路径方便迁移) */
    val coverFile: String = "",

    /** pending / done / failed / manual */
    val scrapeStatus: String = "pending",
    val scrapeSource: String = "",

    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,

    /** 本地 ROM 绝对路径; 空 = 还没下到手机 */
    val localPath: String = "",
    val localSize: Long = 0,
) {
    val platform: Platform? get() = Platform.of(platformCode)

    /** 列表上显示的名字: 中文 > 英文 > 清洗后的 > 原始 */
    val displayTitle: String
        get() = titleZh.ifBlank { titleEn }.ifBlank { title }.ifBlank { titleRaw }
            .ifBlank { romFilename.substringBeforeLast('.') }

    val isLocal: Boolean get() = localPath.isNotBlank() && localSize > 0

    /** 本地文件还在不在 (用户可能手动删了) */
    fun localFile(): java.io.File? =
        if (localPath.isBlank()) null else java.io.File(localPath).takeIf { it.exists() }
}

/** 扫描任务的实时状态, 给 UI 显示进度 */
data class ScanState(
    val running: Boolean = false,
    val phase: String = "",          // 连接 115 / 扫描目录 / 刮削 / 完成
    val scanned: Int = 0,
    val total: Int = 0,
    val scraped: Int = 0,
    val current: String = "",
    val message: String = "",
    val error: String? = null,
    val done: Boolean = false,
) {
    val percent: Float get() = if (total > 0) scanned.toFloat() / total else 0f
}
