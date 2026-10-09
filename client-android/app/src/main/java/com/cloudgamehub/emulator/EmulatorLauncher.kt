package com.cloudgamehub.emulator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 唤起模拟器的统一入口。
 *
 * ## RetroArch 的启动契约 (别乱改)
 *
 * RA 的 Android 前端不认 `ACTION_VIEW`, 认自己那套 extra:
 *
 * ```
 * am start -n <pkg>/com.retroarch.browser.retroactivity.RetroActivityFuture \
 *     -e ROM        /sdcard/RetroArch/roms/FC/mario.nes \
 *     -e LIBRETRO   /data/data/com.retroarch.aarch64/cores/fceumm_libretro_android.so \
 *     -e CONFIGFILE /sdcard/RetroArch/retroarch.cfg \
 *     -e IME        com.android.inputmethod.latin/.LatinIME
 * ```
 *
 * 踩过的坑, 这里都处理了:
 * 1. **`LIBRETRO` 必须是完整路径**, 不能只给 core 文件名。2025-01-17 之后的
 *    nightly 改过 (libretro/RetroArch#17433), 只给名字会 core 加载失败。
 * 2. **`ROM` 最好是真实文件路径**。content:// 只有部分构建能吃, 且需要
 *    `FLAG_GRANT_READ_URI_PERMISSION`。
 * 3. **RA 读不到 app 私有目录** —— `/sdcard/Android/data/<别的app>/...` 在
 *    Android 11+ 上别的 app 无权访问, 所以 ROM 建议放公共目录或 SAF 目录。
 *    拿不到真实路径时上层会明确报错, 而不是让 RA 打开一片空白。
 * 4. 不同构建能认的东西不一样, 所以**逐级降级**:
 *    component → LOAD_CONTENT action → 主界面 + ROM extra → 系统 chooser。
 */
object EmulatorLauncher {

    private const val TAG = "EmulatorLauncher"

    /** 平台 → 独立模拟器 app 包名 (RetroArch 覆盖不到的, 走这些) */
    private val KNOWN_PACKAGES: Map<String, List<String>> = mapOf(
        "FC"      to listOf("com.frodo.nes", "com.nostalgia.nes", "com.retroarch.aarch64", "com.retroarch"),
        "SFC"     to listOf("com.explusalpha.Snes9xPlus", "com.retroarch.aarch64", "com.retroarch"),
        "N64"     to listOf("org.mupen64plusae.pro", "com.retroarch.aarch64", "com.retroarch"),
        "GBA"     to listOf("mgba", "com.retroarch.aarch64", "com.retroarch"),
        "GB"      to listOf("com.explusalpha.GbcEmulator", "com.retroarch.aarch64", "com.retroarch"),
        "GBC"     to listOf("com.explusalpha.GbcEmulator", "com.retroarch.aarch64", "com.retroarch"),
        "NDS"     to listOf("com.drastic", "com.explusalpha.MelonDs", "com.retroarch.aarch64", "com.retroarch"),
        "PSP"     to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "com.retroarch.aarch64", "com.retroarch"),
        "PS1"     to listOf("com.github.stenzek.duckstation", "com.retroarch.aarch64", "com.retroarch"),
        "PS2"     to listOf("xyz.aethersx2", "com.alyx.arp"),
        "DC"      to listOf("com.flycastEmu.flycast", "com.retroarch.aarch64", "com.retroarch"),
        "MD"      to listOf("com.explusalpha.MdEmulator", "com.explusalpha.MdEmu", "com.retroarch.aarch64", "com.retroarch"),
        "3DS"     to listOf("org.citra.citra_emu", "io.github.lime3ds.android", "com.retroarch.aarch64", "com.retroarch"),
        "WII"     to listOf("org.dolphinemu.dolphinemu"),
        "GC"      to listOf("org.dolphinemu.dolphinemu"),
        "ARCADE"  to listOf("com.explusalpha.MameEmu", "com.flycastEmu.flycast", "com.retroarch.aarch64", "com.retroarch"),
        "MAME"    to listOf("com.explusalpha.MameEmu", "com.flycastEmu.flycast", "com.retroarch.aarch64", "com.retroarch"),
        "NEOGEO"  to listOf("com.explusalpha.MameEmu", "com.flycastEmu.flycast"),
        "PCE"     to listOf("com.retroarch.aarch64", "com.retroarch"),
        "SATURN"  to listOf("com.retroarch.aarch64", "com.retroarch"),
        "J2ME"    to listOf("com.x2x3s.sse", "org.j2me_loader"),
        "DOS"     to listOf("com.dosbox", "com.retroarch.aarch64", "com.retroarch"),
        "LYNX"    to listOf("com.retroarch.aarch64", "com.retroarch"),
        "WS"      to listOf("com.retroarch.aarch64", "com.retroarch"),
        "NGP"     to listOf("com.retroarch.aarch64", "com.retroarch"),
        "A2600"   to listOf("com.retroarch.aarch64", "com.retroarch"),
        "INTELLIVISION" to listOf("com.retroarch.aarch64", "com.retroarch"),
    )

    /**
     * 启动结果。带 [via] 是为了日志和 UI 提示 —— 到底走的哪条路,
     * 以后出问题好定位 (component / LOAD_CONTENT / chooser)。
     */
    sealed class Result {
        data class Ok(val pkg: String, val via: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    fun isRetroArch(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && RetroArchManager.RA_PACKAGES.any { it.equals(pkg, true) }

    /**
     * 解析该平台该用哪个包名。
     * 优先级: 平台级配置 → 全局配置 → RA (用户没显式配的话 RA 永远兜底) → 已知包
     */
    fun resolvePackage(
        ctx: Context,
        platformCode: String,
        userPkg: String?,
        globalPkg: String? = null,
    ): String? {
        if (!userPkg.isNullOrBlank() && isInstalled(ctx, userPkg)) return userPkg
        if (!globalPkg.isNullOrBlank() && isInstalled(ctx, globalPkg)) return globalPkg
        // RA 是一站式前端, 装了 RA 就优先用它 (用户装 RA 就是想要 RA)
        RetroArchManager.RA_PACKAGES.firstOrNull { isInstalled(ctx, it) }?.let { return it }
        return KNOWN_PACKAGES[platformCode.uppercase()]?.firstOrNull { isInstalled(ctx, it) }
    }

    // ================= 统一入口 =================

    /** 按包名类型自动选 RA 或通用路径 */
    fun launch(
        ctx: Context,
        pkg: String,
        handle: RomHandle,
        corePath: String? = null,
        configFile: String? = null,
    ): Result = if (isRetroArch(pkg)) {
        launchRetroArch(ctx, pkg, handle, corePath, configFile)
    } else {
        launchGeneric(ctx, pkg, handle)
    }

    // ================= RetroArch =================

    /**
     * 用 RA 启动游戏。
     *
     * @param handle   本地 ROM (真实路径拿不到时降级用 content://)
     * @param corePath core `.so` 的绝对路径, 没有就交给 RA 自己弹核心选择器
     */
    fun launchRetroArch(
        ctx: Context,
        pkg: String,
        handle: RomHandle,
        corePath: String? = null,
        configFile: String? = null,
    ): Result {
        val romValue = handle.path ?: handle.uriString()

        // --- 1. 首选: 显式 component + 完整 extra (2025+ 唯一可靠的方式) ---
        if (componentExists(ctx, pkg, RetroArchManager.RA_PLAY_ACTIVITY)) {
            val intent = baseIntent(ctx, pkg, handle, romValue).apply {
                component = ComponentName(pkg, RetroArchManager.RA_PLAY_ACTIVITY)
                if (!corePath.isNullOrBlank()) putExtra("LIBRETRO", corePath)
                if (!configFile.isNullOrBlank()) putExtra("CONFIGFILE", configFile)
                putExtra("IME", currentIme(ctx))
            }
            if (start(ctx, intent)) return Result.Ok(pkg, "RetroActivityFuture")
        }

        // --- 2. 退回 LOAD_CONTENT action (部分构建只认这个) ---
        val actionIntent = baseIntent(ctx, pkg, handle, romValue).apply {
            action = RetroArchManager.RA_LOAD_ACTION
            addCategory(Intent.CATEGORY_DEFAULT)
            if (!corePath.isNullOrBlank()) {
                // action 路线的老构建只认文件名, 完整路径反而不认
                putExtra("LIBRETRO", corePath.substringAfterLast('/'))
            }
        }
        if (start(ctx, actionIntent)) return Result.Ok(pkg, "LOAD_CONTENT")

        // --- 3. 实在不行, 拉主界面让用户自己点 (总比什么都不做强) ---
        if (componentExists(ctx, pkg, RetroArchManager.RA_MAIN_ACTIVITY)) {
            val menuIntent = Intent().setClassName(pkg, RetroArchManager.RA_MAIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("ROM", romValue)
            if (start(ctx, menuIntent)) return Result.Ok(pkg, "主界面")
        }

        return Result.Failed(
            "RetroArch ($pkg) 无法响应启动请求. 常见原因: 该版本没开外部启动接口, " +
                "请换官网版 (buildbot.libretro.com) 或先手动打开一次 RetroArch 授予存储权限"
        )
    }

    private fun baseIntent(
        ctx: Context, pkg: String, handle: RomHandle, romValue: String,
    ): Intent = Intent().apply {
        setPackage(pkg)
        // data 带上 mime, 兼容那些只认 content:// 的构建
        setDataAndType(handle.uri, mimeForRom(handle.displayName))
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        putExtra("ROM", romValue)
        putExtra("romPath", romValue)
        putExtra("EXTRA_ROM", romValue)
        putExtra("EXTRA_STREAM", handle.uri)
        putExtra(Intent.EXTRA_STREAM, handle.uri)
    }

    private fun componentExists(ctx: Context, pkg: String, cls: String): Boolean = try {
        ctx.packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true
    } catch (_: Exception) { false }

    /** 当前输入法, RA 拿它决定要不要弹软键盘 */
    private fun currentIme(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: "com.android.inputmethod.latin/.LatinIME"
    } catch (_: Exception) {
        "com.android.inputmethod.latin/.LatinIME"
    }

    // ================= 其它模拟器 =================

    /** 非 RA 的模拟器 (PPSSPP / AetherSX2 / DraStic ...): 标准 ACTION_VIEW */
    fun launchGeneric(ctx: Context, pkg: String, handle: RomHandle): Result {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(handle.uri, mimeForRom(handle.displayName))
            setPackage(pkg)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra("ROM", handle.path ?: handle.uriString())
            putExtra("romPath", handle.path ?: handle.uriString())
            putExtra(Intent.EXTRA_STREAM, handle.uri)
        }
        if (start(ctx, intent)) return Result.Ok(pkg, "ACTION_VIEW")

        // 有些 app 不吃 mime, 再用裸 content:// 试一次
        val bare = Intent(Intent.ACTION_VIEW).apply {
            setData(handle.uri)
            setPackage(pkg)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (start(ctx, bare)) return Result.Ok(pkg, "ACTION_VIEW(裸)")
        return Result.Failed("$pkg 拒绝了这个 ROM")
    }

    /** 最后的兜底: 交给系统 chooser 让用户自己挑 */
    fun launchChooser(ctx: Context, handle: RomHandle): Result = try {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(handle.uri, mimeForRom(handle.displayName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "选择模拟器打开 ROM"))
        Result.Ok("(系统选择)", "chooser")
    } catch (e: Exception) {
        Result.Failed("没有能打开 ROM 的应用: ${e.message}")
    }

    // ================= 工具 =================

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "start failed: ${e.message}")
        false
    }

    fun mimeForRom(p: String): String = when {
        p.endsWith(".nes", true) || p.endsWith(".fds", true) -> "application/x-nes-rom"
        p.endsWith(".smc", true) || p.endsWith(".sfc", true) -> "application/x-sfc-rom"
        p.endsWith(".gba", true) -> "application/x-gba-rom"
        p.endsWith(".gb", true) || p.endsWith(".gbc", true) -> "application/x-gb-rom"
        p.endsWith(".nds", true) -> "application/x-nintendo-ds-rom"
        p.endsWith(".n64", true) || p.endsWith(".z64", true) -> "application/x-n64-rom"
        p.endsWith(".iso", true) || p.endsWith(".gcm", true) -> "application/x-cd-image"
        p.endsWith(".cue", true) -> "application/x-cue"
        p.endsWith(".chd", true) -> "application/x-chd"
        p.endsWith(".zip", true) -> "application/zip"
        p.endsWith(".7z", true) -> "application/x-7z-compressed"
        p.endsWith(".jar", true) -> "application/java-archive"
        else -> "*/*"
    }
}
