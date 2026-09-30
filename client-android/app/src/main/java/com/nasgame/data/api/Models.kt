package com.nasgame.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val username: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class Platform(
    val id: Int,
    val code: String,
    val name: String,
    @SerialName("name_en") val nameEn: String = "",
    val folder: String,
    val extensions: String = "",
    @SerialName("game_count") val gameCount: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
data class PlatformMini(
    val id: Int,
    val code: String,
    val name: String,
)

@Serializable
data class Game(
    val id: Int,
    val platform: PlatformMini? = null,
    @SerialName("rom_filename") val romFilename: String,
    @SerialName("rom_size") val romSize: Long = 0,
    val title: String = "",
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("title_zh") val titleZh: String = "",
    @SerialName("title_raw") val titleRaw: String = "",
    val description: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val developer: String = "",
    val publisher: String = "",
    val genre: String = "",
    val rating: Float = 0f,
    val cover: String = "",
    val logo: String = "",
    val video: String = "",
    val screenshots: List<String> = emptyList(),
    @SerialName("scrape_status") val scrapeStatus: String = "pending",
    @SerialName("scrape_source") val scrapeSource: String = "",
    @SerialName("cloud_source") val cloudSource: String = "",
    @SerialName("local_available") val localAvailable: Boolean = false,
    @SerialName("last_played") val lastPlayed: String? = null,
    val favorite: Boolean = false,
    @SerialName("play_count") val playCount: Int = 0,
)

@Serializable
data class GamesResponse(
    val total: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int = 60,
    val items: List<Game>,
)

@Serializable
data class PlayLocalResponse(
    @SerialName("session_id") val sessionId: Int,
    @SerialName("download_url") val downloadUrl: String,
    val command: List<String> = emptyList(),
    val intent: IntentSpec? = null,
    @SerialName("package") val pkg: String? = null,
)

@Serializable
data class IntentSpec(
    val action: String,
    val data: String,
    @SerialName("package") val pkg: String,
)

/**
 * ROM 下载信息: 服务端 /api/games/{id}/rom 返回
 * - source="local" → 客户端走 downloadRom (binary stream)
 * - source="remote_115" → 客户端走裸 OkHttp 下载 url (115 CDN, 不带 Authorization)
 */
@Serializable
data class RomInfoResponse(
    val source: String,           // "local" | "remote_115"
    val url: String = "",         // 115 CDN URL (local 时为空)
    val filename: String = "",
    val size: Long = 0,
    @SerialName("pickcode") val pickcode: String = "",
)

@Serializable
data class PlayStreamResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("ws_control") val wsControl: String,
    @SerialName("ws_video") val wsVideo: String,
)

@Serializable
data class StatsResponse(
    @SerialName("total_games") val totalGames: Int = 0,
    @SerialName("total_platforms") val totalPlatforms: Int = 0,
    @SerialName("by_status") val byStatus: Map<String, Int> = emptyMap(),
    @SerialName("by_platform") val byPlatform: List<PlatStat> = emptyList(),
)

@Serializable
data class PlatStat(val platform: String, val count: Int)