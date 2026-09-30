package com.nasgame.data.repo

import android.content.Context
import com.nasgame.data.api.*
import com.nasgame.data.prefs.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NasGameRepo @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private var api: NasGameApi? = null

    suspend fun init() {
        val token = prefs.currentToken()
        val server = prefs.currentServer()
        if (!token.isNullOrBlank() && !server.isNullOrBlank()) {
            buildApi(server, token)
            try {
                val me = api!!.me()
                _isLoggedIn.value = true
            } catch (e: Exception) {
                _isLoggedIn.value = false
            }
        }
    }

    private fun buildApi(server: String, token: String) {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { token })
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

        api = Retrofit.Builder()
            .baseUrl(server.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NasGameApi::class.java)
    }

    fun serverUrl(): String? = runBlockingGet { prefs.currentServer() }

    private fun <T> runBlockingGet(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    suspend fun login(server: String, username: String, password: String): LoginResponse {
        // build temp api without token for login
        val tmp = Retrofit.Builder()
            .baseUrl(server.trimEnd('/') + "/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NasGameApi::class.java)
        val r = tmp.login(LoginRequest(username, password))
        buildApi(server, r.accessToken)
        prefs.saveLogin(server, r.accessToken, r.username)
        _isLoggedIn.value = true
        return r
    }

    suspend fun logout() {
        prefs.clearLogin()
        _isLoggedIn.value = false
        api = null
    }

    private fun require(): NasGameApi =
        api ?: throw IOException("未连接服务器")

    suspend fun platforms(): List<Platform> = withContext(Dispatchers.IO) { require().platforms() }

    suspend fun games(
        platform: String? = null,
        search: String? = null,
        page: Int = 1,
        pageSize: Int = 60,
    ): GamesResponse = withContext(Dispatchers.IO) {
        require().games(platform = platform, search = search, page = page, pageSize = pageSize)
    }

    suspend fun game(id: Long): Game = withContext(Dispatchers.IO) { require().game(id) }

    suspend fun toggleFav(id: Long): Boolean = withContext(Dispatchers.IO) {
        require().toggleFav(id)["favorite"] ?: false
    }

    suspend fun playLocal(id: Long): PlayLocalResponse = withContext(Dispatchers.IO) {
        require().playLocal(id)
    }

    suspend fun playStream(id: Long): PlayStreamResponse = withContext(Dispatchers.IO) {
        require().playStream(id)
    }

    suspend fun downloadRom(
        id: Long,
        dest: File,
        progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val body = require().downloadRom(id)
        val total = body.contentLength()
        body.byteStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        dest
    }

    suspend fun stats(): StatsResponse = withContext(Dispatchers.IO) { require().stats() }

    fun romCacheDir(platformCode: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "roms/$platformCode")
        dir.mkdirs()
        return dir
    }
}
