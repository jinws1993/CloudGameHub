package com.nasgame.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封面 / 截图的本地存储。
 *
 * 存在 app 私有目录, 不入数据库 —— 库里只记文件名, 这样以后想加"导出到相册"
 * 或者"清理缓存"都好办, 不用迁移数据。
 */
@Singleton
class MediaStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun dir(): File = File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }

    /** 库里记的是文件名, 这里还原成绝对路径 */
    fun pathOf(name: String): File? {
        if (name.isBlank()) return null
        val f = File(dir(), File(name).name)   // 只取文件名, 防目录穿越
        return f.takeIf { it.exists() }
    }

    fun exists(name: String): Boolean = pathOf(name) != null

    /** 保存用户手动上传的封面, 返回新文件名 */
    fun saveManualCover(gameId: Long, bytes: ByteArray, ext: String = "jpg"): String? {
        val safeExt = ext.lowercase().removePrefix(".").let {
            if (it in listOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        return try {
            val f = File(dir(), "cover_$gameId.$safeExt")
            f.writeBytes(bytes)
            f.name
        } catch (_: Exception) {
            null
        }
    }

    fun delete(name: String) {
        pathOf(name)?.delete()
    }

    /** 清掉没被任何游戏引用的孤儿封面 */
    fun gc(keepNames: Set<String>): Int {
        var n = 0
        dir().listFiles()?.forEach { f ->
            if (f.isFile && f.name !in keepNames) { f.delete(); n++ }
        }
        return n
    }

    fun totalBytes(): Long = dir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        dir().listFiles()?.forEach { it.delete() }
    }
}
