package com.cloudgamehub.emulator

import android.net.Uri

/**
 * 本地 ROM 的"可启动句柄"。
 *
 * ## 为什么需要它
 *
 * 之前的代码到处用 `File` + `absolutePath` 传给模拟器。这在 SAF 模式下是个实打实的坑:
 * 用户把 ROM 目录选成 SAF 目录 (比如 `Download/CloudGameHub`) 时, app 手上只有一个
 * `content://` DocumentFile, 它在 app 私有目录下**并不存在**对应的 `File`。
 * 把那个根本不存在的 `absolutePath` 丢给模拟器, 结果就是 RetroArch 打开一片空白。
 *
 * 所以这里显式区分两件事:
 * - [uri]   —— 一定能喂给第三方 app 的 `content://` (FileProvider / SAF), 但模拟器
 *              未必能当**文件路径**用
 * - [path]  —— 真实存在、模拟器能用 `open()` 直接读的绝对路径。拿不到就是 null,
 *              这种情况必须提示用户把 ROM 目录换成公共目录
 */
data class RomHandle(
    val uri: Uri,
    val path: String?,
    val displayName: String,
) {
    /** 模拟器能拿到真实路径 (RetroArch 强依赖这个) */
    val hasRealPath: Boolean get() = !path.isNullOrBlank()

    /** 回退给只认 content:// 的模拟器 (AetherSX2 / PPSSPP / DraStic 都能吃) */
    fun uriString(): String = uri.toString()
}
