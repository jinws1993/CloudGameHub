package com.nasgame.data.prefs

import android.content.Context
import android.net.Uri
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
    private val LAST_SERVER = stringPreferencesKey("last_server_url")
    private val TOKEN = stringPreferencesKey("token")
    private val USER = stringPreferencesKey("username")
    private val SAVED_PASSWORD = stringPreferencesKey("saved_password")
    private val REMEMBER_ME = booleanPreferencesKey("remember_me")
    private val EMU_PREFIX = "emu_pkg_"
    private val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
    /** ROM 存储路径 — 两种形式:
     *  - "default" → 默认 App 私有目录
     *  - "saf:<uri>" → 用户通过 SAF 选择的目录 (Uri encoded)
     *  - "path:/storage/emulated/0/MyRoms" → 旧的手填绝对路径 (legacy)
     */
    private val ROM_STORAGE_KIND = stringPreferencesKey("rom_storage_kind")
    private val ROM_STORAGE_SAF_URI = stringPreferencesKey("rom_storage_saf_uri")
    private val ROM_STORAGE_LEGACY_PATH = stringPreferencesKey("rom_storage_legacy_path")
    private val GLOBAL_EMULATOR_PKG = stringPreferencesKey("global_emulator_pkg")
    private val DEFAULT_RETROARCH_CORE = stringPreferencesKey("default_retroarch_core")

    val serverUrl: Flow<String?> = ctx.dataStore.data.map { it[SERVER] }
    val token: Flow<String?> = ctx.dataStore.data.map { it[TOKEN] }
    val username: Flow<String?> = ctx.dataStore.data.map { it[USER] }

    suspend fun currentServer(): String? = ctx.dataStore.data.first()[SERVER]
    suspend fun currentToken(): String? = ctx.dataStore.data.first()[TOKEN]

    /** 上次连接的服务器地址 (不论登录状态, 总是记住) */
    suspend fun lastServerUrl(): String? = ctx.dataStore.data.first()[LAST_SERVER]
    suspend fun rememberLastServer(url: String) {
        ctx.dataStore.edit { it[LAST_SERVER] = url }
    }

    /** 记住密码 (仅当勾选时才存储, 明文存在 DataStore) */
    suspend fun savedPassword(): String? = ctx.dataStore.data.first()[SAVED_PASSWORD]
    suspend fun isRememberMe(): Boolean = ctx.dataStore.data.first()[REMEMBER_ME] ?: false
    suspend fun setRememberMe(remember: Boolean, password: String?) {
        ctx.dataStore.edit {
            if (remember && !password.isNullOrBlank()) {
                it[SAVED_PASSWORD] = password
                it[REMEMBER_ME] = true
            } else {
                it.remove(SAVED_PASSWORD)
                it[REMEMBER_ME] = false
            }
        }
    }

    suspend fun saveLogin(server: String, token: String, username: String, remember: Boolean, password: String?) {
        ctx.dataStore.edit {
            it[SERVER] = server
            it[LAST_SERVER] = server
            it[TOKEN] = token
            it[USER] = username
            it[REMEMBER_ME] = remember
            if (remember && !password.isNullOrBlank()) it[SAVED_PASSWORD] = password
            else it.remove(SAVED_PASSWORD)
        }
    }

    suspend fun clearLogin() {
        ctx.dataStore.edit {
            it.remove(SERVER); it.remove(TOKEN); it.remove(USER)
            it.remove(REMEMBER_ME); it.remove(SAVED_PASSWORD)
            // LAST_SERVER 保留, 退出不抹掉
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

    /** 最大并发下载数 (默认 2) */
    suspend fun concurrentDownloads(): Int =
        ctx.dataStore.data.first()[CONCURRENT_DOWNLOADS] ?: 2

    suspend fun setConcurrentDownloads(n: Int) {
        ctx.dataStore.edit { it[CONCURRENT_DOWNLOADS] = n }
    }

    // ============ ROM 存储路径 (SAF 模式) ============

    enum class RomStorageKind { DEFAULT, SAF, LEGACY_PATH }

    /** 当前的 ROM 存储方式 */
    suspend fun romStorageKind(): RomStorageKind {
        val v = ctx.dataStore.data.first()[ROM_STORAGE_KIND] ?: RomStorageKind.DEFAULT.name
        return runCatching { RomStorageKind.valueOf(v) }.getOrDefault(RomStorageKind.DEFAULT)
    }

    /** 设置为 SAF (用户选目录) */
    suspend fun setSafStorage(uri: Uri) {
        ctx.dataStore.edit {
            it[ROM_STORAGE_KIND] = RomStorageKind.SAF.name
            it[ROM_STORAGE_SAF_URI] = uri.toString()
        }
    }

    /** 设置为 legacy path (兼容老配置) */
    suspend fun setLegacyPath(path: String) {
        ctx.dataStore.edit {
            it[ROM_STORAGE_KIND] = RomStorageKind.LEGACY_PATH.name
            it[ROM_STORAGE_LEGACY_PATH] = path
        }
    }

    /** 重置为默认 */
    suspend fun resetToDefault() {
        ctx.dataStore.edit {
            it[ROM_STORAGE_KIND] = RomStorageKind.DEFAULT.name
            it.remove(ROM_STORAGE_SAF_URI)
            it.remove(ROM_STORAGE_LEGACY_PATH)
        }
    }

    /** SAF 选中的目录 URI (字符串) */
    suspend fun romStorageSafUri(): String? =
        ctx.dataStore.data.first()[ROM_STORAGE_SAF_URI]

    /** Legacy 路径 */
    suspend fun romStorageLegacyPath(): String? =
        ctx.dataStore.data.first()[ROM_STORAGE_LEGACY_PATH]

    /**
     * 当前 ROM 存储的"显示描述" (用于 UI).
     * - DEFAULT → "<app-external>/roms (卸载自动清理)"
     * - SAF → 友好的树路径 (从 Uri 推断)
     * - LEGACY_PATH → 原始路径
     */
    suspend fun romStorageDisplay(): String = when (romStorageKind()) {
        RomStorageKind.DEFAULT -> "默认 (App 私有): ${defaultRomStoragePath()}"
        RomStorageKind.SAF -> romStorageSafUri()?.let { humanizeSafUri(it) }
            ?: "未知 SAF 目录"
        RomStorageKind.LEGACY_PATH -> romStorageLegacyPath() ?: "(未知)"
    }

    /** 默认 ROM 存储根路径 (App 私有 external — 卸载自动清理) */
    fun defaultRomStoragePath(): String {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return ext.absolutePath + "/roms"
    }

    /** 把 SAF Uri 转成人能看懂的描述 (e.g. "主存储/Download/MyRoms") */
    private fun humanizeSafUri(uriStr: String): String {
        // tree/primary:Documents/MyRoms → "主存储/Documents/MyRoms"
        // tree/XXXX-XXXX:Downloads/sub → "SD卡/Downloads/sub"
        return try {
            val uri = Uri.parse(uriStr)
            val path = uri.path?.removePrefix("/tree/")?.replace(":", "/") ?: uri.toString()
            // 找 primary 标识
            val parts = path.split("/").drop(1) // drop "tree"
            val isPrimary = path.contains("primary:")
            val root = if (isPrimary) "主存储" else parts.firstOrNull()?.uppercase() ?: "外部存储"
            val sub = parts.drop(1).joinToString("/")
            if (sub.isBlank()) root else "$root/$sub"
        } catch (_: Exception) {
            uriStr
        }
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

    /** RetroArch 默认 core (例如 "nestopia_libretro.so" for FC) */
    suspend fun defaultRetroArchCore(): String? =
        ctx.dataStore.data.first()[DEFAULT_RETROARCH_CORE]

    suspend fun setDefaultRetroArchCore(core: String?) {
        ctx.dataStore.edit {
            if (core.isNullOrBlank()) it.remove(DEFAULT_RETROARCH_CORE)
            else it[DEFAULT_RETROARCH_CORE] = core
        }
    }
}