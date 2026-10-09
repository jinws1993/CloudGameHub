package com.cloudgamehub.emulator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.cloudgamehub.data.prefs.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RetroArch 集成层。
 *
 * 解决的问题: 用户装完 CloudGameHub 之后手机上**啥也跑不起来**, 因为
 * 1. 没装 RetroArch, 或者
 * 2. 装了 RetroArch 但没下核心 (core) —— RA 只是个前端, 没有 core 就是空壳
 * 3. 知道该用哪个 core, 但 core 装在 RA 的私有目录里, 我们没权限写
 *
 * 这里的做法:
 * - **自动探测** RA / RA2 的所有包名变体, 不用用户手填
 * - **平台 → core 映射**: 内置 25 个平台的推荐 core 列表 (按优先级)
 * - **自动装核心**: 从 NAS 镜像或 libretro 官方 buildbot 拉 `*_libretro_android.so`,
 *   解压后缓存在 app 目录, 再通过 `LIBRETRO` extra 传给 RA
 *   (RetroArch 2025+ 的 core sideloading 会把它 copy 进自己的 cores 目录)
 * - **架构自适应**: 按 `Build.SUPPORTED_ABIS` 选 arm64-v8a / armeabi-v7a / x86_64 / x86
 *
 * ## 关于 core 文件名
 *
 * buildbot 上的文件名带 ABI 变体: `fceumm_libretro_android.so.zip`。
 * 我们内部用 **base name** (`fceumm`) 索引, 拼 URL 时再补后缀, 免得到处写死。
 * ⚠️ 名单是照着 buildbot 实际目录核过的, 别凭印象加 core。
 */
