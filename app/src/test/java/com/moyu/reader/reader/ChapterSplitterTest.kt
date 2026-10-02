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
     * 回归测试：以特殊篇名开头的**普通句子**不得被当成章节标题。
     *
     * 这曾经是一个真实 bug：RE_SPECIAL 原先写成「篇名 + 可选分隔符 + 最多 30 字的尾巴」，
     * 于是「前言部分内容。」被切成一个叫「前言部分内容。 部分内容。」的章节 ——
     * 既误切，又把尾巴重复拼进了标题。中文小说里这类句子（「前言里说过…」）
     * 出现频率不低，属于会真实影响阅读的缺陷。
     *
     * 修复依据：中文篇名与标题之间一定有空白（「楔子 雪夜」），
     * 否则整行就是一个纯篇名（「前言」）。两者都不满足才判为普通句子。
     */
    @Test
    fun `plain sentences starting with a special name are NOT headings`() {
        val sentences = listOf("前言部分内容。", "序言里说过这件事。", "楔子其实还没写完。", "番外小故事一则。")
        sentences.forEach { line ->
            val doc = "$line\n正文继续。\n"
            val chapters = ChapterSplitter.split(doc)

            assertTrue(
                "line=<$line> must not be split into chapters; titles=${chapters.map { it.title }}",
                chapters.none { it.detected },
            )
            // 整句必须完好地留在正文里，一个字都不能丢
            assertEquals("line=<$line>", line, chapters[0].content.take(line.length))
            assertLossless(doc, chapters)
        }
    }

    /** 真正的特殊篇名仍然要能识别 —— 修复不能把功能一起收掉。 */
    @Test
    fun `real special headings are still detected after the fix`() {
        // 纯篇名
        assertEquals("楔子", ChapterSplitter.split("楔子\n正文。\n")[0].title)
        assertEquals("前言", ChapterSplitter.split("前言\n正文。\n")[0].title)
        // 篇名 + 空白 + 标题
        val withSubtitle = ChapterSplitter.split("楔子 雪夜\n正文。\n")
        assertEquals("楔子 雪夜", withSubtitle[0].title)
        assertTrue(withSubtitle[0].detected)
        // 番外 + 序号 + 空白 + 标题
        val extras = ChapterSplitter.split("番外01 那年春深\n正文。\n")
        assertEquals("番外01 那年春深", extras[0].title)
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

    // ------------------------------------------------------------------
    // 过度切分：短篇里一行「像标题的正文」不应把整篇切成两节
    // ------------------------------------------------------------------

    @Test
    fun `短篇里唯一一行像标题的正文不会被切成两节`() {
        // 真实反馈：一篇完整短篇，中间有一行恰好以「第二章」开头，
        // 但那一行其实是正文句子（长、带句号）。原先会被当成章节标题，
        // 全文于是被切成两节，第一节只有开头一小段。
        // 用户原话：「明明没有出现明显的第二章，却自作聪明的把一篇完整短篇小说分成两节」。
        val text = buildString {
            append("这是一个完整短篇的开头部分，写的是一个人从雪夜出发上路。\n")
            append("第二章的故事从这里开始讲起，他走进了一家很小的客栈坐在角落里。\n")
            append("后面还有很多内容，但这一行其实是正文，不是章节标题。\n")
        }
        val chapters = ChapterSplitter.split(text)

        assertEquals("不应切成多节", 1, chapters.size)
        assertEquals("整篇作为一章", text.length, chapters[0].content.length)
        assertFalse("不是检测出来的章节", chapters[0].detected)
        assertLossless(text, chapters)
    }

    @Test
    fun `带句末标点的单行也按正文处理`() {
        // 长度没超限，但以句号结尾 —— 是句子而不是标题
        val text = "正文开头一段足够长让它不像标题行。\n第三章 他离开了这里。\n后面还有内容。\n"
        assertEquals(1, ChapterSplitter.split(text).size)
    }

    // ------------------------------------------------------------------
    // 剧本体例：幕 / 场 / 序幕
    // 这几条来自一个真实文件（空蝉），它的结构标记就是下面这几种写法。
    // 原先一个都认不出来，于是走了「按 3000 字均匀切块」的兜底，
    // 3 万字的文本被切成约 10 节 —— 用户看到的就是「好端端的短篇
    // 为什么分成很多节」。
    // ------------------------------------------------------------------

    @Test
    fun `剧本的幕次标记会被识别为章节`() {
        val text = buildString {
            append("序幕邂逅\n")
            append("时光飞逝，年号更迭为大正。正当人们都还在摸索新事物的时代。\n")
            append("第一幕憧憬\n")
            append("龙之介现在的身分是某个作家的门下弟子。\n")
            append("第二幕赔偿损失\n")
            append("采访第四天。龙之介走出房外。\n")
        }
        val chapters = ChapterSplitter.split(text)

        assertEquals("应识别出 3 个章节", 3, chapters.size)
        assertTrue("第一幕应被认出", chapters.any { it.title.contains("憧憬") })
        assertTrue("第二幕应被认出", chapters.any { it.title.contains("赔偿") })
        assertLossless(text, chapters)
    }

    @Test
    fun `序幕后面不带空格也能识别`() {
        // 真实文件里写的是「序幕邂逅」，中间**没有空格**。
        // 原先的正则要求「标记 + 空白 + 描述」，因此匹配不上。
        val chapters = ChapterSplitter.split("序幕邂逅\n正文内容。\n")
        assertTrue(
            "「序幕邂逅」应被识别为标题，实际得到：${chapters.map { it.title }}",
            chapters.any { it.detected && it.title.contains("邂逅") },
        )
    }

    @Test
    fun `第N场也会被识别`() {
        // 「场」是剧本的另一级单位，与「幕」一起补进量词表
        val text = "第一场 相遇\n内容甲。\n第二场 别离\n内容乙。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertLossless(text, chapters)
    }

    @Test
    fun `没有可识别标题时走兜底切块而不是当成一章`() {
        // 这条记录兜底行为本身：一篇文章若完全没有章节特征，
        // 会按 fallbackChunkSize 均匀切块。用户的文件正是走了这条路。
        val text = buildString {
            repeat(400) { append("这是一段没有任何章节标记的普通正文内容，用来触发兜底切块逻辑。\n") }
        }
        val chapters = ChapterSplitter.split(text, fallbackChunkSize = 800)
        assertTrue("无标题的长文本应被切成多块，实际 ${chapters.size}", chapters.size > 1)
        assertTrue("兜底块不应被标记为 detected", chapters.none { it.detected })
        assertLossless(text, chapters)
    }

    @Test
    fun `真正的单一短标题仍然会被识别`() {
        // 反向保护：判据改成「标题像不像」之后，
        // 不能再把合法的短标题也拦掉 —— 那会让整本书只剩一章。
        val text = "第一章 开端\n正文内容。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(1, chapters.size)
        assertTrue("应被识别为章节标题", chapters[0].detected)
        assertTrue("标题应保留", chapters[0].title.contains("开端"))
    }

    @Test
    fun `两个以上标题时不做像正文的拦截`() {
        // 多处标题本身就是强结构信号，不应因为某一行形态而整体放弃切分。
        val text = "第一章 甲\n正文。\n第二章 乙\n正文。\n"
        val chapters = ChapterSplitter.split(text)
        assertEquals(2, chapters.size)
        assertLossless(text, chapters)
    }
}
