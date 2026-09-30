package com.nasgame.emulator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object EmulatorLauncher {

    private val KNOWN_PACKAGES = mapOf(
        "FC" to listOf("com.retroarch", "com.retroarch.aarch64", "org.fdroid.retroarch"),
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
        "MD" to listOf("com.explusalpha.MdEmu", "com.retroarch"),
        "3DS" to listOf("org.citra.citra_emu", "io.github.lime3ds.android"),
        "WII" to listOf("org.dolphinemu.dolphinemu"),
        "GC" to listOf("org.dolphinemu.dolphinemu"),
        "ARCADE" to listOf("com.explusalpha.MameEmu", "com.flycastEmu.flycast"),
        "MAME" to listOf("com.explusalpha.MameEmu"),
    )

    /** Find the user-configured package or auto-detect one for the platform. */
    fun resolvePackage(ctx: Context, platformCode: String, userPkg: String?): String? {
        if (!userPkg.isNullOrBlank()) return userPkg
        val pm = ctx.packageManager
        return KNOWN_PACKAGES[platformCode]?.firstOrNull {
            try {
                pm.getPackageInfo(it, 0); true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
    }

    /**
     * Launch emulator with the given ROM.
     * Returns true if launched successfully.
     */
    fun launch(ctx: Context, pkg: String, romPath: String): Boolean {
        val romFile = File(romPath)
        if (!romFile.exists()) return false

        val uri: Uri = try {
            // Try FileProvider for content:// URI
            FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                romFile,
            )
        } catch (_: Exception) {
            Uri.fromFile(romFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForRom(romPath))
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("ROM", romPath)
            putExtra("romPath", romPath)
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        return try {
            ctx.startActivity(intent); true
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeForRom(p: String): String = when {
        p.endsWith(".nes", true) || p.endsWith(".fds", true) -> "application/x-nes-rom"
        p.endsWith(".smc", true) || p.endsWith(".sfc", true) -> "application/x-sfc-rom"
        p.endsWith(".gba", true) -> "application/x-gba-rom"
        p.endsWith(".gb", true) || p.endsWith(".gbc", true) -> "application/x-gb-rom"
        p.endsWith(".iso", true) -> "application/x-cd-image"
        p.endsWith(".cue", true) -> "application/x-cue"
        p.endsWith(".zip", true) -> "application/zip"
        p.endsWith(".jar", true) -> "application/java-archive"
        else -> "*/*"
    }
}
