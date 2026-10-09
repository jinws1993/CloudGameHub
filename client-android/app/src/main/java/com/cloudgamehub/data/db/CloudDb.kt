package com.cloudgamehub.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cloudgamehub.data.model.Game
import com.cloudgamehub.data.model.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地库。**不再有任何服务器**, 游戏数据全部落在手机的 SQLite 里。
 *
 * 用裸 [SQLiteOpenHelper] 而不是 Room: 这个 App 只有一个 db、几张表,
 * 引入 Room 就得多一个 KSP processor 和一堆注解, 收益不抵编译风险。
 *
 * 一条重要约定: **ROM 本体永远不进库**, 只存 115 的 pickcode。
 * 库里 [Game.localPath] 为空就代表"这个游戏还没下到手机"。
 */
@Singleton
class CloudDb @Inject constructor(@ApplicationContext ctx: Context) :
    SQLiteOpenHelper(ctx, "cloudgamehub.db", null, VERSION) {

    companion object {
        const val VERSION = 2
        const val DB_NAME = "cloudgamehub.db"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS games (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                platform_code  TEXT    NOT NULL,
                pickcode       TEXT    NOT NULL DEFAULT '',
                path_display   TEXT    NOT NULL DEFAULT '',
                rom_filename   TEXT    NOT NULL DEFAULT '',
                rom_size       INTEGER NOT NULL DEFAULT 0,
                sha1           TEXT    NOT NULL DEFAULT '',
                title_raw      TEXT    NOT NULL DEFAULT '',
                title          TEXT    NOT NULL DEFAULT '',
                title_en       TEXT    NOT NULL DEFAULT '',
                title_zh       TEXT    NOT NULL DEFAULT '',
                release_date   TEXT    NOT NULL DEFAULT '',
                developer      TEXT    NOT NULL DEFAULT '',
                publisher      TEXT    NOT NULL DEFAULT '',
                genre          TEXT    NOT NULL DEFAULT '',
                players        TEXT    NOT NULL DEFAULT '',
                rating         REAL    NOT NULL DEFAULT 0,
                description    TEXT    NOT NULL DEFAULT '',
                cover_file     TEXT    NOT NULL DEFAULT '',
                scrape_status  TEXT    NOT NULL DEFAULT 'pending',
                scrape_source  TEXT    NOT NULL DEFAULT '',
                favorite       INTEGER NOT NULL DEFAULT 0,
                play_count     INTEGER NOT NULL DEFAULT 0,
                last_played    INTEGER NOT NULL DEFAULT 0,
                local_path     TEXT    NOT NULL DEFAULT '',
                local_size     INTEGER NOT NULL DEFAULT 0,
                created_at     INTEGER NOT NULL DEFAULT 0,
                updated_at     INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_platform ON games(platform_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_status ON games(scrape_status)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_games_pickcode ON games(pickcode)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 早期版本 (v1) 的表结构跟现在不同, 这里直接重建 —— 库可以一键重扫, 丢了不心疼
        if (old < 2) {
            db.execSQL("DROP TABLE IF EXISTS games")
            onCreate(db)
        }
    }

    // ================= 读 =================

    fun platforms(): List<Platform> = Platform.ALL

    fun countGames(platformCode: String? = null): Int = readableDatabase.rawQuery(
        if (platformCode == null) "SELECT COUNT(*) FROM games" else
            "SELECT COUNT(*) FROM games WHERE platform_code = ?", arrayOf(platformCode)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countByPlatform(): Map<String, Int> = readableDatabase.rawQuery(
        "SELECT platform_code, COUNT(*) FROM games GROUP BY platform_code", null
    ).use { c ->
        val out = HashMap<String, Int>()
        while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        out
    }

    fun countPendingScrape(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM games WHERE scrape_status != 'done'", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * 翻页查库。排序: 收藏优先 → 最近玩过 → 标题。
     * 用 LIMIT/OFFSET 而不是全量加载 —— ROM 库轻松上万条。
     */
    fun queryGames(
        platformCode: String? = null,
        search: String? = null,
        favoriteOnly: Boolean = false,
        sort: String = "title",
        page: Int = 1,
        pageSize: Int = 60,
    ): List<Game> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        platformCode?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND platform_code = ?"); args += it
        }
        search?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND (title_zh LIKE ? OR title_en LIKE ? OR title LIKE ? " +
                "OR title_raw LIKE ? OR rom_filename LIKE ?)")
            val like = "%$it%"
            repeat(5) { args += like }
        }
        if (favoriteOnly) where.append(" AND favorite = 1")

        val order = when (sort) {
            "recent" -> "last_played DESC, id DESC"
            "added" -> "id DESC"
            "platform" -> "platform_code ASC, title ASC"
            else -> "title_zh <> '' DESC, title_en <> '' DESC, title ASC, id ASC"
        }
        val sql = "SELECT * FROM games WHERE $where ORDER BY $order LIMIT ? OFFSET ?"
        args += pageSize.toString()
        args += ((page - 1).coerceAtLeast(0) * pageSize).toString()
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { it.readGames() }
    }

    fun gameById(id: Long): Game? =
        readableDatabase.rawQuery("SELECT * FROM games WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.readGame() else null }

    fun gamesByIds(ids: List<Long>): List<Game> {
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        return readableDatabase.rawQuery("SELECT * FROM games WHERE id IN ($ph)", ids.map { it.toString() }.toTypedArray())
            .use { it.readGames() }
    }

    /** 刮削用: 取一批还没刮完的 */
    fun pendingGames(limit: Int = 20, platformCode: String? = null): List<Game> {
        val where = StringBuilder("scrape_status != 'done'")
        val args = ArrayList<String>()
        platformCode?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND platform_code = ?"); args += it
        }
        args += limit.toString()
        return readableDatabase.rawQuery(
            "SELECT * FROM games WHERE $where ORDER BY id LIMIT ?", args.toTypedArray()
        ).use { it.readGames() }
    }

    /** 全部 pickcode, 用来做扫描去重 */
    fun allPickcodes(): Set<String> = readableDatabase.rawQuery(
        "SELECT pickcode FROM games WHERE pickcode != ''", null
    ).use { c ->
        val out = HashSet<String>()
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    fun totalRomBytes(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(rom_size), 0) FROM games", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun totalLocalBytes(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(local_size), 0) FROM games WHERE local_size > 0", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun localCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM games WHERE local_size > 0", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ================= 写 =================

    fun upsertGames(games: List<Game>): Int = writableDatabase.run {
        beginTransaction()
        try {
            val n = games.count { insertOrIgnore(it) >= 0 }
            setTransactionSuccessful()
            n
        } finally { endTransaction() }
    }

    /** 只在 pickcode 不存在时插入 (已存在则跳过), 返回受影响行数 */
    fun insertOrIgnore(game: Game): Long {
        val db = writableDatabase
        // 先查一次, 免得 unique 冲突抛异常
        db.rawQuery("SELECT id FROM games WHERE pickcode = ? AND pickcode != ''",
            arrayOf(game.pickcode)).use { if (it.moveToFirst()) return 0L }
        val id = db.insert("games", null, game.toValues(includeId = false))
        if (game.platformCode.isNotBlank() && Platform.of(game.platformCode) == null) {
            // 未知平台 (自定义扩展名归类出来的) 保留代码, UI 显示代码本身
        }
        return id
    }

    fun updateGame(game: Game) {
        writableDatabase.update("games", game.toValues(includeId = true),
            "id = ?", arrayOf(game.id.toString()))
    }

    /** 刮削完只更新元数据, 不碰 local_path */
    fun updateMetadata(id: Long, meta: Game) {
        val v = ContentValues().apply {
            put("title", meta.title)
            put("title_en", meta.titleEn)
            put("title_zh", meta.titleZh)
            put("release_date", meta.releaseDate)
            put("developer", meta.developer)
            put("publisher", meta.publisher)
            put("genre", meta.genre)
            put("players", meta.players)
            put("rating", meta.rating)
            put("description", meta.description)
            put("cover_file", meta.coverFile)
            put("scrape_status", meta.scrapeStatus)
            put("scrape_source", meta.scrapeSource)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("games", v, "id = ?", arrayOf(id.toString()))
    }

    fun setLocalPath(id: Long, path: String, size: Long) {
        val v = ContentValues().apply {
            put("local_path", path)
            put("local_size", size)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("games", v, "id = ?", arrayOf(id.toString()))
    }

    fun clearLocalPath(id: Long) {
        val v = ContentValues().apply {
            put("local_path", "")
            put("local_size", 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("games", v, "id = ?", arrayOf(id.toString()))
    }

    /** 用户手动删了文件后, 把库里标记清掉 (不能让库说有本地其实没有) */
    fun reconcileLocalFiles(): Int {
        var cleared = 0
        val rows = readableDatabase.rawQuery(
            "SELECT id, local_path FROM games WHERE local_size > 0", null
        ).use { c ->
            val out = ArrayList<Pair<Long, String>>()
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
            out
        }
        rows.forEach { (id, path) ->
            if (!java.io.File(path).exists()) { clearLocalPath(id); cleared++ }
        }
        return cleared
    }

    fun toggleFavorite(id: Long): Boolean {
        val cur = readableDatabase.rawQuery(
            "SELECT favorite FROM games WHERE id = ?", arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) == 1 else false }
        val v = ContentValues().apply { put("favorite", if (cur) 0 else 1) }
        writableDatabase.update("games", v, "id = ?", arrayOf(id.toString()))
        return !cur
    }

    fun markPlayed(id: Long) {
        val v = ContentValues().apply {
            put("play_count", "play_count + 1")
            put("last_played", System.currentTimeMillis())
        }
        // play_count 是表达式, ContentValues 不支持, 走 SQL
        writableDatabase.execSQL(
            "UPDATE games SET play_count = play_count + 1, last_played = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun deleteAllGames() {
        writableDatabase.delete("games", null, null)
    }

    // ================= 映射 =================

    private fun Game.toValues(includeId: Boolean): ContentValues = ContentValues().apply {
        if (includeId && id > 0) put("id", id)
        put("platform_code", platformCode)
        put("pickcode", pickcode)
        put("path_display", pathDisplay)
        put("rom_filename", romFilename)
        put("rom_size", romSize)
        put("sha1", sha1)
        put("title_raw", titleRaw)
        put("title", title)
        put("title_en", titleEn)
        put("title_zh", titleZh)
        put("release_date", releaseDate)
        put("developer", developer)
        put("publisher", publisher)
        put("genre", genre)
        put("players", players)
        put("rating", rating)
        put("description", description)
        put("cover_file", coverFile)
        put("scrape_status", scrapeStatus)
        put("scrape_source", scrapeSource)
        put("favorite", if (favorite) 1 else 0)
        put("play_count", playCount)
        put("last_played", lastPlayed)
        put("local_path", localPath)
        put("local_size", localSize)
        put("created_at", System.currentTimeMillis())
        put("updated_at", System.currentTimeMillis())
    }

    private fun Cursor.readGames(): List<Game> {
        val out = ArrayList<Game>(count)
        while (moveToNext()) out += readGame()
        return out
    }

    private fun Cursor.readGame(): Game = Game(
        id = getLong(getColumnIndexOrThrow("id")),
        platformCode = getString(getColumnIndexOrThrow("platform_code")) ?: "misc",
        pickcode = getString(getColumnIndexOrThrow("pickcode")) ?: "",
        pathDisplay = getString(getColumnIndexOrThrow("path_display")) ?: "",
        romFilename = getString(getColumnIndexOrThrow("rom_filename")) ?: "",
        romSize = getLong(getColumnIndexOrThrow("rom_size")),
        sha1 = getString(getColumnIndexOrThrow("sha1")) ?: "",
        titleRaw = getString(getColumnIndexOrThrow("title_raw")) ?: "",
        title = getString(getColumnIndexOrThrow("title")) ?: "",
        titleEn = getString(getColumnIndexOrThrow("title_en")) ?: "",
        titleZh = getString(getColumnIndexOrThrow("title_zh")) ?: "",
        releaseDate = getString(getColumnIndexOrThrow("release_date")) ?: "",
        developer = getString(getColumnIndexOrThrow("developer")) ?: "",
        publisher = getString(getColumnIndexOrThrow("publisher")) ?: "",
        genre = getString(getColumnIndexOrThrow("genre")) ?: "",
        players = getString(getColumnIndexOrThrow("players")) ?: "",
        rating = getFloat(getColumnIndexOrThrow("rating")),
        description = getString(getColumnIndexOrThrow("description")) ?: "",
        coverFile = getString(getColumnIndexOrThrow("cover_file")) ?: "",
        scrapeStatus = getString(getColumnIndexOrThrow("scrape_status")) ?: "pending",
        scrapeSource = getString(getColumnIndexOrThrow("scrape_source")) ?: "",
        favorite = getInt(getColumnIndexOrThrow("favorite")) == 1,
        playCount = getInt(getColumnIndexOrThrow("play_count")),
        lastPlayed = getLong(getColumnIndexOrThrow("last_played")),
        localPath = getString(getColumnIndexOrThrow("local_path")) ?: "",
        localSize = getLong(getColumnIndexOrThrow("local_size")),
    )
}
