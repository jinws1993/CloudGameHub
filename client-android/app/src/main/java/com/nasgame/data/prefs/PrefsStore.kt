package com.nasgame.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "nasgame")

@Singleton
class PrefsStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val SERVER = stringPreferencesKey("server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val USER = stringPreferencesKey("username")
    private val EMU_PREFIX = "emu_pkg_"
    private val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")

    val serverUrl: Flow<String?> = ctx.dataStore.data.map { it[SERVER] }
    val token: Flow<String?> = ctx.dataStore.data.map { it[TOKEN] }
    val username: Flow<String?> = ctx.dataStore.data.map { it[USER] }

    suspend fun currentServer(): String? = ctx.dataStore.data.first()[SERVER]
    suspend fun currentToken(): String? = ctx.dataStore.data.first()[TOKEN]

    suspend fun saveLogin(server: String, token: String, username: String) {
        ctx.dataStore.edit {
            it[SERVER] = server
            it[TOKEN] = token
            it[USER] = username
        }
    }

    suspend fun clearLogin() {
        ctx.dataStore.edit {
            it.remove(SERVER); it.remove(TOKEN); it.remove(USER)
        }
    }

    suspend fun setEmulatorPkg(platform: String, pkg: String) {
        ctx.dataStore.edit { it[stringPreferencesKey(EMU_PREFIX + platform)] = pkg }
    }

    suspend fun getEmulatorPkg(platform: String): String? =
        ctx.dataStore.data.first()[stringPreferencesKey(EMU_PREFIX + platform)]

    suspend fun allEmuPackages(): Map<String, String> {
        val prefs = ctx.dataStore.data.first().asMap()
        return prefs.entries
            .filter { it.key.name.startsWith(EMU_PREFIX) }
            .associate { it.key.name.removePrefix(EMU_PREFIX) to (it.value as String) }
    }

    /** 最大并发下载数 (默认 2, ROM 文件大, NAS/手机带宽压力) */
    suspend fun concurrentDownloads(): Int =
        ctx.dataStore.data.first()[CONCURRENT_DOWNLOADS] ?: 2

    suspend fun setConcurrentDownloads(n: Int) {
        ctx.dataStore.edit { it[CONCURRENT_DOWNLOADS] = n }
    }
}