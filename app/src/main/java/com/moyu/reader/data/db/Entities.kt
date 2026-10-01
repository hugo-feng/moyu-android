package com.moyu.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体定义。
 *
 * 与 Web 验证器的领域模型（src/engine/types.ts）保持字段语义一一对应，
 * 便于两端算法行为互相校验。字段名用下划线（SQL 惯例），Kotlin 属性用驼峰。
 *
 * 一个关键设计：**正文不整本入库**。章节按章存表，每章记录 [ChapterEntity.start]
 * （该章在原始文档中的字符偏移），于是「全局字符偏移」成为进度、书签、笔记、
 * 搜索结果的统一坐标系 —— 这正是两端能共用同一套换算逻辑的原因。
 */

/** 书籍来源格式。PDF 只做整页渲染，不参与重排。 */
enum class BookFormat { TXT, EPUB, PDF }

/** 阅读状态。用枚举而非多个布尔字段，避免出现「已读且未读」这类非法组合。 */
enum class ReadingStatus { UNREAD, READING, FINISHED }

@Entity(
    tableName = "books",
    indices = [
        Index("last_read_at"),
        Index("added_at"),
        Index("group_id"),
        Index("title"),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val intro: String,
    val format: BookFormat,
    /** 文本编码名（UTF-8 / GB18030 / UTF-16LE …），仅 TXT 有意义 */
    val encoding: String,
    /** 封面本地文件路径（下载/生成的封面）；为空时用程序生成的渐变书脊封面 */
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    /** 全书字符数 */
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    /** 原始文档总长，用于进度换算与偏移校验 */
    @ColumnInfo(name = "source_length") val sourceLength: Int,
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    val status: ReadingStatus = ReadingStatus.UNREAD,
    /** SAF 持久化 URI（takePersistableUriPermission 之后可长期访问） */
    @ColumnInfo(name = "source_uri") val sourceUri: String? = null,
    /** 文件在 SAF 里的最后修改时间，用于「重新扫描时是否需重新导入」的判断 */
    @ColumnInfo(name = "source_modified") val sourceModified: Long = 0L,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long,
)

/**
 * 章节。
 *
 * 复合主键 (book_id, index)：既保证同一本书内序号唯一，
 * 又让「取某本书全部章节」成为一次索引前缀扫描（SQLite 对复合主键的前缀可走索引）。
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["book_id", "index"],
    indices = [Index("book_id")],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    val index: Int,
    val title: String,
    val content: String,
    /** 该章正文在原始文档中的起始字符偏移 */
    val start: Int,
    val length: Int,
    /** 是否由分章算法识别出的真实章节标题（false 表示是兜底切分的块） */
    val detected: Boolean,
)

/**
 * 阅读位置。
 *
 * 一章一条（主键 book_id），只保留最新位置：阅读器不需要位置历史，
 * 而每次翻页都写一条历史会产生大量无用数据。
 */
@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    /** 章内字符偏移 */
    @ColumnInfo(name = "chapter_offset") val chapterOffset: Int,
    /** 全书字符偏移（冗余保存，便于重新分章后仍能还原位置） */
    @ColumnInfo(name = "global_offset") val globalOffset: Int,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    /** 全书进度 0f..1f */
    val percent: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 书签。 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("book_id"), Index("created_at")],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "chapter_offset") val chapterOffset: Int,
    @ColumnInfo(name = "global_offset") val globalOffset: Int,
    /** 书签处的上下文摘录，便于在列表里辨认 */
    val excerpt: String,
    /** 用户备注 */
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 划线。endOffset 为章内右开区间。 */
@Entity(
    tableName = "highlights",
    indices = [Index("book_id"), Index("created_at")],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
    val text: String,
    /** 划线颜色（ARGB 的十六进制字符串） */
    val color: String,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 阅读会话，用于统计。
 *
 * 记录每次连续阅读的时长与字数，是「每日时长 / 周报 / 热力图」的原始数据。
 * 聚合口径（本地时区归属、单次会话 4 小时截断）在 ReadingStatsCalculator 里统一实现。
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("book_id"), Index("started_at")],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** 持续秒数 */
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    /** 本次会话阅读的字符数 */
    @ColumnInfo(name = "char_count") val charCount: Int,
)

/** 书籍分组（书架分类）。 */
@Entity(tableName = "book_groups")
data class BookGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 分组标签色（ARGB 十六进制） */
    val color: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 用户自定义词典（导入的 txt 词典）。 */
@Entity(tableName = "user_dictionaries")
data class UserDictionaryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 序列化后的词条 JSON，避免为词典单独建表（查询走内存） */
    @ColumnInfo(name = "entries_json") val entriesJson: String,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)
