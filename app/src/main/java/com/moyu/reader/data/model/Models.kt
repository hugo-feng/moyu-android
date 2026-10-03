package com.moyu.reader.data.model

/**
 * UI 层使用的领域模型。
 *
 * 为什么要与 Room 实体分开，而不是直接把 Entity 传给 Compose：
 *   1. 实体里的字段是「存储视角」（source_uri、source_modified 等），
 *      界面并不需要也不该知道；混在一起会让 UI 参数列表越来越脏；
 *   2. 把 content 从章节里拆出去（见 [ChapterHeader]）能防止 UI 层
 *      不小心把整本书的正文都读进内存 —— 这是大文件下最常见的性能事故；
 *   3. 实体字段变更时不会连锁影响所有 Compose 函数签名。
 */

enum class BookFormat { TXT, EPUB, PDF }

/**
 * 格式的中文名，给界面显示用。
 *
 * 界面上一律用中文：封面上那个角标、详情页的「文本 · 3 万字 · 12 章」
 * 都是用户直接看到的字，不该出现英文缩写。
 * 枚举名本身保持英文 —— 它是要入库的稳定标识，不能为了显示去改。
 */
val BookFormat.displayName: String
    get() = when (this) {
        BookFormat.TXT -> "文本"
        BookFormat.EPUB -> "电子书"
        BookFormat.PDF -> "PDF 文档"
    }

enum class ReadingStatus { UNREAD, READING, FINISHED }

/** 书架卡片所需的书籍信息（不含正文）。 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val intro: String,
    val format: BookFormat,
    val encoding: String,
    val coverPath: String?,
    val charCount: Int,
    val chapterCount: Int,
    val groupId: String?,
    val status: ReadingStatus,
    val addedAt: Long,
    val lastReadAt: Long,
    /**
     * 是否已加入书架。
     *
     * 导入的书全部落在**书库**；只有用户主动「加入书架」后才会出现在书架栏。
     * 默认 false —— 新导入的书不该自动占据书架。
     */
    val inShelf: Boolean = false,
    /** 分章结果所用的规则版本；落后于当前版本时会在打开时重新分章。 */
    val splitVersion: Int = 0,
)

/**
 * 章节**头信息**（不含正文）。
 *
 * 章节列表、目录、搜索的章节分组都只需要标题与偏移，
 * 用这个类型可以从类型层面保证不会误加载正文。
 */
data class ChapterHeader(
    val index: Int,
    val title: String,
    val start: Int,
    val length: Int,
    val detected: Boolean,
)

/** 章节（含正文），仅在阅读器需要渲染某一章时使用。 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String,
    val start: Int,
    val length: Int,
    val detected: Boolean,
)

/**
 * 阅读位置。
 *
 * [globalOffset] 是全书字符偏移，作为进度/书签/搜索的统一坐标系；
 * [chapterOffset] 用于快速定位到章内具体位置，避免每次都要二分查找。
 */
data class ReadingPosition(
    val chapterIndex: Int,
    val chapterOffset: Int,
    val globalOffset: Int,
    val pageIndex: Int,
    val percent: Float,
    val updatedAt: Long,
)

data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterOffset: Int,
    val globalOffset: Int,
    val excerpt: String,
    val note: String,
    val createdAt: Long,
)

data class Highlight(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: String,
    val note: String,
    val createdAt: Long,
)

data class BookGroup(
    val id: String,
    val name: String,
    val color: String,
    val createdAt: Long,
)

data class ReadingSession(
    val id: String,
    val bookId: String,
    val startedAt: Long,
    val durationSec: Int,
    val charCount: Int,
)

/**
 * 书架上的一张卡片所需的全部派生数据。
 *
 * 把「进度百分比 / 是否已读完 / 相对时间文案」在仓储层算好，
 * 而不是让每个 Composable 各自算一遍：这类派生逻辑一旦分散，
 * 很容易出现书架上显示 99%、详情页显示 100% 这种不一致。
 */
data class ShelfItem(
    val book: Book,
    val percent: Float,
    val finished: Boolean,
    val unread: Boolean,
    val lastReadLabel: String,
)

/** 导入结果，用于向用户报告成功/失败原因。 */
sealed interface ImportResult {
    data class Success(
        val bookId: String,
        val title: String,
        val chapterCount: Int,
        val charCount: Int,
        /** 编码识别不确定时为 true，界面应提示可手动切换 */
        val encodingUncertain: Boolean,
        val encoding: String,
        /** 非致命告警（例如「未找到目录，已按文件顺序生成章节」） */
        val warnings: List<String>,
    ) : ImportResult

    data class Duplicate(val title: String) : ImportResult

    data class Failure(val fileName: String, val message: String) : ImportResult
}
