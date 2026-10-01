package com.moyu.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChapterSplitter 的纯 JVM 单元测试。
 *
 * 除了「能不能切」，重点验证**偏移无损**：
 *   - 所有 content 依次拼接必须与输入逐字符相同；
 *   - 每个章节满足 text.substring(start, start + length) == content；
 *   - index 从 0 开始且连续；
 *   - 相邻章节首尾相接（无空洞、无重叠）。
 */
class ChapterSplitterTest {

    private fun assertLossless(text: String, chapters: List<ChapterSplitter.Chapter>) {
        assertEquals(
            "concatenating every chapter's content must reproduce the input exactly",
            text,
            chapters.joinToString("") { it.content },
        )
        chapters.forEachIndexed { i, ch ->
            assertEquals("index must be 0-based and contiguous", i, ch.index)
            assertEquals("length must equal content.length", ch.content.length, ch.length)
            assertTrue("start must be non-negative", ch.start >= 0)
            assertEquals(
                "text.substring(start, start + length) must equal content",
                ch.content,
                text.substring(ch.start, ch.start + ch.length),
            )
        }
        chapters.zipWithNext().forEach { (a, b) ->
            assertEquals("chapters must be contiguous (no gap, no overlap)", a.start + a.length, b.start)
        }
    }

    private fun titles(text: String, fallbackChunkSize: Int = ChapterSplitter.DEFAULT_FALLBACK_CHUNK_SIZE) =
        ChapterSplitter.split(text, fallbackChunkSize).map { it.title }

    // ------------------------------------------------------------ 标题识别

