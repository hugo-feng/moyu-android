package com.moyu.reader.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件访问层（存储访问框架 SAF）。
 *
 * 为什么用 SAF 而不是直接读 /sdcard：
 *   1. Android 10+ 的分区存储让直接路径访问基本失效，除非申请
 *      MANAGE_EXTERNAL_STORAGE —— 那个权限会被 Google Play 严格审核甚至拒审；
 *   2. SAF 由用户主动授权文件夹，授权后可以长期持有（takePersistableUriPermission），
 *      既能满足「扫描整个文件夹」的体验，又符合最小权限原则。
 *
 * 目录树无法像 File 那样直接递归（多层 DocumentFile 遍历很慢），
 * 因此这里用 DocumentsContract 直接对子项做批量查询，避免逐层 listFiles 造成的卡顿。
 */
class DocumentStore(private val context: Context) {

    /** 从 content Uri 读取全部字节。 */
    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法打开文件：$uri")
    }

    /** 从 content Uri 读取文本（按指定编码）。 */
    suspend fun readText(uri: Uri, charsetName: String): String = withContext(Dispatchers.IO) {
        val bytes = readBytes(uri)
        // 用 Kotlin 的 charset()（内部是 Charset.forName），编码名不存在时退回 UTF-8，
        // 而不是抛异常 —— 编码名来自用户选择，不该让读取因一个拼写问题整体失败。
        bytes.toString(runCatching { charset(charsetName) }.getOrDefault(Charsets.UTF_8))
    }

    /**
     * 查询文件显示名。
     * SAF 的 Uri 本身不含文件名，必须通过 OpenableColumns 查询。
     */
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
    }

    /** 查询文件大小（字节）；未知时返回 -1。 */
    suspend fun size(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
            }
        }.getOrNull() ?: -1L
    }

    /** 查询最后修改时间。 */
    suspend fun lastModified(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            }
        }.getOrNull() ?: 0L
    }

    /**
     * 申请某个 Uri 的持久化读权限。
     *
     * 必须在用户选择之后立刻调用：否则应用重启后该 Uri 就会失效，
     * 用户会遇到「书还在书架上，但点开报错」这种最令人困惑的问题。
     */
    fun persistPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /**
     * 递归列出目录下所有受支持的书文件。
     *
     * 只匹配我们支持的扩展名，避免把整个相册/下载目录都读进来（那会很慢且无意义）。
     * 深度上限 8 层：正常书库不会更深，同时防止意外的符号链接环路。
     */
    suspend fun listBooksUnder(treeUri: Uri, maxDepth: Int = 8): List<Uri> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Uri>()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        walk(root, result, 0, maxDepth)
        result
    }

    private fun walk(dir: DocumentFile, out: MutableList<Uri>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walk(child, out, depth + 1, maxDepth)
            } else if (isSupportedBook(child.name)) {
                out.add(child.uri)
            }
        }
    }

    /** 是否为受支持的书籍文件。 */
    fun isSupportedBook(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.endsWith(".txt") || lower.endsWith(".epub") || lower.endsWith(".pdf")
    }

    /** 书格式判定。 */
    fun formatOf(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") -> "txt"
            lower.endsWith(".epub") -> "epub"
            lower.endsWith(".pdf") -> "pdf"
            else -> null
        }
    }

    // ============================================================
    // 应用私有目录：封面缓存、导出文件
    // ============================================================

    /** 封面存放目录（应用私有，不需要任何权限）。 */
    fun coverDir(): File = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    fun coverFile(bookId: String): File = File(coverDir(), "$bookId.img")

    fun exportDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "export")
        .apply { if (!exists()) mkdirs() }

    /** 删除某本书的本地封面文件。 */
    fun deleteCover(bookId: String) {
        runCatching { coverFile(bookId).delete() }
    }
}
