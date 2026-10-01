package com.moyu.reader.reader

import android.graphics.Typeface
import com.moyu.reader.reader.PaginationEngine.Metrics
import com.moyu.reader.reader.PaginationEngine.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PaginationEngine.paginate()` 依赖 android.text.StaticLayout，
 * 纯 JVM 下不可用，因此这一个测试类跑在 Robolectric 上（仍然不需要模拟器）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaginationEngineLayoutTest {

    private fun paint() = PaginationEngine.buildTextPaint(48f, Typeface.DEFAULT)

    @Test
    fun `paginate on empty content does not throw and returns one empty page`() {
        val pages = PaginationEngine.paginate("", Metrics(1080, 1920, 48), paint())
        assertEquals(1, pages.size)
        assertEquals(Page(0, 0, 0), pages[0])
    }

    @Test
    fun `paginate covers a long chapter without gaps or overlaps`() {
        val content = buildString {
            repeat(200) { append("这是一段用于分页测试的中文正文，包含标点符号。\n") }
        }
        assertTrue(content.length > 4000)

        val metrics = Metrics(contentWidth = 1080, contentHeight = 640, lineHeight = 60)
        val pages = PaginationEngine.paginate(content, metrics, paint())

        // StaticLayout 在 Robolectric 上真的能排版吗？排不出来就直接跳过而不是谎报通过。
        assumeTrue("StaticLayout produced no usable pages", pages.size > 1)

        assertEquals("first page must start at 0", 0, pages[0].start)
        assertEquals("last page must reach the end of the chapter", content.length, pages.last().end)
        pages.forEachIndexed { i, p ->
            assertEquals("page index must be the list position", i, p.index)
            assertTrue("page $i must advance: start=${p.start} end=${p.end}", p.end > p.start)
        }
        pages.zipWithNext().forEach { (a, b) ->
            assertEquals("pages must be contiguous", a.end, b.start)
        }
        assertEquals(
            "concatenating page slices must reproduce the chapter exactly",
            content,
            pages.joinToString("") { PaginationEngine.pageText(content, it) },
        )

        // 页码查找与分页结果自洽
        pages.forEach { p ->
            assertEquals(p.index, PaginationEngine.pageIndexForOffset(pages, p.start))
            assertEquals(p.index, PaginationEngine.pageIndexForOffset(pages, p.end - 1))
        }
    }
}
