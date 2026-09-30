package com.nasgame.data.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface NasGameApi {

    @POST("/api/auth/login")
    suspend fun login(@Body req: LoginRequest): LoginResponse

    @GET("/api/auth/me")
    suspend fun me(): MeResponse

    @GET("/api/platforms")
    suspend fun platforms(): List<Platform>

    @GET("/api/games")
    suspend fun games(
        @Query("platform") platform: String? = null,
        @Query("search") search: String? = null,
        @Query("status") status: String? = null,
        @Query("favorite") favorite: Boolean? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 60,
        @Query("sort") sort: String = "title",
    ): GamesResponse

    @GET("/api/games/{id}")
    suspend fun game(@Path("id") id: Long): Game

    @POST("/api/games/{id}/favorite")
    suspend fun toggleFav(@Path("id") id: Long): Map<String, Boolean>

    @POST("/api/games/{id}/play/local")
    suspend fun playLocal(
        @Path("id") id: Long,
        @Query("client") client: String = "android",
        @Query("emulator_override") emulatorOverride: String = "",
    ): PlayLocalResponse

    @POST("/api/games/{id}/play/stream")
    suspend fun playStream(@Path("id") id: Long): PlayStreamResponse

    /** ROM 下载 (本地时服务端流式返回 binary) */
    @GET("/api/games/{id}/rom")
    @Streaming
    suspend fun downloadRom(@Path("id") id: Long): ResponseBody

    /**
     * ROM 元信息: 服务端返回 source + url + size + filename
     * - source="local" → 客户端走 downloadRom (binary stream)
     * - source="remote_115" → 客户端走裸 OkHttp 下载 url (115 CDN, 不带 Authorization)
     */
    @GET("/api/games/{id}/rom-info")
    suspend fun romInfo(@Path("id") id: Long): RomInfoResponse

    @GET("/api/stats")
    suspend fun stats(): StatsResponse

    @Multipart
    @POST("/api/auth/change-password")
    suspend fun changePassword(
        @Part("old") old: okhttp3.RequestBody,
        @Part("new") new: okhttp3.RequestBody,
    ): Map<String, Boolean>

    /** 检查 115 Cookie 是否还有效 (启动后调一下) */
    @GET("/api/cloud/115/status")
    suspend fun cloud115Status(): Cloud115Status
}