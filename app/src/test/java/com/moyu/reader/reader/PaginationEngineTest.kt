package com.moyu.reader.reader

import com.moyu.reader.reader.PaginationEngine.Metrics
import com.moyu.reader.reader.PaginationEngine.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaginationEngine 的**纯 helper** 单元测试（普通的 JVM 测试，不带 Robolectric）。
 *
 * `paginate()` 依赖 android.text.StaticLayout，在纯 JVM 下无法使用；
 * 它只在 [PaginationEngineLayoutTest]（Robolectric）里验证。
 */
class PaginationEngineTest {

    private val pages = listOf(
        Page(0, 0, 10),
        Page(1, 10, 20),
        Page(2, 20, 30),
    )

    // ------------------------------------------------ pageIndexForOffset

    @Test
    fun `offset 0 and negative offsets map to page 0`() {
        assertEquals(0, PaginationEngine.pageIndexForOffset(pages, 0))
        assertEquals(0, PaginationEngine.pageIndexForOffset(pages, -1))
        assertEquals(0, PaginationEngine.pageIndexForOffset(pages, -9999))
    }

    @Test
    fun `page ranges are right-open so the end offset belongs to the next page`() {
        assertEquals(0, PaginationEngine.pageIndexForOffset(pages, 1))
        assertEquals(0, PaginationEngine.pageIndexForOffset(pages, 9))
        // 边界：page0.end == 10，偏移 10 属于第 1 页
        assertEquals(1, PaginationEngine.pageIndexForOffset(pages, 10))
        assertEquals(1, PaginationEngine.pageIndexForOffset(pages, 19))
        assertEquals(2, PaginationEngine.pageIndexForOffset(pages, 20))
        assertEquals(2, PaginationEngine.pageIndexForOffset(pages, 29))
    }

    @Test
    fun `an offset past the end clamps to the last page`() {
        assertEquals(2, PaginationEngine.pageIndexForOffset(pages, 30))
        assertEquals(2, PaginationEngine.pageIndexForOffset(pages, 31))
        assertEquals(2, PaginationEngine.pageIndexForOffset(pages, 1_000_000))
    }

    @Test
    fun `an empty page list returns 0`() {
        assertEquals(0, PaginationEngine.pageIndexForOffset(emptyList(), 0))
        assertEquals(0, PaginationEngine.pageIndexForOffset(emptyList(), 42))
    }

    @Test
    fun `single page covers every offset`() {
        val one = listOf(Page(0, 0, 100))
        assertEquals(0, PaginationEngine.pageIndexForOffset(one, 0))
        assertEquals(0, PaginationEngine.pageIndexForOffset(one, 99))
        assertEquals(0, PaginationEngine.pageIndexForOffset(one, 100))
        assertEquals(0, PaginationEngine.pageIndexForOffset(one, 500))
    }

    @Test
    fun `lookup is consistent across a long page list`() {
        val many = (0 until 100).map { Page(it, it * 20, (it + 1) * 20) }
        for (i in 0 until 100) {
            assertEquals("start of page $i", i, PaginationEngine.pageIndexForOffset(many, i * 20))
            assertEquals("middle of page $i", i, PaginationEngine.pageIndexForOffset(many, i * 20 + 7))
            assertEquals("last char of page $i", i, PaginationEngine.pageIndexForOffset(many, i * 20 + 19))
        }
    }

    // ------------------------------------------------------------ pageText

    @Test
    fun `pageText slices the right-open range`() {
        val content = "0123456789abcdefghij"
        assertEquals("0123456789", PaginationEngine.pageText(content, Page(0, 0, 10)))
        assertEquals("abcdefghij", PaginationEngine.pageText(content, Page(1, 10, 20)))
        assertEquals("", PaginationEngine.pageText(content, Page(2, 20, 20)))
    }

    // ------------------------------------------------ estimateCharsPerScreen

    @Test
    fun `estimateCharsPerScreen multiplies chars per line by lines`() {
        // 1080 / 48 = 22.5 字/行；1920 / 48 = 40 行 -> 900
        val metrics = Metrics(contentWidth = 1080, contentHeight = 1920, lineHeight = 48)
        assertEquals(40, metrics.maxLines)
        assertEquals(900, PaginationEngine.estimateCharsPerScreen(metrics, 48f))
    }

    @Test
    fun `estimateCharsPerScreen never returns zero or negative`() {
        val tiny = Metrics(contentWidth = 10, contentHeight = 10, lineHeight = 100)
        assertEquals(1, PaginationEngine.estimateCharsPerScreen(tiny, 48f))
        assertEquals(1, PaginationEngine.estimateCharsPerScreen(tiny, 0f))
        assertEquals(1, PaginationEngine.estimateCharsPerScreen(tiny, -12f))
    }

    @Test
    fun `estimateCharsPerScreen falls back to one line when lineHeight is not positive`() {
        val metrics = Metrics(contentWidth = 1080, contentHeight = 1920, lineHeight = 0)
        assertEquals("lineHeight <= 0 must degrade to 1 line", 1, metrics.maxLines)
        // lines = 1920 / max(1, 0) = 1920 -> 22.5 * 1920 = 43200
        assertEquals(43200, PaginationEngine.estimateCharsPerScreen(metrics, 48f))
    }

    @Test
    fun `estimateCharsPerScreen scales with the font size`() {
        val metrics = Metrics(contentWidth = 1080, contentHeight = 1920, lineHeight = 48)
        // 96px: 1080/96 = 11.25 字/行 * 40 行 = 450
        // 24px: 1080/24 = 45    字/行 * 40 行 = 1800
        val bigger = PaginationEngine.estimateCharsPerScreen(metrics, 96f)
        val smaller = PaginationEngine.estimateCharsPerScreen(metrics, 24f)
        assertEquals(450, bigger)
        assertEquals(1800, smaller)
        assertTrue("larger font must fit fewer chars: $bigger vs $smaller", bigger < smaller)
    }
}
