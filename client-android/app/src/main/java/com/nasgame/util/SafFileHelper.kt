package com.nasgame.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework (SAF) 文件助手.
 *
 * 用户在设置中通过系统文件选择器 [Intent.ACTION_OPEN_DOCUMENT_TREE] 选定一个目录后,
 * 我们把这个 Uri 存到 PrefsStore。后续读写 ROM 都通过 DocumentFile API 访问,
 * 不需要 WRITE_EXTERNAL_STORAGE 权限。
 *
 * 关键点:
 * 1. **必须 take persistable URI permission**, 否则重启后失效
 * 2. **路径不能直接拼** — 子目录用 DocumentFile.findFile 或 createDirectory 递归
 * 3. **FileProvider 仍然能工作** — content:// URI 可以喂给第三方 app (模拟器)
 */
object SafFileHelper {

    /**
     * 持久化用户选择的目录 URI (重启后仍有效).
     * 必须调用一次, 否则 URI 在进程被杀后失效.
     */
    fun persistTreePermission(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    /** 检查 URI 是否仍有 persistable permission (重启后可能失效) */
    fun hasPersistedPermission(ctx: Context, treeUri: Uri): Boolean {
        val persisted = ctx.contentResolver.persistedUriPermissions
        return persisted.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
    }

    /** 释放持久化权限 */
    fun releaseTreePermission(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.releasePersistableUriPermission(treeUri, flags)
        } catch (_: Exception) {}
    }

    /**
     * 在用户选定的根目录下, 找或创建子目录路径 (e.g. ["FC", "roms"]).
     */
    fun findOrCreateDir(
        ctx: Context,
        rootTree: DocumentFile,
        parts: List<String>,
    ): DocumentFile {
        var current = rootTree
        for (p in parts) {
            val next = current.findFile(p)
            current = next ?: current.createDirectory(p)
                ?: throw IllegalStateException("无法创建子目录: $p")
        }
        return current
    }

    /** 找或创建文件 */
    fun findOrCreateFile(
        ctx: Context,
        dir: DocumentFile,
        filename: String,
        mimeType: String = "application/octet-stream",
    ): DocumentFile {
        val existing = dir.findFile(filename)
        if (existing != null) return existing
        return dir.createFile(mimeType, filename)
            ?: throw IllegalStateException("无法创建文件: $filename")
    }

    /** 打开 SAF 文件的输入流 */
    fun openInput(ctx: Context, doc: DocumentFile): InputStream =
        ctx.contentResolver.openInputStream(doc.uri)
            ?: throw IllegalStateException("无法打开 URI 输入流: ${doc.uri}")

    /** 打开 SAF 文件的输出流 (truncate = true) */
    fun openOutput(ctx: Context, doc: DocumentFile, truncate: Boolean = true): OutputStream =
        ctx.contentResolver.openOutputStream(doc.uri, if (truncate) "wt" else "w")
            ?: throw IllegalStateException("无法打开 URI 输出流: ${doc.uri}")

    /** 把 SAF 文件转成 content:// URI 喂给第三方 (模拟器) */
    fun toShareableUri(doc: DocumentFile): Uri = doc.uri
}