package com.moyu.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染下标 ↔ 章内偏移的换算。
 *
 * ## 这个测试为什么重要
 *
 * 长按取词时，触摸位置先被换算成**渲染文本**里的下标，
 * 再必须换回**章内源文本**的偏移（书签与笔记都存这个坐标系）。
 * 而渲染时会插入内容 —— 段首全角缩进、段落之间的空行 ——
 * 所以两者下标不是一一对应。
 *
 * 早先这里按「页起始 + 渲染下标」估算，每多一个缩进就多漂一点，
 * 越往后偏得越远：用户长按一句话想划线，划到的是别处。
 * 而这类错误**编译能过、界面也不会崩**，只有把下标算出来比对才看得见。
 *
 * 因此这里逐个字符验证：渲染文本里第 i 个字符，必须映射回源文本里
 * 那个**内容相同**的字符。
 */
class PageTextComposerTest {

    /** 默认排版：缩进 2 字、段间距 1。 */
    private val indent = 2f
    private val breaks = 0.8f

    @Test
    fun `渲染文本插入缩进与段落空行`() {
        val page = "第一段内容\n第二段内容"
        val layout = PageTextComposer.layoutOf(page, pageStart = 0, indentEm = indent, paragraphSpacingMultiplier = breaks)

        assertEquals("两段正文", 2, layout.lines.size)
        assertEquals("缩进 2 字", 2, layout.indentLength)
        assertEquals("段间 1 个空行", 1, layout.paragraphBreaks)

        // 逐项推演（indent=2、breaks=1、pageStart=0）。
        // 正文是「第一段内容」「第二段内容」，各 5 个字符：
        //   段1：render 0 + 缩进2 = 2 → LineSpan(2, 0, 5)，render 变 7
        //        下一段的游标 = 0 + 5 + 1(换行) = 6
        //   段2：render 7 + 空行1 = 8，+ 缩进2 = 10 → LineSpan(10, 6, 5)，render 变 15
        assertEquals("渲染总长度", 15, layout.renderLength)
        assertEquals("第一段渲染起点", 2, layout.lines[0].renderStart)
        assertEquals("第一段源起点", 0, layout.lines[0].sourceStart)
        assertEquals("第二段渲染起点", 10, layout.lines[1].renderStart)
        assertEquals("第二段源起点", 6, layout.lines[1].sourceStart)
    }

    @Test
    fun `每个正文字符都能精确映射回源偏移`() {
        // 这一条是核心：逐字符验证，而不是抽样几个位置。
        // 抽样会漏掉「缩进累加导致的整体偏移」这类错误 ——
        // 那种错误在开头几个字符上往往是对的。
        val page = "远处的钟声\n惊起寒鸦\n他握紧了剑柄"
        val start = 120
        val layout = PageTextComposer.layoutOf(page, start, indent, breaks)

        for (span in layout.lines) {
            for (i in 0 until span.length) {
                val chapterOffset = PageTextComposer.renderOffsetToChapterOffset(
                    pageText = page,
                    pageStart = start,
                    indentEm = indent,
                    paragraphSpacingMultiplier = breaks,
                    renderOffset = span.renderStart + i,
                )
                assertNotNull("渲染下标 ${span.renderStart + i} 应能换算", chapterOffset)
                assertEquals(
                    "第 ${span.renderStart + i} 个渲染字符应映射到源下标 ${span.sourceStart + i}",
                    span.sourceStart + i,
                    chapterOffset,
                )
            }
        }
    }

    @Test
    fun `落在缩进上时归到该段段首`() {
        val page = "第一段\n第二段"
        val layout = PageTextComposer.layoutOf(page, 0, indent, breaks)
        val second = layout.lines[1]

        // 第二段渲染起点往前数两个位置就是缩进
        for (i in 1..layout.indentLength) {
            val off = PageTextComposer.renderOffsetToChapterOffset(
                pageText = page,
                pageStart = 0,
                indentEm = indent,
                paragraphSpacingMultiplier = breaks,
                renderOffset = second.renderStart - i,
            )
            assertEquals("缩进内应归到段首", second.sourceStart, off)
        }
    }

