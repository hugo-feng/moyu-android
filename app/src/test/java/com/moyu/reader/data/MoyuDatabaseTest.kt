package com.moyu.reader.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.moyu.reader.data.db.BookEntity
import com.moyu.reader.data.db.BookFormat
import com.moyu.reader.data.db.BookGroupEntity
import com.moyu.reader.data.db.BookmarkEntity
import com.moyu.reader.data.db.ChapterEntity
import com.moyu.reader.data.db.HighlightEntity
import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.db.ReadingPositionEntity
import com.moyu.reader.data.db.ReadingSessionEntity
import com.moyu.reader.data.db.ReadingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 Room 数据库的测试 —— 用 Robolectric 跑在 JVM 上，无需模拟器/真机。
 *
 * 数据库是真正的 SQLite（Robolectric 提供 native 实现），因此外键 CASCADE、
 * `PRAGMA foreign_keys = ON`、以及 KSP 生成的 DAO 实现都是真跑的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoyuDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: MoyuDatabase

    private val bookDao get() = db.bookDao()
    private val chapterDao get() = db.chapterDao()
    private val positionDao get() = db.readingPositionDao()
    private val bookmarkDao get() = db.bookmarkDao()
    private val highlightDao get() = db.highlightDao()
    private val sessionDao get() = db.readingSessionDao()
    private val groupDao get() = db.bookGroupDao()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MoyuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(
        id: String,
        title: String = "书$id",
        charCount: Int = 100,
        groupId: String? = null,
    ) = BookEntity(
        id = id,
        title = title,
        author = "佚名",
        intro = "",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        coverPath = null,
        charCount = charCount,
        chapterCount = 1,
        sourceLength = charCount,
        groupId = groupId,
        status = ReadingStatus.UNREAD,
        sourceUri = null,
        sourceModified = 0L,
        addedAt = 1L,
        lastReadAt = 1L,
    )

    private fun chapter(bookId: String, index: Int, title: String, content: String, start: Int) =
        ChapterEntity(
            bookId = bookId,
            index = index,
            title = title,
            content = content,
            start = start,
            length = content.length,
            detected = true,
        )

    private fun position(bookId: String) = ReadingPositionEntity(
        bookId = bookId,
        chapterIndex = 0,
        chapterOffset = 0,
        globalOffset = 0,
        pageIndex = 0,
        percent = 0f,
        updatedAt = 1L,
    )

    // ------------------------------------------------------- 事务原子性

    @Test
    fun `book plus chapters plus position commit together in one transaction`() = runBlocking {
        db.withTransaction {
            bookDao.upsert(book("b1"))
            chapterDao.insertAll(
                listOf(
                    chapter("b1", 0, "第一章", "正文一", 0),
                    chapter("b1", 1, "第二章", "正文二", 4),
                )
            )
            positionDao.upsert(position("b1"))
        }

        assertEquals(1, bookDao.count())
        assertEquals(2, chapterDao.countByBook("b1"))
        assertNotNull(positionDao.find("b1"))
    }

    @Test
    fun `a failure inside withTransaction rolls back every table`() = runBlocking {
        db.withTransaction {
            bookDao.upsert(book("ok"))
            chapterDao.insertAll(listOf(chapter("ok", 0, "第一章", "正文", 0)))
            positionDao.upsert(position("ok"))
        }
        assertEquals(1, bookDao.count())

        val thrown = runCatching {
            db.withTransaction {
                bookDao.upsert(book("b2"))
                chapterDao.insertAll(listOf(chapter("b2", 0, "第一章", "正文", 0)))
                positionDao.upsert(position("b2"))
                throw IllegalStateException("boom")
            }
        }

        assertTrue("withTransaction must rethrow", thrown.isFailure)
        assertEquals("boom", thrown.exceptionOrNull()?.message)
        assertNull("book must have been rolled back", bookDao.findById("b2"))
        assertEquals("chapters must have been rolled back", 0, chapterDao.countByBook("b2"))
        assertNull("position must have been rolled back", positionDao.find("b2"))
        assertEquals("the earlier committed book must survive", 1, bookDao.count())
    }

    // ------------------------------------------------------- 级联删除

    @Test
    fun `deleting a book cascades to chapters bookmarks highlights and sessions`() = runBlocking {
        bookDao.upsert(book("b1"))
        chapterDao.insertAll(
            listOf(
                chapter("b1", 0, "第一章", "正文一", 0),
                chapter("b1", 1, "第二章", "正文二", 4),
            )
        )
        bookmarkDao.upsert(BookmarkEntity("bm1", "b1", 0, 0, 0, "摘录", "", 1L))
        highlightDao.upsert(HighlightEntity("h1", "b1", 0, 0, 2, "正文", "#FF0000", "", 1L))
        sessionDao.insert(ReadingSessionEntity("s1", "b1", 1L, 600, 100))

        assertEquals(2, chapterDao.countByBook("b1"))
        assertEquals(1, bookmarkDao.getByBook("b1").size)
        assertEquals(1, highlightDao.getByBook("b1").size)
        assertEquals(1, sessionDao.getAll().size)

        bookDao.deleteById("b1")

        assertNull(bookDao.findById("b1"))
        assertEquals("chapters must cascade", 0, chapterDao.countByBook("b1"))
        assertTrue("bookmarks must cascade", bookmarkDao.getByBook("b1").isEmpty())
        assertTrue("highlights must cascade", highlightDao.getByBook("b1").isEmpty())
        assertTrue("sessions must cascade", sessionDao.getAll().isEmpty())
        assertTrue("no bookmark rows at all", bookmarkDao.getAll().isEmpty())
        assertTrue("no highlight rows at all", highlightDao.getAll().isEmpty())
    }

    @Test
    fun `deleting one book leaves another book's children untouched`() = runBlocking {
        bookDao.upsert(book("b1"))
        bookDao.upsert(book("b2"))
        chapterDao.insertAll(
            listOf(
                chapter("b1", 0, "第一章", "正文一", 0),
                chapter("b2", 0, "第一章", "正文一", 0),
            )
        )
        bookmarkDao.upsert(BookmarkEntity("bm1", "b1", 0, 0, 0, "a", "", 1L))
        bookmarkDao.upsert(BookmarkEntity("bm2", "b2", 0, 0, 0, "b", "", 1L))

        bookDao.deleteById("b1")

        assertEquals(0, chapterDao.countByBook("b1"))
        assertEquals(1, chapterDao.countByBook("b2"))
        assertTrue(bookmarkDao.getByBook("b1").isEmpty())
        assertEquals(1, bookmarkDao.getByBook("b2").size)
    }

    /**
     * 回归测试：删除书籍必须把它的阅读位置一起删掉。
     *
     * 这曾经是一个真实缺陷：`reading_positions` 实体漏声明了外键，
     * 于是删书之后会留下一条孤儿行（其余四张子表都正常级联）。
     * 孤儿行不只是脏数据 —— 同一本书被重新导入时，它会被当成
     * 「上次读到这儿」，让刚导入的新书一打开就跳到旧位置。
     */
    @Test
    fun `deleting a book cascades to its reading position`() = runBlocking {
        bookDao.upsert(book("b1"))
        bookDao.upsert(book("b2"))
        positionDao.upsert(position("b1"))
        positionDao.upsert(position("b2"))

        bookDao.deleteById("b1")

        assertNull(bookDao.findById("b1"))
        assertNull("reading position must be deleted with its book", positionDao.find("b1"))
        assertNotNull("the other book's position must be untouched", positionDao.find("b2"))
    }

    // ------------------------------------------------------- 判重

    @Test
    fun `findByTitleAndLength dedups on the title plus char count pair`() = runBlocking {
        bookDao.upsert(book("b1", title = "测试书", charCount = 1234))
        bookDao.upsert(book("b2", title = "测试书", charCount = 4321))
        bookDao.upsert(book("b3", title = "另一本书", charCount = 1234))

        assertEquals("b1", bookDao.findByTitleAndLength("测试书", 1234)?.id)
        assertEquals("b2", bookDao.findByTitleAndLength("测试书", 4321)?.id)
        assertNull("same title, different length is not a dup", bookDao.findByTitleAndLength("测试书", 999))
        assertNull("different title, same length is not a dup", bookDao.findByTitleAndLength("另一本书", 999))
        assertNull(bookDao.findByTitleAndLength("不存在的书", 1234))
    }

    // ------------------------------------------------------- 章节表头内存安全

    @Test
    fun `getChapterHeaders returns titles and offsets but NEVER content`() = runBlocking {
        bookDao.upsert(book("b1"))
        chapterDao.insertAll(
            listOf(
                ChapterEntity("b1", 0, "第一章", "第一章的正文内容甲", 0, 9, true),
                ChapterEntity("b1", 1, "第二章", "第二章的正文内容乙", 9, 9, true),
                ChapterEntity("b1", 2, "第三章", "第三章的正文内容丙", 18, 9, false),
            )
        )

        val headers = chapterDao.getChapterHeaders("b1")

        assertEquals(3, headers.size)
        assertEquals(listOf("第一章", "第二章", "第三章"), headers.map { it.title })
        assertEquals(listOf(0, 9, 18), headers.map { it.start })
        assertEquals(listOf(9, 9, 9), headers.map { it.length })
        assertEquals(listOf(true, true, false), headers.map { it.detected })
        headers.forEach { row ->
            assertEquals(
                "content must be the empty-string placeholder (memory-safety contract)",
                "",
                row.content,
            )
            assertTrue(row.content.isEmpty())
        }
        assertEquals(listOf(0, 1, 2), headers.map { it.index })

        // 对照：真正的单章查询会带回正文
        assertEquals("第一章的正文内容甲", chapterDao.getChapter("b1", 0)?.content)
    }

    // ------------------------------------------------------- 分组清理

    @Test
    fun `clearGroupOnBooks nulls group_id only for the requested group`() = runBlocking {
        groupDao.upsert(BookGroupEntity("g1", "组1", "#FF0000", 1L))
        groupDao.upsert(BookGroupEntity("g2", "组2", "#00FF00", 1L))
        bookDao.upsert(book("b1", groupId = "g1"))
        bookDao.upsert(book("b2", groupId = "g2"))
        bookDao.upsert(book("b3", groupId = null))

        groupDao.clearGroupOnBooks("g1")

        assertNull("b1 must be un-grouped", bookDao.findById("b1")?.groupId)
        assertEquals("g2", bookDao.findById("b2")?.groupId)
        assertNull(bookDao.findById("b3")?.groupId)
    }

    @Test
    fun `deleteGroup clears the reference then removes the group`() = runBlocking {
        groupDao.upsert(BookGroupEntity("g1", "组1", "#FF0000", 1L))
        bookDao.upsert(book("b1", groupId = "g1"))

        db.transactionDao().deleteGroup("g1", groupDao)

        assertNull(bookDao.findById("b1")?.groupId)
        assertTrue(groupDao.getAll().isEmpty())
    }

    // ------------------------------------------------------- 其它 DAO 行为

    @Test
    fun `upsert replaces an existing row instead of duplicating it`() = runBlocking {
        bookDao.upsert(book("b1", title = "旧名"))
        bookDao.upsert(book("b1", title = "新名"))
        assertEquals(1, bookDao.count())
        assertEquals("新名", bookDao.findById("b1")?.title)

        chapterDao.insertAll(listOf(chapter("b1", 0, "第一章", "旧正文", 0)))
        chapterDao.insertAll(listOf(chapter("b1", 0, "第一章", "新正文", 0)))
        assertEquals(1, chapterDao.countByBook("b1"))
        assertEquals("新正文", chapterDao.getChapter("b1", 0)?.content)
    }

    @Test
    fun `chapters are returned ordered by index`() = runBlocking {
        bookDao.upsert(book("b1"))
        chapterDao.insertAll(
            listOf(
                chapter("b1", 2, "第三章", "丙", 8),
                chapter("b1", 0, "第一章", "甲", 0),
                chapter("b1", 1, "第二章", "乙", 4),
            )
        )
        assertEquals(listOf(0, 1, 2), chapterDao.getByBook("b1").map { it.index })
        assertEquals(listOf(0, 1, 2), chapterDao.getChapterHeaders("b1").map { it.index })
    }

    @Test
    fun `sessions can be filtered by started_at`() = runBlocking {
        bookDao.upsert(book("b1"))
        sessionDao.insert(ReadingSessionEntity("s1", "b1", 100L, 60, 10))
        sessionDao.insert(ReadingSessionEntity("s2", "b1", 500L, 60, 10))
        sessionDao.insert(ReadingSessionEntity("s3", "b1", 900L, 60, 10))

        assertEquals(listOf("s2", "s3"), sessionDao.getSince(500L).map { it.id })
        assertEquals(3, sessionDao.getAll().size)
        sessionDao.clearAll()
        assertTrue(sessionDao.getAll().isEmpty())
    }
}