    @Test
    fun `splits 第一章 标题 and 第1章 and bare 第一章`() {
        val text = "第一章 标题\n正文一\n第2章 标题二\n正文二\n第三章\n正文三\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(3, chapters.size)
        assertEquals(listOf("第一章 标题", "第2章 标题二", "第三章"), chapters.map { it.title })
        assertTrue("all three must be detected headings", chapters.all { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `handles spaces inside the numeral`() {
        val text = "第 一 章 标题\n正文\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(1, chapters.size)
        // 观察到：带空格的序号能被识别，但标题会按「第N章」紧凑形式重建，内部空格被去掉
        assertEquals("第一章 标题", chapters[0].title)
        assertTrue(chapters[0].detected)
        assertLossless(text, chapters)
        // 原文一个字符都没少（空格仍保留在 content 里）
        assertEquals(text, chapters[0].content)
    }

    @Test
    fun `splits 第X回`() {
        val text = "第三回 归来\n正文\n第四回 出发\n正文\n"
        assertEquals(listOf("第三回 归来", "第四回 出发"), titles(text))
        assertLossless(text, ChapterSplitter.split(text))
    }

    @Test
    fun `splits 第X卷`() {
        val text = "第二卷 风云\n正文\n第三卷 落幕\n正文\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(listOf("第二卷 风云", "第三卷 落幕"), chapters.map { it.title })
        assertTrue(chapters.all { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `splits english Chapter 1`() {
        val text = "Chapter 1 The Beginning\nBody one.\nChapter 2 The End\nBody two.\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(listOf("Chapter 1 The Beginning", "Chapter 2 The End"), chapters.map { it.title })
        assertTrue(chapters.all { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `splits bracketed heading 【第3章】`() {
        val text = "【第3章】觉醒\n正文\n【第4章】出发\n正文\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(listOf("第3章 觉醒", "第4章 出发"), chapters.map { it.title })
        assertTrue(chapters.all { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `splits special names 楔子 and 番外`() {
        val text = "楔子\n正文楔子内容\n正文章节\n番外\n正文番外内容\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertEquals("楔子", chapters[0].title)
        assertEquals("番外", chapters[1].title)
        assertTrue(chapters.all { it.detected })
        assertLossless(text, chapters)
    }

    // -------------------------------------------------- 超长行不得被当成标题

    @Test
    fun `a long line starting with 第一章 is not treated as a heading`() {
        val longLine = "第一章 " + "这一章的内容非常长需要被当成正文而不是标题".repeat(3)
        assertTrue("test fixture must exceed the 40-char title limit", longLine.length > 40)

        val text = "简介部分内容。\n" + longLine + "\n第二章 真正的标题\n正文。\n"
        val chapters = ChapterSplitter.split(text)

        assertTrue(
            "a >40 char line must never produce a heading; titles=${chapters.map { it.title }}",
            chapters.none { it.title.contains("第一章") },
        )
        assertEquals(listOf("前言", "第二章 真正的标题"), chapters.map { it.title })
        // 超长行必须完整落在「前言」正文里，没有被切走
        assertTrue(chapters[0].content.contains(longLine))
        assertLossless(text, chapters)
    }

    /**
     * 观察到的真实行为（误切，值得记录）：
     * RE_SPECIAL 只要求「行首出现 前言/序言/楔子/番外…」，后面允许直接跟最多 30 个字符，
     * 因此**普通句子**只要以此类词开头、且整行不超过 40 字，就会被当成章节标题，
     * 而且标题会被拼成「整句 + 残留尾巴」的重复形式。
     */
    @Test
    fun `short sentences starting with a special name are misdetected as headings (observed)`() {
        val text = "前言部分内容。\n正文继续。\n"
        val chapters = ChapterSplitter.split(text)

        assertEquals("OBSERVED: a plain sentence became a chapter", 1, chapters.size)
        assertTrue("OBSERVED: detected=true for a non-heading", chapters[0].detected)
        assertEquals("前言部分内容。 部分内容。", chapters[0].title)
        assertLossless(text, chapters)

        // 同一类误切在其它特殊词上同样出现
        listOf("序言里说过这件事。", "楔子其实还没写完。", "番外小故事一则。").forEach { line ->
            val doc = "$line\n正文继续。\n"
            val result = ChapterSplitter.split(doc)
            assertEquals("line=<$line>", 1, result.size)
            assertTrue("OBSERVED false positive for <$line>", result[0].detected)
            assertLossless(doc, result)
        }
    }

    @Test
    fun `a line that merely contains 第一章 is not a heading`() {
        val text = "他翻开书，看到第一章 标题时就笑了。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(1, chapters.size)
        assertFalse("no real heading exists in this text", chapters[0].detected)
        assertLossless(text, chapters)
    }

    // ---------------------------------------------------------- 偏移无损（长文）

    @Test
    fun `offsets are lossless for a multi-chapter document`() {
        val text = buildString {
            append("书名：测试之书\n作者：佚名\n简介：这是一段超过二十个字符的前言，用来触发前言章节。\n")
            for (i in 1..25) {
                append("第${i}章 标题${i}\n")
                repeat(5) { append("这是第${i}章的第${it + 1}段正文，内容用于占用篇幅。\n") }
            }
        }
        val chapters = ChapterSplitter.split(text)
        assertEquals("前言 + 25 章", 26, chapters.size)
        assertEquals("前言", chapters[0].title)
        assertEquals(0, chapters[0].start)
        assertFalse("the preface is not a detected heading", chapters[0].detected)
        assertTrue("all 25 real chapters must be detected", chapters.drop(1).all { it.detected })
        assertLossless(text, chapters)
    }

    // ------------------------------------------------------- 兜底段落分块

    @Test
    fun `empty text yields no chapters`() {
        assertTrue(ChapterSplitter.split("").isEmpty())
    }

    @Test
    fun `falls back to paragraph chunking without losing characters`() {
        val para = "这是第一段的内容，用于测试兜底分块是否会丢失字符。\n"
        val text = para.repeat(40)
        assertTrue("fixture must be longer than one chunk", text.length > 200)

        val chapters = ChapterSplitter.split(text, fallbackChunkSize = 200)
        assertTrue("must produce several fallback chunks, got ${chapters.size}", chapters.size > 1)
        assertTrue("fallback chunks are not 'detected' headings", chapters.none { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `hasChapterMarkers needs at least two headings`() {
        assertTrue(ChapterSplitter.hasChapterMarkers("第一章 甲\n正文\n第二章 乙\n正文\n"))
        assertFalse(ChapterSplitter.hasChapterMarkers("第一章 甲\n正文\n"))
        assertFalse(ChapterSplitter.hasChapterMarkers("没有任何章节标记的正文。\n"))
    }
}
