package com.moyu.reader.data.repository

import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.model.BookGroup
import com.moyu.reader.data.model.Bookmark
import com.moyu.reader.data.model.Highlight
import com.moyu.reader.data.toEntity
import com.moyu.reader.data.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 书签与笔记仓储。
 *
 * 书签与划线合成一个仓库而不是拆两个：它们在使用上是同一件事
 * （「记录我在这一页留下的东西」），界面上也共用同一个列表与跳转逻辑。
 * 拆开只会让界面层不得不同时依赖两个仓储、写两套几乎相同的代码。
 */
class NoteRepository(private val database: MoyuDatabase) {

    private val bookmarkDao get() = database.bookmarkDao()
    private val highlightDao get() = database.highlightDao()

    val allBookmarks: Flow<List<Bookmark>> =
        bookmarkDao.observeAll().map { list -> list.map { it.toModel() } }

    val allHighlights: Flow<List<Highlight>> =
        highlightDao.observeAll().map { list -> list.map { it.toModel() } }

    fun bookmarksOf(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao.observeByBook(bookId).map { list -> list.map { it.toModel() } }

    fun highlightsOf(bookId: String): Flow<List<Highlight>> =
        highlightDao.observeByBook(bookId).map { list -> list.map { it.toModel() } }

    suspend fun getBookmarks(bookId: String): List<Bookmark> = withContext(Dispatchers.IO) {
        bookmarkDao.getByBook(bookId).map { it.toModel() }
    }

    suspend fun getHighlights(bookId: String): List<Highlight> = withContext(Dispatchers.IO) {
        highlightDao.getByBook(bookId).map { it.toModel() }
    }

    suspend fun getAllBookmarks(): List<Bookmark> = withContext(Dispatchers.IO) {
        bookmarkDao.getAll().map { it.toModel() }
    }

    suspend fun getAllHighlights(): List<Highlight> = withContext(Dispatchers.IO) {
        highlightDao.getAll().map { it.toModel() }
    }

    suspend fun addBookmark(
        bookId: String,
        chapterIndex: Int,
        chapterOffset: Int,
        globalOffset: Int,
        excerpt: String,
        note: String = "",
    ): Bookmark = withContext(Dispatchers.IO) {
        val bookmark = Bookmark(
            id = "bm_" + UUID.randomUUID().toString().replace("-", "").take(16),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterOffset = chapterOffset,
            globalOffset = globalOffset,
            excerpt = excerpt.take(120),
            note = note,
            createdAt = System.currentTimeMillis(),
        )
        bookmarkDao.upsert(bookmark.toEntity())
        bookmark
    }

    suspend fun addHighlight(
        bookId: String,
        chapterIndex: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        color: String,
        note: String = "",
    ): Highlight = withContext(Dispatchers.IO) {
        val highlight = Highlight(
            id = "hl_" + UUID.randomUUID().toString().replace("-", "").take(16),
            bookId = bookId,
            chapterIndex = chapterIndex,
            startOffset = startOffset,
            endOffset = endOffset,
            text = text.take(800),
            color = color,
            note = note,
            createdAt = System.currentTimeMillis(),
        )
        highlightDao.upsert(highlight.toEntity())
        highlight
    }

    suspend fun updateBookmarkNote(id: String, note: String) = withContext(Dispatchers.IO) {
        bookmarkDao.updateNote(id, note)
    }

    suspend fun updateHighlightNote(id: String, note: String) = withContext(Dispatchers.IO) {
        highlightDao.updateNote(id, note)
    }

    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteById(id)
    }

    suspend fun removeHighlight(id: String) = withContext(Dispatchers.IO) {
        highlightDao.deleteById(id)
    }
}

/**
 * 分组仓储。
 *
 * 删除分组时必须把组内书籍的 group_id 清空，否则会留下指向不存在分组的悬空引用 ——
 * 那种数据在界面上表现为「书籍消失」：因为它属于一个筛选不出来的分组。
 */
class GroupRepository(private val database: MoyuDatabase) {

    private val groupDao get() = database.bookGroupDao()

    val groups: Flow<List<BookGroup>> =
        groupDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun create(name: String, color: String): BookGroup = withContext(Dispatchers.IO) {
        val group = BookGroup(
            id = "g_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim(),
            color = color,
            createdAt = System.currentTimeMillis(),
        )
        groupDao.upsert(group.toEntity())
        group
    }

    suspend fun rename(id: String, name: String, color: String) = withContext(Dispatchers.IO) {
        groupDao.upsert(
            com.moyu.reader.data.db.BookGroupEntity(
                id = id,
                name = name.trim(),
                color = color,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        // 先清空引用再删分组，顺序不能反：反了会有一瞬间的悬空引用
        groupDao.clearGroupOnBooks(id)
        groupDao.deleteById(id)
    }
}