    @Test
    fun `空行折叠成一个段落间距`() {
        // split('\n') 会把连续空行变成空串，空串不产生 LineSpan ——
        // 也就是说「段落之间两个换行」与「一个换行」渲染结果相同。
        // 这正是早先「段间距设置没反应」的根因，这里把行为钉住。
        val single = PageTextComposer.layoutOf("A\nB", 0, 0f, 1f)
        val double = PageTextComposer.layoutOf("A\n\nB", 0, 0f, 1f)

        assertEquals("两种写法的段落数相同", single.lines.size, double.lines.size)
        assertEquals("两种写法的渲染长度相同", single.renderLength, double.renderLength)
    }

    @Test
    fun `不缩进时渲染下标与源偏移只差页起始`() {
        val page = "第一行\n第二行"
        val start = 50
        val layout = PageTextComposer.layoutOf(page, start, 0f, 1f)

        assertEquals("无缩进", 0, layout.indentLength)
        for (span in layout.lines) {
            assertEquals(
                "无缩进时段内偏移应等于源偏移",
                start + span.renderStart,
                span.sourceStart,
            )
        }
    }

    @Test
    fun `段间多个空行时渲染长度按倍数增长`() {
        val one = PageTextComposer.layoutOf("A\nB\nC", 0, 0f, 1f)
        val two = PageTextComposer.layoutOf("A\nB\nC", 0, 0f, 2f)

        assertEquals("段数相同", one.lines.size, two.lines.size)
        assertEquals("多一个空行就多一个字", 2, two.renderLength - one.renderLength)
    }

    @Test
    fun `空页面返回 null 而不是崩溃`() {
        assertNull(
            PageTextComposer.renderOffsetToChapterOffset("", 0, indent, breaks, 0),
        )
        assertNull(
            PageTextComposer.renderOffsetToChapterOffset("\n\n", 0, indent, breaks, 0),
        )
        assertNull(
            "负数下标应被拒绝",
            PageTextComposer.renderOffsetToChapterOffset("A", 0, indent, breaks, -1),
        )
    }

    @Test
    fun `超出渲染末尾时归到最后一个字`() {
        val page = "第一段\n第二段"
        val layout = PageTextComposer.layoutOf(page, 0, indent, breaks)
        val off = PageTextComposer.renderOffsetToChapterOffset(
            pageText = page,
            pageStart = 0,
            indentEm = indent,
            paragraphSpacingMultiplier = breaks,
            renderOffset = layout.renderLength + 100,
        )
        assertNotNull(off)
        // 源文本 "第一段\n第二段" 共 7 个字符（每段 3 字 + 1 个换行）。
        // 末段「第二段」的 sourceStart = 4、length = 3，因此最后一个字的下标是 6。
        assertEquals(6, off)
    }

    @Test
    fun `行首空白被剥掉且不影响映射`() {
        // 有些 TXT 用多个空格做段落缩进。渲染时会剥掉，
        // 但映射必须仍然落在正确字符上（源文本里那些空格是真实存在的）。
        val page = "　　第一段\n　　第二段"
        val layout = PageTextComposer.layoutOf(page, 0, indent, breaks)

        for (span in layout.lines) {
            assertTrue("剥掉行首空白后内容不应以空格开头", !span.text.startsWith(" "))
            for (i in 0 until span.length) {
                val off = PageTextComposer.renderOffsetToChapterOffset(
                    pageText = page,
                    pageStart = 0,
                    indentEm = indent,
                    paragraphSpacingMultiplier = breaks,
                    renderOffset = span.renderStart + i,
                )
                assertEquals(span.sourceStart + i, off)
            }
        }
    }

    @Test
    fun `页起始偏移非零时映射依然正确`() {
        // 分页会从章中间开始，pageStart 通常不是 0。
        // 这一条防的是「忘了加 pageStart」这类错误。
        val page = "内容甲\n内容乙"
        val layout = PageTextComposer.layoutOf(page, 500, indent, breaks)
        val first = layout.lines[0]

        val off = PageTextComposer.renderOffsetToChapterOffset(
            pageText = page,
            pageStart = 500,
            indentEm = indent,
            paragraphSpacingMultiplier = breaks,
            renderOffset = first.renderStart,
        )
        assertEquals("页起始应为 500 而不是 0", 500, off)
    }
}
