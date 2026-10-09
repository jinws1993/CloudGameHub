package com.cloudgamehub.emulator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object EmulatorLauncher {

    private val KNOWN_PACKAGES = mapOf(
        "FC" to listOf("com.retroarch", "com.retroarch.aarch64", "org.fdroid.retroarch", "com.nostalgia.nes"),
        "SFC" to listOf("com.retroarch", "com.explusalpha.Snes9xPlus"),
        "N64" to listOf("org.mupen64plusae.pro", "com.retroarch"),
        "GBA" to listOf("mgba", "com.retroarch"),
        "GB" to listOf("com.explusalpha.GbcEmulator", "com.retroarch"),
        "GBC" to listOf("com.explusalpha.GbcEmulator", "com.retroarch"),
        "NDS" to listOf("com.drastic", "com.explusalpha.MelonDs", "com.retroarch"),
        "PSP" to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "com.retroarch"),
        "PS1" to listOf("com.github.stenzek.duckstation", "com.retroarch"),
        "PS2" to listOf("xyz.aethersx2", "com.alyx.arp"),
        "DC" to listOf("com.flycastEmu.flycast", "com.retroarch"),
        "MD" to listOf("com.explusalpha.MdEmulator", "com.explusalpha.MdEmu", "com.retroarch"),
        "3DS" to listOf("org.citra.citra_emu", "io.github.lime3ds.android", "com.retroarch"),
        "WII" to listOf("org.dolphinemu.dolphinemu"),
        "GC" to listOf("org.dolphinemu.dolphinemu"),
        "ARCADE" to listOf("com.explusalpha.MameEmu", "com.flycastEmu.flycast", "com.retroarch"),
        "MAME" to listOf("com.explusalpha.MameEmu", "com.retroarch"),
        "LYNX" to listOf("com.retroarch"),
        "WS" to listOf("com.retroarch"),
        "NGP" to listOf("com.retroarch"),
        "A2600" to listOf("com.retroarch"),
        "INTELLIVISION" to listOf("com.retroarch"),
    )

    /** 常见 RetroArch 包名 */
    val RETROARCH_PACKAGES = setOf(
        "com.retroarch",
        "com.retroarch.aarch64",
        "com.retroarch.unsigned",
        "org.fdroid.retroarch",
    )

    fun resolvePackage(
        ctx: Context,
        platformCode: String,
        userPkg: String?,
        globalPkg: String? = null,
    ): String? {
        if (!userPkg.isNullOrBlank() && isInstalled(ctx, userPkg)) return userPkg
        if (!globalPkg.isNullOrBlank() && isInstalled(ctx, globalPkg)) return globalPkg
        return KNOWN_PACKAGES[platformCode]?.firstOrNull { isInstalled(ctx, it) }
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun isRetroArch(pkg: String): Boolean = pkg in RETROARCH_PACKAGES

    /**
     * 启动模拟器.
     *
     * @param uri ROM 的 Uri (FileProvider 或 SAF 都直接传 Uri 即可)
     * @param romPath ROM 的可读路径 (e.g. "/storage/emulated/0/..."), 用于 putExtra
     */
    fun launch(
        ctx: Context,
        pkg: String,
        uri: Uri,
        romPath: String,
        defaultCore: String? = null,
    ): Boolean {
        return if (isRetroArch(pkg)) {
            launchRetroArch(ctx, pkg, uri, romPath, defaultCore)
        } else {
            launchGeneric(ctx, pkg, uri, romPath)
        }
    }

    /** 兼容旧 API (传 File) */
    fun launch(
        ctx: Context,
        pkg: String,
        romPath: String,
        defaultCore: String? = null,
    ): Boolean {
        val romFile = File(romPath)
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", romFile)
        } catch (_: Exception) {
            Uri.fromFile(romFile)
        }
        return launch(ctx, pkg, uri, romPath, defaultCore)
    }

    private fun launchRetroArch(
        ctx: Context, pkg: String, uri: Uri, romPath: String, defaultCore: String?,
    ): Boolean {
        val intent = Intent("org.libretro.android.action.LOAD_CONTENT").apply {
            setPackage(pkg)
            setDataAndType(uri, mimeForRom(romPath))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("ROM", romPath)
            putExtra("romPath", romPath)
            putExtra("EXTRA_ROM", romPath)
            putExtra("EXTRA_STREAM", uri)
            if (!defaultCore.isNullOrBlank()) {
                putExtra("LIBRETRO_CORE", defaultCore)
                putExtra("core", defaultCore)
            }
        }
        return tryStartActivity(ctx, intent, fallbackActionView = uri, romPath = romPath, pkg = pkg)
    }

    private fun launchGeneric(ctx: Context, pkg: String, uri: Uri, romPath: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForRom(romPath))
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("ROM", romPath)
            putExtra("romPath", romPath)
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        return tryStartActivity(ctx, intent, fallbackActionView = uri, romPath = romPath, pkg = pkg)
    }

    private fun tryStartActivity(
        ctx: Context, intent: Intent, fallbackActionView: Uri, romPath: String, pkg: String,
    ): Boolean {
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fallbackActionView, mimeForRom(romPath))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("ROM", romPath)
            }
            try {
                ctx.startActivity(Intent.createChooser(fallback, "选择模拟器打开 ROM"))
                true
            } catch (_: Exception) { false }
        }
    }

    private fun mimeForRom(p: String): String = when {
        p.endsWith(".nes", true) || p.endsWith(".fds", true) -> "application/x-nes-rom"
        p.endsWith(".smc", true) || p.endsWith(".sfc", true) -> "application/x-sfc-rom"
        p.endsWith(".gba", true) -> "application/x-gba-rom"
        p.endsWith(".gb", true) || p.endsWith(".gbc", true) -> "application/x-gb-rom"
        p.endsWith(".nds", true) -> "application/x-nintendo-ds-rom"
        p.endsWith(".iso", true) -> "application/x-cd-image"
        p.endsWith(".cue", true) -> "application/x-cue"
        p.endsWith(".zip", true) -> "application/zip"
        p.endsWith(".7z", true) -> "application/x-7z-compressed"
        p.endsWith(".jar", true) -> "application/java-archive"
        else -> "*/*"
    }
}