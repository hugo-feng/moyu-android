package com.moyu.reader.reader

import android.graphics.Typeface
import com.moyu.reader.reader.PaginationEngine.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分页与渲染必须量同一个字符串。
 *
 * ## 这条不变量是怎么被破坏的
 *
 * 渲染会往正文里**插东西**：段首缩进、段落之间的空行。而分页早期量的是
 * **原始正文** —— 于是：
 *   - 段间距调到 2 个空行时，一页里有十几个段落，渲染出来比量出来的
 *     高出十几行，版心根本装不下，末行连同后面几行直接被裁掉；
 *   - 缩进每段两个字，一页也悄悄多出约一行。
 * 用户看到的是「页尾少了一截字」，而且只会以为是自己看漏了。
 *
 * 现在分页量 `PageTextComposer.render(...)` 的结果（与渲染同一套拼接规则），
 * 页边界再映射回章内偏移。这个测试守住的就是这条：
 *
 *   **把分页结果里的任意一页，按渲染规则重新拼一遍，它必须仍然只占一页。**
 *
 * 换句话说 —— 分页认为装得下的东西，真正画出来也必须装得下。
 * 若哪天有人把「分页量渲染文本」这一步去掉，这里会立刻失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderedPageFitsTest {

    private fun paint() = PaginationEngine.buildTextPaint(48f, Typeface.DEFAULT)

    /** 足够长、段落足够多，保证一页里会跨很多段（段间距的影响才显著）。 */
    private val chapter = (1..160).joinToString("\n") {
        "第${it}段：他握紧了剑柄，回头望了一眼那座已经看不见的城，雪还在下。"
    }

    private val metrics = Metrics(contentWidth = 1080, contentHeight = 640, lineHeight = 60)

    private fun assertEveryPageFits(indentEm: Float, spacing: Float) {
        val rendered = PageTextComposer.render(
            pageText = chapter,
            pageStart = 0,
            indentEm = indentEm,
            paragraphSpacingMultiplier = spacing,
        )
        val pages = PaginationEngine.paginate(rendered, metrics, paint())
        assumeTrue("StaticLayout produced no usable pages", pages.size > 1)

        // 页边界必须把渲染文本完整覆盖、不重不漏
        assertEquals("首页必须从 0 开始", 0, pages.first().start)
        assertEquals("末页必须覆盖到末尾", rendered.length, pages.last().end)
        pages.zipWithNext().forEach { (a, b) ->
            assertEquals("相邻页必须首尾相接", a.end, b.start)
        }

        pages.forEach { page ->
            val slice = rendered.substring(page.start, page.end)
            // 这一页在渲染时会不会被当成「从段中间开始」，与阅读页判断一致
            val midParagraph = page.start > 0 && rendered[page.start - 1] != '\n'
            val pageRendered = PageTextComposer.render(
                pageText = slice,
                pageStart = 0,
                indentEm = indentEm,
                paragraphSpacingMultiplier = spacing,
                startsMidParagraph = midParagraph,
            )
            val again = PaginationEngine.paginate(pageRendered, metrics, paint())
            assertEquals(
                "缩进 ${indentEm} 字、段间距 ${spacing} 个空行时，第 ${page.index} 页" +
                    "按渲染规则重新排版后被分成了 ${again.size} 页 —— " +
                    "说明分页量的字符串比真正画出来的短，末行会被裁掉。",
                1,
                again.size,
            )
        }
    }

    @Test
    fun `默认排版下每一页渲染出来都装得进版心`() {
        assertEveryPageFits(indentEm = 2f, spacing = 1f)
    }

    @Test
    fun `段间距调到最大时每一页仍然装得进版心`() {
        // 这一条是回归测试：段间距 2 以上会在每段之间多插一个空行，
        // 一页十几个段落就是十几行 —— 旧实现会裁掉小半页。
        assertEveryPageFits(indentEm = 2f, spacing = 2f)
        assertEveryPageFits(indentEm = 2f, spacing = 3f)
    }

    @Test
    fun `不缩进时每一页也装得进版心`() {
        assertEveryPageFits(indentEm = 0f, spacing = 1f)
        assertEveryPageFits(indentEm = 0f, spacing = 3f)
    }

    @Test
    fun `渲染文本里缩进与空行都真的被插进去了`() {
        val plain = PageTextComposer.render(chapter, 0, indentEm = 0f, paragraphSpacingMultiplier = 1f)
        val indented = PageTextComposer.render(chapter, 0, indentEm = 2f, paragraphSpacingMultiplier = 1f)
        val spaced = PageTextComposer.render(chapter, 0, indentEm = 2f, paragraphSpacingMultiplier = 2f)

        assertTrue("缩进必须真的插入字符", indented.length > plain.length)
        assertTrue("段间距必须真的插入空行", spaced.length > indented.length)
        assertTrue("缩进必须是全角空格", indented.contains('\u3000'))

        // 从段中间开始的切片，首行不该再缩进
        val mid = PageTextComposer.render(
            pageText = "这是被切断的半句，后面还有字。\n第二段完整的话。",
            pageStart = 0,
            indentEm = 2f,
            paragraphSpacingMultiplier = 1f,
            startsMidParagraph = true,
        )
        assertTrue("从段中间开始时，首行不该加缩进：$mid", !mid.startsWith("\u3000"))
        assertTrue("但第二段仍然要缩进", mid.contains("\n\u3000"))
    }
}