@Singleton
class RetroArchManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    private val TAG = "RetroArchManager"

    /** 核心下载用裸 client: 不带 NAS Authorization, 免得把 token 发给 buildbot */
    private val plain: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ================= RA 探测 =================

    data class RaInstall(
        val pkg: String,
        val versionName: String,
        val is64Bit: Boolean,
        /** 能不能解析到 RetroActivityFuture (决定能否一条 intent 直接进游戏) */
        val hasPlayActivity: Boolean,
    )

    fun isRetroArch(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && RA_PACKAGES.any { it.equals(pkg, ignoreCase = true) }

    /** 已安装的 RetroArch (64 位优先) */
    fun installedRetroArch(): List<RaInstall> {
        val pm = ctx.packageManager
        return RA_PACKAGES.mapNotNull { pkg ->
            try {
                val info = pm.getPackageInfo(pkg, 0)
                RaInstall(
                    pkg = pkg,
                    versionName = info.versionName ?: "",
                    is64Bit = pkg.endsWith("aarch64") || pkg == "org.libretro.ra2",
                    hasPlayActivity = hasComponent(pkg, RA_PLAY_ACTIVITY),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareByDescending<RaInstall> { it.is64Bit }.thenBy { it.pkg })
    }

    fun hasAnyRetroArch(): Boolean = installedRetroArch().isNotEmpty()

    /**
     * 选一个 RA 来用。优先级:
     * 1. 设置里用户手动指定的包名
     * 2. 自动探测 (64 位优先)
     */
    fun preferredPackage(userPreference: String?): String? {
        if (isRetroArch(userPreference) && isInstalled(userPreference!!)) return userPreference
        return installedRetroArch().firstOrNull()?.pkg
    }

    fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun hasComponent(pkg: String, cls: String): Boolean = try {
        ctx.packageManager.getActivityInfo(
            android.content.ComponentName(pkg, cls), 0
        ); true
    } catch (_: Exception) { false }

    /** RA 能不能被外部 app 直接拉起 (有 <queries> 声明 + 装了) */
    fun canLaunchExternally(pkg: String): Boolean = hasComponent(pkg, RA_PLAY_ACTIVITY)

    /** 打开 RetroArch 主界面 (引导用户装 core / 授权存储) */
    fun openRetroArch(pkg: String): Boolean = try {
        ctx.startActivity(
            Intent().setClassName(pkg, RA_PLAY_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            true
        } catch (_: Exception) { false }
    }

    /** 跳 Play 商店 / buildbot 下载 RetroArch —— 用户压根没装时的出路 */
    fun openInstallPage(): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$RA_PACKAGES[1]"))
        return try {
            ctx.startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.retroarch.com/?page=platforms"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ); true
            } catch (_: Exception) { false }
        }
    }

    // ================= core 缓存目录 =================

    /** app 私有缓存 —— 放我们自己下的 core, 通过 LIBRETRO extra 让 RA 侧载 */
    fun coreCacheDir(): File =
        File(ctx.filesDir, "libretro/cores").apply { if (!exists()) mkdirs() }

    /**
     * RA 的数据目录, 按可能性排。
     *
     * 优先用外部目录 (`/sdcard/RetroArch` 或 `/sdcard/Android/data/<pkg>/files`):
     * RA 读 ROM 也要文件权限, 走 app 私有目录它读不到。
     * Android 11+ 上 `Android/data/<other-pkg>/files` 我们也读不了, 所以实际上
     * 只有 `/sdcard/RetroArch` 这类公共目录可写 —— 读不到就返回 null, 让上层
     * 走 `LIBRETRO` extra 的方式 (RA 自己 copy 核心)。
     */
    fun raDataDir(pkg: String): File? {
        val ext = ctx.getExternalFilesDir(null)?.parentFile
        val candidates = buildList {
            ext?.parentFile?.resolve("RetroArch")?.let(::add)          // /sdcard/RetroArch
            add(File("/sdcard/RetroArch"))                              // 多用户/受限存储兜底
            ext?.resolve("$pkg/files")?.let(::add)                      // 旧版 RA 布局
        }
        return candidates.firstOrNull { it.exists() && it.canWrite() }
    }

    fun raConfigFile(pkg: String): String? {
        val dir = raDataDir(pkg) ?: return null
        val cfg = File(dir, "retroarch.cfg")
        return if (cfg.exists()) cfg.absolutePath else File(dir, "config/retroarch.cfg").absolutePath
    }

    // ================= 平台 → core 映射 =================

    /**
     * 平台 code → 推荐 core 列表 (base name, 优先级从高到低)。
     *
     * 名单对着 buildbot `nightly/android/latest/<abi>/` 实际核过, 缺的会标
     * `null` 表示"buildbot 没有, 要手动装 / 用独立模拟器 app"。
     */
    private val PLATFORM_CORES: Map<String, List<String>> = mapOf(
        "FC"      to listOf("fceumm", "nestopia", "quicknes"),
        "SFC"     to listOf("snes9x", "snes9x2010", "snes9x2005", "snes9x2002", "mednafen_snes"),
        "N64"     to listOf("mupen64plus_next_gles3", "mupen64plus_next_gles2"),
        "GB"      to listOf("gambatte", "sameboy"),
        "GBC"     to listOf("gambatte", "sameboy"),
        "GBA"     to listOf("mgba", "mednafen_gba"),
        "NDS"     to listOf("melonds", "desmume", "desmume2015"),
        "3DS"     to listOf("citra"),
        "MD"      to listOf("genesis_plus_gx", "genesis_plus_gx_wide", "picodrive"),
        "SATURN"  to listOf("yabasanshiro", "mednafen_saturn"),
        "DC"      to listOf("flycast"),
        "PS1"     to listOf("swanstation", "pcsx_rearmed", "mednafen_psx"),
        "PS2"     to emptyList(),                                 // buildbot 无 PS2 core
        "PSP"     to listOf("ppsspp"),
        "WII"     to listOf("dolphin"),
        "GC"      to listOf("dolphin"),
        "PCE"     to listOf("mednafen_pce_fast", "mednafen_pce", "neocd"),
        "NEOGEO"  to listOf("fbneo", "mame2003_plus"),
        "MAME"    to listOf("mame2003_plus", "mame2016", "mamearcade", "fbneo"),
        "ARCADE"  to listOf("fbneo", "mame2003_plus"),
        "J2ME"    to emptyList(),                                 // buildbot 无 J2ME core
        "DOS"     to listOf("dosbox_pure", "dosbox_svn", "dosbox"),
        "WIN"     to emptyList(),                                 // 靠独立模拟器 app
        "FLASH"   to emptyList(),                                 // 靠独立模拟器 app
        "HTML"    to emptyList(),                                 // 靠内置 WebView
    )

    /** 平台没有 RA core 时的解释 (设置页 / 错误提示用) */
    private val NO_CORE_HINT: Map<String, String> = mapOf(
        "PS2" to "PS2 没有 libretro 核心, 请装 AetherSX2 (xyz.aethersx2)",
        "J2ME" to "J2ME 没有 Android 核心, 请装 FreeJ2ME 或 J2ME Loader",
        "WIN" to "Windows 游戏请用 Wine/BoxedWine 独立 App",
        "FLASH" to "Flash 游戏请用 Ruffle 独立 App",
        "HTML" to "HTML5 游戏直接用内置浏览器打开",
    )

    fun noCoreHint(platformCode: String): String? = NO_CORE_HINT[platformCode.uppercase()]

    /** 用户在设置里手动指定的 core (最高优先级) */
    suspend fun coreOverride(platformCode: String): String? = prefs.raCoreOverride(platformCode)

    suspend fun setCoreOverride(platformCode: String, core: String?) =
        prefs.setRaCoreOverride(platformCode, core)

    /** 解析该平台最终要用的 core: 手动指定 > 推荐列表[0] */
    suspend fun resolveCore(platformCode: String): String? {
        coreOverride(platformCode)?.let { return it }
        return PLATFORM_CORES[platformCode.uppercase()]?.firstOrNull()
    }

    fun recommendedCores(platformCode: String): List<String> =
        PLATFORM_CORES[platformCode.uppercase()].orEmpty()

    /** 本地已经有的 core (app 缓存 + RA 目录), 返回 base name 列表 */
    fun installedCores(): List<String> {
        val names = LinkedHashSet<String>()
        coreCacheDir().listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so")) names += stripVariant(f.name)
        }
        installedRetroArch().forEach { ra ->
            raDataDir(ra.pkg)?.let { dir ->
                listOf(File(dir, "cores"), File(dir, "libretro/cores")).forEach { c ->
                    c.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".so")) names += stripVariant(f.name)
                    }
                }
            }
        }
        return names.sorted()
    }

    /** 找出这个 core 的本地 .so 绝对路径 (缓存区优先, 再找 RA 目录) */
    suspend fun locateCore(platformCode: String): String? {
        val base = resolveCore(platformCode) ?: return null
        coreCacheDir().resolve("$base${coreFileSuffix()}").takeIf { it.exists() }?.let {
            return it.absolutePath
        }
        installedRetroArch().forEach { ra ->
            raDataDir(ra.pkg)?.let { dir ->
                listOf(File(dir, "cores/$base${coreFileSuffix()}"),
                        File(dir, "libretro/cores/$base${coreFileSuffix()}"))
                    .firstOrNull { it.exists() }
                    ?.let { return it.absolutePath }
            }
        }
        return null
    }

    // ================= 架构 / 文件名 =================

    fun abi(): String {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return when {
            abis.any { it.startsWith("arm64") || it == "aarch64" } -> "arm64-v8a"
            abis.any { it.startsWith("armeabi") } -> "armeabi-v7a"
            abis.any { it.startsWith("x86_64") } -> "x86_64"
            abis.any { it.startsWith("x86") } -> "x86"
            else -> "arm64-v8a"
        }
    }

    fun coreFileSuffix(): String = "_libretro_android.so"

    fun coreZipName(base: String): String = "$base${coreFileSuffix()}.zip"

    fun stripVariant(filename: String): String =
        filename.removeSuffix(".zip").removeSuffix(coreFileSuffix())

    fun buildbotUrl(base: String, abi: String? = null): String =
        "https://buildbot.libretro.com/nightly/android/latest/${abi ?: abi()}/${coreZipName(base)}"

    fun buildbotApkUrl(abi: String? = null): String {
        val suffix = when (abi ?: abi()) {
            "armeabi-v7a" -> "RetroArch_ra32.apk"
            "arm64-v8a" -> "RetroArch_aarch64.apk"
            else -> "RetroArch.apk"
        }
        return "https://buildbot.libretro.com/nightly/android/latest/$suffix"
    }

    // ================= 装核心 =================

    /**
     * 确保这个平台的 core 可用。返回可传给 RA 的 `.so` 绝对路径。
     *
     * 查找/获取顺序:
     * 1. App 自己的缓存 (之前下过的)
     * 2. RetroArch 已经装好的核心目录 (用户自己装过)
     * 3. libretro 官方 buildbot 下载 (按设备 ABI 自适应)
     *
     * buildbot 在国内可能连不上, 这时候会抛异常并提示用户去 RetroArch 里手动装.
     */
    suspend fun ensureCore(
        platformCode: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): String {
        val base = resolveCore(platformCode)
            ?: throw IOException(noCoreHint(platformCode) ?: "该平台没有可用的 RA 核心")

        locateCore(platformCode)?.let { return it }   // 已经有了

        val out = File(coreCacheDir(), "$base${coreFileSuffix()}")
        if (out.exists() && out.length() > 0) return out.absolutePath

        if (!tryDownloadFromBuildbot(base, out, onProgress)) {
            out.delete()
            throw IOException(
                "核心下载失败 ($base). libretro 官方源在国内经常连不上, " +
                    "可以先打开 RetroArch 在它的「在线更新 → 核心下载」里装好, " +
                    "或者换个网络再试"
            )
        }
        Log.i(TAG, "core ready: ${out.absolutePath} (${out.length()} bytes)")
        return out.absolutePath
    }

    private suspend fun tryDownloadFromBuildbot(
        base: String, out: File, onProgress: ((Long, Long) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = buildbotUrl(base)
        Log.i(TAG, "downloading core from buildbot: $url")
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            val resp = plain.newCall(Request.Builder().url(url).build()).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "buildbot ${it.code} for $url")
                    return@withContext false
                }
                val body = it.body ?: return@withContext false
                val total = body.contentLength()
                val zip = body.byteStream()
                // buildbot 给的是 zip, 流式解压只取 .so, 不落盘整个包
                val zin = ZipInputStream(zip)
                val sink = tmp.outputStream()
                var done = 0L
                val buf = ByteArray(64 * 1024)
                zin.use { z ->
                    var entry = z.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".so")) {
                            while (true) {
                                val n = z.read(buf)
                                if (n <= 0) break
                                sink.write(buf, 0, n)
                                done += n
                                onProgress?.invoke(done, if (total > 0) total else -1)
                            }
                            entry = null
                        } else {
                            entry = z.nextEntry
                        }
                    }
                }
                sink.flush(); sink.close()
            }
            if (tmp.length() <= 0) { tmp.delete(); return@withContext false }
            tmp.renameTo(out)
            true
        } catch (e: Exception) {
            Log.w(TAG, "buildbot download failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    private fun extractFirstSo(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        onProgress: ((Long, Long) -> Unit)? = null,
        total: Long = -1L,
    ) {
        ZipInputStream(input).use { z ->
            var entry = z.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".so")) {
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = z.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                    return
                }
                entry = z.nextEntry
            }
        }
    }

    companion object {
        /**
         * RetroArch 的包名变体 (Play 商店版 / 官网 aarch64 / RA2 / F-Droid)。
         * ⚠️ Android 11+ 必须同时在 AndroidManifest.xml 的 `<queries>` 里声明这些包,
         *    否则 `getPackageInfo` 会一律返回 null —— 这是老版本客户端"检测不到
         *    RetroArch"的真正原因。
         */
        val RA_PACKAGES = listOf(
            "com.retroarch.aarch64",   // 官网 64 位 (最常见)
            "com.retroarch",          // 官网 32 位 / Play 版
            "com.retroarch.ra32",
            "org.libretro.ra2",       // RetroArch 2 (nightly 主线)
            "org.fdroid.retroarch",
        )

        /** RA 前端的游戏启动 Activity (从 1.9 起所有变体都是这个类名) */
        const val RA_PLAY_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
        const val RA_MAIN_ACTIVITY = "com.retroarch.browser.mainmenu.MainMenuActivity"

        /** RA 认的 intent action (部分构建只认这个, 不认 component) */
        const val RA_LOAD_ACTION = "org.libretro.android.action.LOAD_CONTENT"
    }
}
