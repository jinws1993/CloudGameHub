package com.nasgame.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "nasgame")

/**
 * 全部设置都在手机本地。**不再有服务器地址、没有登录 token、没有 JWT。**
 *
 * 唯一的"凭证"是 115 的 cookie —— 它只存在这台手机上, 不上传任何地方。
 */
@Singleton
class PrefsStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val K115_COOKIE = stringPreferencesKey("cookie_115")
    private val K115_ROOT_CID = stringPreferencesKey("root_cid_115")
    private val K115_ROOT_PATH = stringPreferencesKey("root_path_115")
    private val K115_SCAN_DEPTH = intPreferencesKey("scan_depth_115")
    private val K115_SCAN_ALL = booleanPreferencesKey("scan_all_ext")

    private val K_AI_ENABLED = booleanPreferencesKey("ai_enabled")
    private val K_AI_BASE = stringPreferencesKey("ai_base_url")
    private val K_AI_KEY = stringPreferencesKey("ai_api_key")
    private val K_AI_MODEL = stringPreferencesKey("ai_model")

    private val K_ROM_KIND = stringPreferencesKey("rom_storage_kind")
    private val K_ROM_SAF = stringPreferencesKey("rom_storage_saf_uri")
    private val K_ROM_LEGACY = stringPreferencesKey("rom_storage_legacy_path")
    private val K_CONCURRENT = intPreferencesKey("concurrent_downloads")

    private val K_RA_PKG = stringPreferencesKey("ra_package")
    private val K_RA_AUTO_CORE = booleanPreferencesKey("ra_auto_core")
    private val K_RA_CORE_OV_PREFIX = "ra_core_ov_"

    private val K_EMU_PREFIX = "emu_pkg_"
    private val K_GLOBAL_EMU = stringPreferencesKey("global_emulator_pkg")
    private val K_SCAN_AUTO_SCRAPE = booleanPreferencesKey("scan_auto_scrape")

    val flow115Cookie: Flow<Boolean> = ctx.dataStore.data.map { !it[K115_COOKIE].isNullOrBlank() }

    // ==================== 115 ====================

    suspend fun cached115Cookie(): String? = ctx.dataStore.data.first()[K115_COOKIE]

    suspend fun save115Cookie(cookie: String) {
        ctx.dataStore.edit { it[K115_COOKIE] = cookie }
    }

    suspend fun clear115Cookie() {
        ctx.dataStore.edit { it.remove(K115_COOKIE) }
    }

    /** 用户选定的 ROM 根目录 (115 里的 cid + 显示路径) */
    suspend fun romRootCid(): String? = ctx.dataStore.data.first()[K115_ROOT_CID]

    suspend fun romRootPath(): String? = ctx.dataStore.data.first()[K115_ROOT_PATH]

    suspend fun setRomRoot(cid: String, path: String) {
        ctx.dataStore.edit {
            it[K115_ROOT_CID] = cid
            it[K115_ROOT_PATH] = path
        }
    }

    suspend fun scanDepth(): Int = ctx.dataStore.data.first()[K115_SCAN_DEPTH] ?: 5

    suspend fun setScanDepth(n: Int) {
        ctx.dataStore.edit { it[K115_SCAN_DEPTH] = n.coerceIn(1, 10) }
    }

    /** 扫描时是否把所有文件都收进来 (不只按扩展名过滤) */
    suspend fun scanAllExtensions(): Boolean = ctx.dataStore.data.first()[K115_SCAN_ALL] ?: false

    suspend fun setScanAllExtensions(on: Boolean) {
        ctx.dataStore.edit { it[K115_SCAN_ALL] = on }
    }

    // ==================== AI 刮削 ====================

    suspend fun aiEnabled(): Boolean = ctx.dataStore.data.first()[K_AI_ENABLED] ?: false

    suspend fun setAiEnabled(on: Boolean) {
        ctx.dataStore.edit { it[K_AI_ENABLED] = on }
    }

    suspend fun aiBaseUrl(): String = ctx.dataStore.data.first()[K_AI_BASE]
        ?: "https://api.openai.com/v1"

    suspend fun setAiBaseUrl(url: String) {
        ctx.dataStore.edit { it[K_AI_BASE] = url.trim().trimEnd('/') }
    }

    suspend fun aiApiKey(): String = ctx.dataStore.data.first()[K_AI_KEY] ?: ""

    suspend fun setAiApiKey(key: String) {
        ctx.dataStore.edit { it[K_AI_KEY] = key }
    }

    suspend fun aiModel(): String = ctx.dataStore.data.first()[K_AI_MODEL] ?: "gpt-4o-mini"

    suspend fun setAiModel(model: String) {
        ctx.dataStore.edit { it[K_AI_MODEL] = model.trim() }
    }

    /** API key 的显示值 (打码) */
    suspend fun aiApiKeyMasked(): String {
        val k = aiApiKey()
        return if (k.isBlank()) "" else "•".repeat(minOf(8, k.length)) + k.takeLast(4)
    }

    // ==================== ROM 存放位置 ====================

    enum class RomStorageKind { DEFAULT, SAF, LEGACY_PATH }

    suspend fun romStorageKind(): RomStorageKind {
        val v = ctx.dataStore.data.first()[K_ROM_KIND] ?: RomStorageKind.SAF.name
        return runCatching { RomStorageKind.valueOf(v) }.getOrDefault(RomStorageKind.SAF)
    }

    suspend fun setSafStorage(uri: Uri) {
        ctx.dataStore.edit {
            it[K_ROM_KIND] = RomStorageKind.SAF.name
            it[K_ROM_SAF] = uri.toString()
        }
    }

    suspend fun setLegacyPath(path: String) {
        ctx.dataStore.edit {
            it[K_ROM_KIND] = RomStorageKind.LEGACY_PATH.name
            it[K_ROM_LEGACY] = path
        }
    }

    suspend fun resetToDefault() {
        ctx.dataStore.edit {
            it[K_ROM_KIND] = RomStorageKind.DEFAULT.name
            it.remove(K_ROM_SAF); it.remove(K_ROM_LEGACY)
        }
    }

    suspend fun romStorageSafUri(): String? = ctx.dataStore.data.first()[K_ROM_SAF]

    suspend fun romStorageLegacyPath(): String? = ctx.dataStore.data.first()[K_ROM_LEGACY]

    /** 设置页展示用 */
    suspend fun romStorageDisplay(): String = when (romStorageKind()) {
        RomStorageKind.DEFAULT -> "App 私有目录 ($defaultRomStoragePath())\n注意: RetroArch 读不到, 需要换个公共目录"
        RomStorageKind.SAF -> romStorageSafUri()?.let { humanizeSafUri(it) } ?: "未选择"
        RomStorageKind.LEGACY_PATH -> romStorageLegacyPath() ?: "未设置"
    }

    fun defaultRomStoragePath(): String {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return ext.absolutePath + "/roms"
    }

    private fun humanizeSafUri(uriStr: String): String {
        return try {
            val uri = Uri.parse(uriStr)
            val path = uri.path?.removePrefix("/tree/")?.replace(":", "/") ?: uriStr
            val isPrimary = path.contains("primary:")
            val parts = path.split("/").drop(1)
            val root = if (isPrimary) "主存储" else (parts.firstOrNull()?.uppercase() ?: "外部存储")
            val sub = parts.drop(1).joinToString("/")
            val pretty = if (sub.isBlank()) root else "$root/$sub"
            val real = com.nasgame.util.SafFileHelper.treeUriToPath(uri)
            if (real != null) "$pretty\n$real" else pretty
        } catch (_: Exception) {
            uriStr
        }
    }

    // ==================== 下载 ====================

    suspend fun concurrentDownloads(): Int = ctx.dataStore.data.first()[K_CONCURRENT] ?: 1

    suspend fun setConcurrentDownloads(n: Int) {
        ctx.dataStore.edit { it[K_CONCURRENT] = n.coerceIn(1, 4) }
    }

    suspend fun lastDownloadId(): Long = ctx.dataStore.data.first()[longPreferencesKey("last_dl_id")] ?: 0

    // ==================== 模拟器 ====================

    suspend fun raPackage(): String? = ctx.dataStore.data.first()[K_RA_PKG]

    suspend fun setRaPackage(pkg: String?) {
        ctx.dataStore.edit { if (pkg.isNullOrBlank()) it.remove(K_RA_PKG) else it[K_RA_PKG] = pkg }
    }

    suspend fun raAutoInstallCore(): Boolean = ctx.dataStore.data.first()[K_RA_AUTO_CORE] ?: true

    suspend fun setRaAutoInstallCore(on: Boolean) {
        ctx.dataStore.edit { it[K_RA_AUTO_CORE] = on }
    }

    suspend fun raCoreOverride(platform: String): String? =
        ctx.dataStore.data.first()[stringPreferencesKey(K_RA_CORE_OV_PREFIX + platform.uppercase())]

    suspend fun setRaCoreOverride(platform: String, core: String?) {
        val key = stringPreferencesKey(K_RA_CORE_OV_PREFIX + platform.uppercase())
        ctx.dataStore.edit { if (core.isNullOrBlank()) it.remove(key) else it[key] = core }
    }

    suspend fun allRaCoreOverrides(): Map<String, String> =
        ctx.dataStore.data.first().asMap()
            .filter { it.key.name.startsWith(K_RA_CORE_OV_PREFIX) }
            .mapNotNull { e -> (e.value as? String)?.let { e.key.name.removePrefix(K_RA_CORE_OV_PREFIX) to it } }
            .filter { it.second.isNotBlank() }
            .toMap()

    suspend fun setEmulatorPkg(platform: String, pkg: String?) {
        val key = stringPreferencesKey(K_EMU_PREFIX + platform.uppercase())
        ctx.dataStore.edit { if (pkg.isNullOrBlank()) it.remove(key) else it[key] = pkg }
    }

    suspend fun getEmulatorPkg(platform: String): String? =
        ctx.dataStore.data.first()[stringPreferencesKey(K_EMU_PREFIX + platform.uppercase())]

    suspend fun globalEmulatorPkg(): String? = ctx.dataStore.data.first()[K_GLOBAL_EMU]

    suspend fun setGlobalEmulatorPkg(pkg: String?) {
        ctx.dataStore.edit { if (pkg.isNullOrBlank()) it.remove(K_GLOBAL_EMU) else it[K_GLOBAL_EMU] = pkg }
    }

    // ==================== 扫描 ====================

    suspend fun autoScrapeAfterScan(): Boolean = ctx.dataStore.data.first()[K_SCAN_AUTO_SCRAPE] ?: true

    suspend fun setAutoScrapeAfterScan(on: Boolean) {
        ctx.dataStore.edit { it[K_SCAN_AUTO_SCRAPE] = on }
    }

    /** 一次性清空全部设置 (换账号 / 重置用) */
    suspend fun resetAll() {
        ctx.dataStore.edit { it.clear() }
    }
}
