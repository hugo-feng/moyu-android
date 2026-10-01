package com.moyu.reader.data

import android.content.Context
import androidx.room.Room
import com.moyu.reader.data.db.BookFormat
import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.storage.DocumentStore
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
 * BookRepository.importFromText 的端到端测试（Robolectric + 真实 Room 内存库）。
 *
 * 覆盖：落库结果（书名/章节数/字数）、章节偏移、初始阅读位置、
 * 以及「同一本书导入两次返回 Duplicate」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookRepositoryImportTest {

    private lateinit var context: Context
    private lateinit var db: MoyuDatabase
    private lateinit var repo: BookRepository

    /** 共 24 个字符：6 + \n + 4 + \n + 6 + \n + 4 + \n */
    private val text = "第一章 开始\n正文甲。\n第二章 继续\n正文乙。\n"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MoyuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BookRepository(context, db, DocumentStore(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun importResult(fileName: String, content: String): ImportResult =
        runBlocking { repo.importFromText(fileName, content, "UTF-8") }

    private fun success(result: ImportResult): ImportResult.Success {
        assertTrue("expected Success but was $result", result is ImportResult.Success)
        return result as ImportResult.Success
    }

    private fun duplicate(result: ImportResult): ImportResult.Duplicate {
        assertTrue("expected Duplicate but was $result", result is ImportResult.Duplicate)
        return result as ImportResult.Duplicate
    }

    private fun failure(result: ImportResult): ImportResult.Failure {
        assertTrue("expected Failure but was $result", result is ImportResult.Failure)
        return result as ImportResult.Failure
    }

    @Test
    fun `importFromText persists the book chapters and the initial reading position`() = runBlocking {
        val s = success(repo.importFromText("测试书.txt", text, "UTF-8"))

        assertEquals("测试书", s.title)
        assertEquals(2, s.chapterCount)
        assertEquals(24, s.charCount)
        assertEquals("UTF-8", s.encoding)

        val book = db.bookDao().findById(s.bookId)
        assertNotNull("book row must exist", book)
        val b = book!!
        assertEquals("测试书", b.title)
        assertEquals("佚名", b.author)
        assertEquals(2, b.chapterCount)
        assertEquals(24, b.charCount)
        assertEquals(24, b.sourceLength)
        assertEquals(BookFormat.TXT, b.format)
        assertEquals("UTF-8", b.encoding)

        val chapters = db.chapterDao().getByBook(s.bookId)
        assertEquals(2, chapters.size)
        assertEquals(listOf("第一章 开始", "第二章 继续"), chapters.map { it.title })
        assertEquals(listOf(0, 12), chapters.map { it.start })
        assertEquals(listOf(12, 12), chapters.map { it.length })
        assertTrue("both chapters come from real markers", chapters.all { it.detected })
        // 章节内容在原始文档里逐字可还原
        assertEquals(text, chapters.joinToString("") { it.content })
        chapters.forEach { assertEquals(it.content, text.substring(it.start, it.start + it.length)) }

        val pos = db.readingPositionDao().find(s.bookId)
        assertNotNull("initial reading position must exist", pos)
        val p = pos!!
        assertEquals(0, p.chapterIndex)
        assertEquals(0, p.chapterOffset)
        assertEquals(0, p.globalOffset)
        assertEquals(0, p.pageIndex)
        assertEquals(0f, p.percent, 0f)
    }

    @Test
    fun `a second identical import returns Duplicate and does not add a row`() = runBlocking {
        val first = success(repo.importFromText("测试书.txt", text, "UTF-8"))
        assertEquals(1, db.bookDao().count())

        val dup = duplicate(repo.importFromText("测试书.txt", text, "UTF-8"))
        assertEquals("测试书", dup.title)
        assertEquals("no second row may be inserted", 1, db.bookDao().count())
        assertEquals(2, db.chapterDao().getByBook(first.bookId).size)
        val pos = db.readingPositionDao().find(first.bookId)
        assertNotNull("position row must still exist", pos)
        assertEquals(0, pos!!.globalOffset)
    }

    @Test
    fun `changing the text length is enough to bypass dedup`() = runBlocking {
        success(repo.importFromText("测试书.txt", text, "UTF-8"))

        val longer = text + "第三章 又一段\n更多正文。\n"
        val second = success(repo.importFromText("测试书.txt", longer, "UTF-8"))
        assertEquals("different charCount must not be a dup", 3, second.chapterCount)
        assertEquals(2, db.bookDao().count())
    }

    @Test
    fun `CRLF input is normalised before chapter counting`() = runBlocking {
        val crlf = text.replace("\n", "\r\n")
        assertEquals(28, crlf.length)

        val s = success(repo.importFromText("换行测试.txt", crlf, "UTF-8"))
        assertEquals("换行测试", s.title)
        assertEquals(2, s.chapterCount)
        // normalize() 去掉 4 个 \r，字数比原始 CRLF 文本少 4
        assertEquals(24, s.charCount)
        assertEquals(crlf.length - 4, s.charCount)
    }

    @Test
    fun `blank text is rejected with a Failure`() = runBlocking {
        val f = failure(repo.importFromText("空.txt", "   \n\n  \t ", "UTF-8"))
        assertEquals("空.txt", f.fileName)
        assertEquals("内容为空", f.message)
        assertEquals(0, db.bookDao().count())
    }

    @Test
    fun `repository chapter headers and single-chapter reads`() = runBlocking {
        val s = success(repo.importFromText("测试书.txt", text, "UTF-8"))

        val headers = repo.getChapterHeaders(s.bookId)
        assertEquals(2, headers.size)
        assertEquals(listOf("第一章 开始", "第二章 继续"), headers.map { it.title })
        assertEquals(listOf(0, 12), headers.map { it.start })

        val position = repo.getPosition(s.bookId)
        assertNotNull(position)
        assertEquals(0, position!!.globalOffset)
        assertEquals(0, position.chapterIndex)

        val first = repo.getChapter(s.bookId, 0)
        assertNotNull(first)
        assertEquals("第一章 开始", first!!.title)
        assertEquals(text.substring(0, 12), first.content)
        assertNull("out-of-range chapter index must return null", repo.getChapter(s.bookId, 99))
    }

    @Test
    fun `importFromText works through the helper as well`() {
        val s = success(importResult("助手.txt", text))
        assertEquals("助手", s.title)
        assertEquals(2, s.chapterCount)
        assertEquals(1, runBlocking { db.bookDao().count() })
    }
}
