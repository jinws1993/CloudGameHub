package com.nasgame.data.prefs

import android.content.Context
import android.os.Environment
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
    private val ROM_STORAGE_PATH = stringPreferencesKey("rom_storage_path")
    private val GLOBAL_EMULATOR_PKG = stringPreferencesKey("global_emulator_pkg")
    private val DEFAULT_RETROARCH_CORE = stringPreferencesKey("default_retroarch_core")

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

    /**
     * ROM 存放根路径.
     * 默认: <app-external>/roms (卸载 App 自动清理)
     * 用户可改成: 外置 SD 卡 / 自定义目录
     */
    suspend fun romStoragePath(): String {
        return ctx.dataStore.data.first()[ROM_STORAGE_PATH]
            ?: defaultRomStoragePath()
    }

    suspend fun setRomStoragePath(path: String) {
        ctx.dataStore.edit { it[ROM_STORAGE_PATH] = path }
    }

    /** 默认 ROM 存储路径 (App 私有 external) */
    fun defaultRomStoragePath(): String {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return ext.absolutePath + "/roms"
    }

    /** 列出可选的 ROM 存储路径候选 */
    fun romStorageCandidates(): List<Pair<String, String>> {
        val candidates = mutableListOf<Pair<String, String>>()
        // 默认 (App 私有 external — 卸载自动清理)
        candidates += "默认 (App 私有)" to defaultRomStoragePath()
        // 主存储公共目录 (需 WRITE_EXTERNAL_STORAGE, 但 Android 11+ 用 SAF 才行)
        try {
            val pub = Environment.getExternalStorageDirectory().absolutePath + "/NasGameRoms"
            candidates += "公共存储" to pub
        } catch (_: Exception) {}
        return candidates
    }

    /**
     * 全局模拟器包名 — 优先级最高的 fallback (例如设置 RetroArch 包名后, 所有平台优先用它)
     */
    suspend fun globalEmulatorPkg(): String? =
        ctx.dataStore.data.first()[GLOBAL_EMULATOR_PKG]

    suspend fun setGlobalEmulatorPkg(pkg: String?) {
        ctx.dataStore.edit {
            if (pkg.isNullOrBlank()) it.remove(GLOBAL_EMULATOR_PKG)
            else it[GLOBAL_EMULATOR_PKG] = pkg
        }
    }

    /**
     * RetroArch 默认 core (例如 "nestopia_libretro.so" for FC)
     * 不设置则 RA 会让用户首次启动时选 core
     */
    suspend fun defaultRetroArchCore(): String? =
        ctx.dataStore.data.first()[DEFAULT_RETROARCH_CORE]

    suspend fun setDefaultRetroArchCore(core: String?) {
        ctx.dataStore.edit {
            if (core.isNullOrBlank()) it.remove(DEFAULT_RETROARCH_CORE)
            else it[DEFAULT_RETROARCH_CORE] = core
        }
    }
}