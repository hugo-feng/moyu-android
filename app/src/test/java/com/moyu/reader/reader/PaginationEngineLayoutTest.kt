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

    // ------------------------------------------------------------------
    // 断行：句子不该被从中间切开
    // ------------------------------------------------------------------

    @Test
    fun `分页边界不会落在一句话的中间`() {
        // 用户反馈「为什么书籍分页会把一句完整的句子从中间切开分到两页」。
        //
        // 直接原因：渲染侧（Compose 的 TextStyle）没有指定 lineBreak，
        // 默认是 LineBreak.Simple，允许在任意字符间断行；
        // 而分页引擎用的是 BREAK_STRATEGY_HIGH_QUALITY。
        // 两边策略不同 → 分页算出的行尾与真正画出来的行尾不一致，
        // 于是页面边界会切在句子中间。
        //
        // 这条测试守住的是「页面边界落在标点或段末」这个性质：
        // 每一页的最后一个字符，要么是标点/换行，要么后面紧跟标点。
        val content = buildString {
            repeat(60) {
                append("他握紧了剑柄，回头望了一眼那座已经看不见的城。雪还在下。\n")
            }
        }
        val metrics = Metrics(contentWidth = 1080, contentHeight = 640, lineHeight = 60)
        val pages = PaginationEngine.paginate(content, metrics, paint())
        assumeTrue("StaticLayout produced no usable pages", pages.size > 1)

        // 句末标点与段落结束都算「自然的断点」
        val naturalBreaks = setOf('。', '！', '？', '；', '…', '\n', '」', '』', '，', '、')

        val bad = pages.dropLast(1).filter { p ->
            val lastChar = content[p.end - 1]
            val nextChar = content.getOrNull(p.end)
            lastChar !in naturalBreaks && nextChar !in naturalBreaks
        }

        assertTrue(
            "以下页面的边界落在句子中间（既不是标点前也不是标点后）：" +
                bad.joinToString("") { p ->
                    "\n  page ${p.index}: …「" +
                        content.substring((p.end - 8).coerceAtLeast(0), p.end) + "」|「" +
                        content.substring(p.end, (p.end + 8).coerceAtMost(content.length)) + "」…"
                },
            bad.isEmpty(),
        )
    }

    @Test
    fun `行尾不会出现句末标点跑到下一行开头`() {
        // 中文排版的基本规则：句号、逗号这类标点不能出现在行首。
        // 分页引擎用的是系统 StaticLayout，规则由系统保证；
        // 这条测试的作用是：如果将来有人把 breakStrategy 改掉（比如换成 SIMPLE），
        // 这里会立刻失败并指出问题。
        val content = buildString {
            repeat(40) { append("剑光一闪，雨停了，他的衣角还在滴水，却没有一丝声音。\n") }
        }
        val metrics = Metrics(contentWidth = 1080, contentHeight = 640, lineHeight = 60)
        val pages = PaginationEngine.paginate(content, metrics, paint())
        assumeTrue("StaticLayout produced no usable pages", pages.size > 1)

        pages.dropLast(1).forEach { p ->
            val next = content.getOrNull(p.end)
            assertTrue(
                "页 ${p.index} 的下一页以标点开头：「${next}」—— " +
                    "说明断行策略没有把标点约束在行尾",
                next == null || next !in setOf('。', '，', '！', '？', '、', '；'),
            )
        }
    }
}
