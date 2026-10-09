package com.cloudgamehub.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF (Storage Access Framework) 助手。
 *
 * ROM 要下到一个**模拟器读得到**的目录, 而 Android 11+ 不允许我们直接写
 * `/sdcard/RetroArch`。所以用系统文件选择器让用户挑一个公共目录, 拿到的
 * tree URI 长期授权, 之后用 DocumentFile 读写。
 *
 * 两个关键点:
 * 1. **必须 take persistable permission**, 否则重启后授权就失效
 * 2. **DocumentFile 不是 File** —— 但 tree URI 能反推回真实路径, 这对
 *    RetroArch 至关重要 (它只认绝对路径)
 */
object SafFileHelper {

    fun persistTreePermission(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    fun hasPersistedPermission(ctx: Context, treeUri: Uri): Boolean {
        val persisted = ctx.contentResolver.persistedUriPermissions
        return persisted.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
    }

    fun releaseTreePermission(ctx: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            ctx.contentResolver.releasePersistableUriPermission(treeUri, flags)
        } catch (_: Exception) {}
    }

    fun findOrCreateDir(ctx: Context, rootTree: DocumentFile, parts: List<String>): DocumentFile {
        var current = rootTree
        for (p in parts) {
            current = current.findFile(p) ?: current.createDirectory(p)
                ?: throw IllegalStateException("无法创建目录: $p")
        }
        return current
    }

    fun findDir(rootTree: DocumentFile, name: String): DocumentFile? =
        rootTree.findFile(name)?.takeIf { it.isDirectory }

    fun findOrCreateFile(
        ctx: Context, dir: DocumentFile, filename: String, mimeType: String = "application/octet-stream",
    ): DocumentFile =
        dir.findFile(filename) ?: dir.createFile(mimeType, filename)
            ?: throw IllegalStateException("无法创建文件: $filename")

    fun openInput(ctx: Context, doc: DocumentFile): InputStream =
        ctx.contentResolver.openInputStream(doc.uri)
            ?: throw IllegalStateException("无法打开: ${doc.uri}")

    /** @param truncate true = "wt" 截断重写, false = "wa" 追加 (断点续传用) */
    fun openOutput(ctx: Context, doc: DocumentFile, truncate: Boolean = true): OutputStream =
        ctx.contentResolver.openOutputStream(doc.uri, if (truncate) "wt" else "wa")
            ?: throw IllegalStateException("无法打开输出流: ${doc.uri}")

    fun toShareableUri(doc: DocumentFile): Uri = doc.uri

    /**
     * tree URI → 真实文件系统路径。
     *
     * `primary:Download/CloudGameHub` → `/storage/emulated/0/Download/CloudGameHub`
     * `1A2B-3C4D:Download/X`        → `/storage/1A2B-3C4D/Download/X`
     *
     * @return 真实路径; 推不出来 (SD 卡 / 特殊 provider) 返回 null
     */
    fun treeUriToPath(treeUri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rest = docId.substring(colon + 1)
        val root = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
        val path = if (rest.isBlank()) root else "$root/$rest"
        File(path).takeIf { it.exists() }?.absolutePath
    } catch (_: Exception) {
        null
    }

    /** SAF 目录里某个文件的真实路径; 拿不到返回 null */
    fun fileRealPath(treeUri: Uri, vararg pathSegments: String): String? {
        val root = treeUriToPath(treeUri) ?: return null
        val full = File(root, pathSegments.joinToString("/"))
        return if (full.exists()) full.absolutePath else null
    }

    /** 给 FileProvider 用的 content:// (给只认 content:// 的模拟器) */
    fun uriFor(ctx: Context, file: File): Uri = runCatching {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }.getOrElse { Uri.fromFile(file) }
}
