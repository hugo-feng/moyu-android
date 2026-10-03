package com.moyu.reader.data

import com.moyu.reader.data.db.BookEntity
import com.moyu.reader.data.db.BookFormat as DbBookFormat
import com.moyu.reader.data.db.BookGroupEntity
import com.moyu.reader.data.db.BookmarkEntity
import com.moyu.reader.data.db.ChapterEntity
import com.moyu.reader.data.db.HighlightEntity
import com.moyu.reader.data.db.ReadingPositionEntity
import com.moyu.reader.data.db.ReadingSessionEntity
import com.moyu.reader.data.db.ReadingStatus as DbReadingStatus
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.model.BookGroup
import com.moyu.reader.data.model.Bookmark
import com.moyu.reader.data.model.Chapter
import com.moyu.reader.data.model.ChapterHeader
import com.moyu.reader.data.model.Highlight
import com.moyu.reader.data.model.ReadingPosition
import com.moyu.reader.data.model.ReadingSession
import com.moyu.reader.data.model.ReadingStatus

/**
 * 实体 ↔ 领域模型的映射。
 *
 * 集中放在一处而不是散在各个仓库里：映射是「纯函数、无副作用」的，
 * 集中后可以一眼看全两端字段的对应关系，也便于将来加数据库迁移时核对。
 */

fun BookEntity.toModel(): Book = Book(
    id = id,
    title = title,
    author = author,
    intro = intro,
    format = when (format) {
        DbBookFormat.TXT -> BookFormat.TXT
        DbBookFormat.EPUB -> BookFormat.EPUB
        DbBookFormat.PDF -> BookFormat.PDF
    },
    encoding = encoding,
    coverPath = coverPath,
    charCount = charCount,
    chapterCount = chapterCount,
    groupId = groupId,
    status = when (status) {
        DbReadingStatus.UNREAD -> ReadingStatus.UNREAD
        DbReadingStatus.READING -> ReadingStatus.READING
        DbReadingStatus.FINISHED -> ReadingStatus.FINISHED
    },
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    inShelf = inShelf,
    splitVersion = splitVersion,
)

fun Book.toEntity(sourceUri: String? = null, sourceModified: Long = 0L): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    intro = intro,
    format = when (format) {
        BookFormat.TXT -> DbBookFormat.TXT
        BookFormat.EPUB -> DbBookFormat.EPUB
        BookFormat.PDF -> DbBookFormat.PDF
    },
    encoding = encoding,
    coverPath = coverPath,
    charCount = charCount,
    chapterCount = chapterCount,
    sourceLength = charCount,
    groupId = groupId,
    status = when (status) {
        ReadingStatus.UNREAD -> DbReadingStatus.UNREAD
        ReadingStatus.READING -> DbReadingStatus.READING
        ReadingStatus.FINISHED -> DbReadingStatus.FINISHED
    },
    sourceUri = sourceUri,
    sourceModified = sourceModified,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    inShelf = inShelf,
    splitVersion = splitVersion,
)

fun ChapterEntity.toHeader(): ChapterHeader = ChapterHeader(
    index = index,
    title = title,
    start = start,
    length = length,
    detected = detected,
)

fun ChapterEntity.toModel(): Chapter = Chapter(
    index = index,
    title = title,
    content = content,
    start = start,
    length = length,
    detected = detected,
)

fun ReadingPositionEntity.toModel(): ReadingPosition = ReadingPosition(
    chapterIndex = chapterIndex,
    chapterOffset = chapterOffset,
    globalOffset = globalOffset,
    pageIndex = pageIndex,
    percent = percent,
    updatedAt = updatedAt,
)

fun ReadingPosition.toEntity(bookId: String): ReadingPositionEntity = ReadingPositionEntity(
    bookId = bookId,
    chapterIndex = chapterIndex,
    chapterOffset = chapterOffset,
    globalOffset = globalOffset,
    pageIndex = pageIndex,
    percent = percent,
    updatedAt = updatedAt,
)

fun BookmarkEntity.toModel(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    chapterOffset = chapterOffset,
    globalOffset = globalOffset,
    excerpt = excerpt,
    note = note,
    createdAt = createdAt,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    chapterOffset = chapterOffset,
    globalOffset = globalOffset,
    excerpt = excerpt,
    note = note,
    createdAt = createdAt,
)

fun HighlightEntity.toModel(): Highlight = Highlight(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    startOffset = startOffset,
    endOffset = endOffset,
    text = text,
    color = color,
    note = note,
    createdAt = createdAt,
)

fun Highlight.toEntity(): HighlightEntity = HighlightEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    startOffset = startOffset,
    endOffset = endOffset,
    text = text,
    color = color,
    note = note,
    createdAt = createdAt,
)

fun ReadingSessionEntity.toModel(): ReadingSession = ReadingSession(
    id = id,
    bookId = bookId,
    startedAt = startedAt,
    durationSec = durationSec,
    charCount = charCount,
)

fun ReadingSession.toEntity(): ReadingSessionEntity = ReadingSessionEntity(
    id = id,
    bookId = bookId,
    startedAt = startedAt,
    durationSec = durationSec,
    charCount = charCount,
)

fun BookGroupEntity.toModel(): BookGroup = BookGroup(
    id = id,
    name = name,
    color = color,
    createdAt = createdAt,
)

fun BookGroup.toEntity(): BookGroupEntity = BookGroupEntity(
    id = id,
    name = name,
    color = color,
    createdAt = createdAt,
)
