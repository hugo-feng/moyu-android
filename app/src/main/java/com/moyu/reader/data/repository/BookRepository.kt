package com.moyu.reader.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.model.Chapter
import com.moyu.reader.data.model.ChapterHeader
import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.data.model.ReadingPosition
import com.moyu.reader.data.model.ReadingStatus
import com.moyu.reader.data.model.ShelfItem
import com.moyu.reader.data.toEntity
import com.moyu.reader.data.toHeader
import com.moyu.reader.data.toModel
import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.db.ReadingPositionEntity
import com.moyu.reader.parser.EpubParser
import com.moyu.reader.parser.ParsedBook
import com.moyu.reader.parser.TxtParser
import com.moyu.reader.storage.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 书籍仓储：书库的唯一读写入口。
 *
 * 职责边界：
 *   - 解析（parser）、存储（database）、文件访问（documentStore）都在它下面，
 *     UI 只跟它打交道，不直接碰 Room 或 SAF；
 *   - 相对时间文案、进度百分比这类派生数据也在这里算好，
 *     避免书架上显示 99%、详情页显示 100% 这种不一致。
 */
class BookRepository(
    private val context: Context,
    private val database: MoyuDatabase,
    private val documentStore: DocumentStore,
) {

    private val bookDao get() = database.bookDao()
    private val chapterDao get() = database.chapterDao()
    private val positionDao get() = database.readingPositionDao()

    /** 书架数据流：书籍 + 位置 + 分组的组合。 */
    val shelfItems: Flow<List<ShelfItem>> = combine(
        bookDao.observeAll(),
        positionDao.observeAll(),
    ) { books, positions ->
        val positionByBook = positions.associateBy { it.bookId }
        books.map { entity ->
            val book = entity.toModel()
            val position = positionByBook[book.id]
            val percent = when {
                book.status == ReadingStatus.FINISHED -> 1f
                position != null -> position.percent
                else -> 0f
            }
            val finished = book.status == ReadingStatus.FINISHED || percent >= 0.995f
            ShelfItem(
                book = book,
                percent = if (finished) 1f else percent,
                finished = finished,
                unread = book.status == ReadingStatus.UNREAD && (position?.globalOffset ?: 0) == 0,
                lastReadLabel = formatRelativeTime(book.lastReadAt),
            )
        }
    }

    val books: Flow<List<Book>> = bookDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getBook(id: String): Book? = withContext(Dispatchers.IO) {
        bookDao.findById(id)?.toModel()
    }

    suspend fun getChapterHeaders(bookId: String): List<ChapterHeader> = withContext(Dispatchers.IO) {
        chapterDao.getChapterHeaders(bookId).map { it.toHeader() }
    }

    /** 取单章正文。刻意不做整本加载：大文件下那会直接 OOM。 */
    suspend fun getChapter(bookId: String, index: Int): Chapter? = withContext(Dispatchers.IO) {
        chapterDao.getChapter(bookId, index)?.toModel()
    }

    suspend fun getPosition(bookId: String): ReadingPosition? = withContext(Dispatchers.IO) {
        positionDao.find(bookId)?.toModel()
    }

    suspend fun savePosition(bookId: String, position: ReadingPosition) = withContext(Dispatchers.IO) {
        positionDao.upsert(position.toEntity(bookId))
        bookDao.touchLastRead(bookId, System.currentTimeMillis())
        // 首次开读时把「未读」推进到「在读」
        val book = bookDao.findById(bookId)
        if (book != null && book.status == com.moyu.reader.data.db.ReadingStatus.UNREAD) {
            bookDao.updateStatus(bookId, com.moyu.reader.data.db.ReadingStatus.READING)
        }
    }

    suspend fun setStatus(bookId: String, status: ReadingStatus) = withContext(Dispatchers.IO) {
        bookDao.updateStatus(
            bookId,
            when (status) {
                ReadingStatus.UNREAD -> com.moyu.reader.data.db.ReadingStatus.UNREAD
                ReadingStatus.READING -> com.moyu.reader.data.db.ReadingStatus.READING
                ReadingStatus.FINISHED -> com.moyu.reader.data.db.ReadingStatus.FINISHED
            },
        )
    }

    suspend fun moveToGroup(bookId: String, groupId: String?) = withContext(Dispatchers.IO) {
        bookDao.updateGroup(bookId, groupId)
    }

    suspend fun updateMetadata(
        bookId: String,
        title: String? = null,
        author: String? = null,
        intro: String? = null,
        coverPath: String? = null,
    ) = withContext(Dispatchers.IO) {
        val existing = bookDao.findById(bookId) ?: return@withContext
        bookDao.update(
            existing.copy(
                title = title ?: existing.title,
                author = author ?: existing.author,
                intro = intro ?: existing.intro,
                // coverPath 传空字符串表示「清除封面」，null 表示「不改」
                coverPath = when (coverPath) {
                    null -> existing.coverPath
                    "" -> null
                    else -> coverPath
                },
            )
        )
    }

    /** 删除书籍。外键 CASCADE 会一并清掉章节、书签、笔记、会话。 */
    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        bookDao.deleteById(bookId)
        documentStore.deleteCover(bookId)
    }

    suspend fun countBooks(): Int = withContext(Dispatchers.IO) { bookDao.count() }

    /** 一次性取出全部书籍（用于「清除全部数据」这类批量操作）。 */
    suspend fun getAllBooksOnce(): List<Book> = withContext(Dispatchers.IO) {
        bookDao.getAll().map { it.toModel() }
    }

    // ============================================================
    // 导入
    // ============================================================

    /**
     * 从 SAF Uri 导入一本书。
     *
     * 完整流程：读取字节 → 按格式解析 → 判重 → 事务写入（书 + 章节 + 初始位置）→ 存封面。
     *
     * 判重用「标题 + 字数」的组合：单看标题会误判同名不同书，
     * 单看字数会误判恰好等长的不同书，两者结合后误判率极低。
     */
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val fileName = documentStore.displayName(uri)
        val format = documentStore.formatOf(fileName)
            ?: return@withContext ImportResult.Failure(fileName, "不支持的文件格式")

        // 立刻申请持久化权限：否则应用重启后该 Uri 失效，
        // 用户会遇到「书还在但打不开」这种最令人困惑的问题。
        documentStore.persistPermission(uri)

        try {
            val parsed: ParsedBook = when (format) {
                "txt" -> TxtParser.parse(documentStore.readBytes(uri), fileName)
                "epub" -> EpubParser.parse(documentStore.readBytes(uri), fileName)
                "pdf" -> buildPdfPlaceholder(fileName)
                else -> return@withContext ImportResult.Failure(fileName, "不支持的文件格式")
            }

            // 交给统一的落库入口：判重、封面落盘、事务写入都在那里，
            // 避免「从文件导入」和「从文本导入」两条路径行为不一致。
            persistParsedBook(
                parsed = parsed,
                format = when (format) {
                    "epub" -> BookFormat.EPUB
                    "pdf" -> BookFormat.PDF
                    else -> BookFormat.TXT
                },
                sourceUri = uri.toString(),
                sourceModified = documentStore.lastModified(uri),
            )
        } catch (e: Exception) {
            ImportResult.Failure(fileName, e.message ?: "解析失败")
        }
    }

    /**
     * 从纯文本导入（示例书、粘贴文本等）。
     *
     * 与从文件导入共用同一条落库路径（[persistParsedBook]），
     * 避免出现「示例书走特例逻辑」导致的隐性行为差异。
     */
    suspend fun importFromText(fileName: String, text: String, encoding: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                // 文本已经解码完成，因此直接走归一化 + 分章，
                // 不再经过 TxtParser 的字节探测（那会把已解码文本按 UTF-8 再判一次，多此一举）。
                val normalized = com.moyu.reader.reader.TextEncodingDetector.normalize(text)
                if (normalized.isBlank()) {
                    return@withContext ImportResult.Failure(fileName, "内容为空")
                }
                val chapters = com.moyu.reader.reader.ChapterSplitter.split(normalized)
                val parsed = ParsedBook(
                    title = fileName.substringBeforeLast('.').trim().ifEmpty { "未命名" },
                    author = "佚名",
                    intro = "",
                    encoding = encoding,
                    encodingUncertain = false,
                    chapters = chapters.map {
                        com.moyu.reader.parser.ParsedChapter(
                            index = it.index,
                            title = it.title,
                            content = it.content,
                            start = it.start,
                            length = it.length,
                            detected = it.detected,
                        )
                    },
                )
                persistParsedBook(
                    parsed = parsed,
                    format = BookFormat.TXT,
                    sourceUri = null,
                    sourceModified = 0L,
                )
            } catch (e: Exception) {
                ImportResult.Failure(fileName, e.message ?: "导入失败")
            }
        }

    /**
     * 把解析结果落库。
     *
     * 这是所有导入路径的**唯一**写入口，包含：判重、封面落盘、事务写入
     * （书 + 章节 + 初始位置）。集中在一处可以保证不会有某条路径漏掉判重或封面处理。
     */
    private suspend fun persistParsedBook(
        parsed: ParsedBook,
        format: BookFormat,
        sourceUri: String?,
        sourceModified: Long,
    ): ImportResult {
        val totalChars = parsed.chapters.sumOf { it.length }

        // 判重：标题 + 字数的组合。单看标题会误判同名不同书，
        // 单看字数会误判恰好等长的不同书，两者结合后误判率极低。
        val existing = bookDao.findByTitleAndLength(parsed.title, totalChars)
        if (existing != null) return ImportResult.Duplicate(existing.title)

        val bookId = "b_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()

        // 封面写入应用私有目录：不把大图塞进数据库（会让查询变慢且备份体积暴涨）
        val coverPath = parsed.coverBytes?.let { bytes ->
            runCatching {
                val file = documentStore.coverFile(bookId)
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }

        val book = Book(
            id = bookId,
            title = parsed.title,
            author = parsed.author,
            intro = parsed.intro,
            format = format,
            encoding = parsed.encoding,
            coverPath = coverPath,
            charCount = totalChars,
            chapterCount = parsed.chapters.size,
            groupId = null,
            status = ReadingStatus.UNREAD,
            addedAt = now,
            lastReadAt = now,
        )

        // 书 + 章节 + 初始位置必须原子写入，否则中途失败会留下
        // 「书在书架上但没有章节」的坏记录（点开会白屏）。
        database.withTransaction {
            bookDao.upsert(book.toEntity(sourceUri = sourceUri, sourceModified = sourceModified))
            chapterDao.deleteByBook(bookId)
            chapterDao.insertAll(
                parsed.chapters.map { chapter ->
                    com.moyu.reader.data.db.ChapterEntity(
                        bookId = bookId,
                        index = chapter.index,
                        title = chapter.title,
                        content = chapter.content,
                        start = chapter.start,
                        length = chapter.length,
                        detected = chapter.detected,
                    )
                }
            )
            positionDao.upsert(
                ReadingPositionEntity(
                    bookId = bookId,
                    chapterIndex = 0,
                    chapterOffset = 0,
                    globalOffset = 0,
                    pageIndex = 0,
                    percent = 0f,
                    updatedAt = now,
                )
            )
        }

        return ImportResult.Success(
            bookId = bookId,
            title = book.title,
            chapterCount = book.chapterCount,
            charCount = book.charCount,
            encodingUncertain = parsed.encodingUncertain,
            encoding = parsed.encoding,
            warnings = parsed.warnings,
        )
    }

    /**
     * PDF 只建立书库记录。
     *
     * 刻意**不假装**提取了 PDF 文本：PDF 没有「重排」概念，
     * 强行抽文字会得到错乱的版面。原生端把 PDF 作为整页图片渲染，
     * 因此这里只存一个说明性章节，并在界面上如实告知用户。
     */
    private fun buildPdfPlaceholder(fileName: String): ParsedBook {
        val title = fileName.substringBeforeLast('.').trim().ifEmpty { "未命名文档" }
        val note = buildString {
            append("［PDF 文档］\n\n")
            append("墨阅把 PDF 作为整页渲染，不提取其中的文字。\n")
            append("因此这个条目保存的是书库记录与阅读位置；")
            append("如需按字号重排阅读，请导入该书的 TXT 或 EPUB 版本。")
        }
        return ParsedBook(
            title = title,
            author = "佚名",
            intro = "",
            encoding = "UTF-8",
            encodingUncertain = false,
            chapters = listOf(
                com.moyu.reader.parser.ParsedChapter(
                    index = 0,
                    title = title,
                    content = note,
                    start = 0,
                    length = note.length,
                    detected = false,
                )
            ),
            warnings = listOf("PDF 不参与重排，仅保存书库记录"),
        )
    }

    /** 从已导入书籍重新解析（用户切换编码后重新导入时使用）。 */
    suspend fun reimportWithEncoding(uri: Uri, encoding: String): ImportResult = withContext(Dispatchers.IO) {
        val fileName = documentStore.displayName(uri)
        try {
            val bytes = documentStore.readBytes(uri)
            val decoded = com.moyu.reader.reader.TextEncodingDetector.decodeAs(bytes, encoding)
            val text = com.moyu.reader.reader.TextEncodingDetector.normalize(decoded.text)
            val chapters = com.moyu.reader.reader.ChapterSplitter.split(text)
            ImportResult.Success(
                bookId = "",
                title = fileName.substringBeforeLast('.'),
                chapterCount = chapters.size,
                charCount = text.length,
                encodingUncertain = decoded.uncertain,
                encoding = encoding,
                warnings = if (decoded.uncertain) listOf("该编码下仍有无法识别的字符") else emptyList(),
            )
        } catch (e: Exception) {
            ImportResult.Failure(fileName, e.message ?: "重新解码失败")
        }
    }

    companion object {
        /**
         * 相对时间文案。
         * 与 Web 验证器 store.ts 的 formatRelativeTime 口径一致。
         */
        fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
            val diff = now - timestamp
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000} 分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
                diff < 2 * 86_400_000L -> "昨天"
                diff < 30 * 86_400_000L -> "${diff / 86_400_000} 天前"
                diff < 365 * 86_400_000L -> "${diff / (30 * 86_400_000L)} 个月前"
                else -> "${diff / (365 * 86_400_000L)} 年前"
            }
        }
    }
}
