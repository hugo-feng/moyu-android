package com.moyu.reader.reader

/**
 * TXT 自动分章。
 *
 * 这是 Web 验证器 src/engine/chapters.ts 的 Kotlin 移植，**规则与判定顺序完全一致**。
 * 之所以逐条对齐而不是"重新设计一版"：分章结果是用户可见的核心行为，
 * 两端不一致会让人无法用验证器去判断 Android 端是否正常。
 *
 * 中文小说的章节标题格式极度混乱（第一章 / 第1章 / 【第一章】 / 卷一 / 楔子 / 番外 …），
 * 因此分两级判定：
 *   - 高置信：行首出现「第 N 章/节/回…」这类明确序号，且整行足够短；
 *   - 低置信：「一、标题」或纯序号，仅在完全没有高置信结果时启用。
 *
 * 设计原则：**宁可少切，不可错切**。错切会让正文被割裂，比不分章更糟。
 */
object ChapterSplitter {

    /** 中文数字，含大写与「两」「〇」。 */
    private const val CN_NUM = "[0-9零一二三四五六七八九十百千万两〇壹贰叁肆伍陆柒捌玖拾佰仟]"

    /** 章节量词。 */
    private const val CN_UNIT = "章节回卷节篇部集话話"

    /** 高置信：第X章 / 第X节 / 第X回 … */
    private val RE_NUMBERED = Regex(
        "^[\\s\\u3000]*第[\\s\\u3000]*($CN_NUM{1,12})[\\s\\u3000]*[$CN_UNIT]" +
            "[\\s\\u3000]*[:：.、,，\\-—]?[\\s\\u3000]*(.*)$"
    )

    /** 第X卷 / 第X部 / 第X篇 */
    private val RE_VOLUME = Regex(
        "^[\\s\\u3000]*第[\\s\\u3000]*($CN_NUM{1,12})[\\s\\u3000]*[卷部篇][\\s\\u3000]*[:：.、]?[\\s\\u3000]*(.*)$"
    )

    /** 从行内提取量词（章/节/回/卷…），用于重建干净的标题。 */
    private val RE_UNIT = Regex("第[\\s\\u3000]*$CN_NUM{1,12}[\\s\\u3000]*([$CN_UNIT])")

    /** 英文：Chapter 12 / CHAPTER 1 / Chap. 2 */
    private val RE_ENGLISH = Regex(
        "^[\\s\\u3000]*(?:chapter|chap\\.?|part|section)[\\s\\u3000]*([0-9]{1,4}|[ivxlcdm]{1,8})" +
            "[\\s\\u3000]*[:：.\\-—]?[\\s\\u3000]*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** 括号包裹：【第3章】标题 / 《第3章 标题》 */
    private val RE_BRACKETED = Regex(
        "^[\\s\\u3000]*[【《\\[（(]\\s*(第[\\s\\u3000]*$CN_NUM{1,12}[\\s\\u3000]*[$CN_UNIT])" +
            "([^】》\\]）)]*)[】》\\]）)]\\s*(.*)$"
    )

    /** 特殊篇名。 */
    private val RE_SPECIAL = Regex(
        "^[\\s\\u3000]*(?:序章|序言|序|自序|前言|引子|楔子|引言|尾声|终章|完结章|后记|附录|" +
            "番外[0-9零一二三四五六七八九十]*|外传|作者的话|作品相关|設定|设定|人物介绍)" +
            "[\\s\\u3000]*[:：.、]?[\\s\\u3000]*(.{0,30})$"
    )

    /** 低置信：一、标题 */
    private val RE_LOW_CONFIDENCE = Regex("^[\\s\\u3000]*($CN_NUM{1,12})[\\s\\u3000]*[、.．][\\s\\u3000]*(.{1,30})$")

    /** 裸数字行 */
    private val RE_BARE_NUMBER = Regex("^[\\s\\u3000]*([0-9]{1,4})[\\s\\u3000]*$")

    /** 标题行最大长度：超过则视为正文中的普通句子，不切。 */
    private const val MAX_TITLE_LINE_LENGTH = 40

    /** 完全不分章时的兜底块大小（字符数）。 */
    const val DEFAULT_FALLBACK_CHUNK_SIZE = 3000

    /**
     * 切分后的章节。
     *
     * [start] 是该章正文在原始文档中的字符偏移 —— 它是「全局偏移坐标系」的基石，
     * 进度、书签、笔记、搜索跳转全靠它。
     */
    data class Chapter(
        val index: Int,
        val title: String,
        val content: String,
        val start: Int,
        val length: Int,
        val detected: Boolean,
    )

    private data class Candidate(val offset: Int, val title: String, val confidence: Int)

    /** 清洗标题里的序号残留与多余空白。 */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("[\\s\\u3000]+"), " ")
            .replace(Regex("^[:：.、,，\\-—]+"), "")
            .replace(Regex("[:：.、,，\\-—]+$"), "")
            .trim()

    /** 对单行做标题匹配。 */
    private fun matchTitleLine(line: String, allowLowConfidence: Boolean): Pair<String, Int>? {
        // 特殊篇名（序章/楔子/番外…）
        RE_SPECIAL.find(line)?.let { m ->
            val head = cleanTitle(line)
            val tail = cleanTitle(m.groupValues.getOrElse(1) { "" })
            return if (tail.isNotEmpty()) {
                val first = head.split(Regex("[\\s\\u3000]")).firstOrNull().orEmpty()
                "$first $tail" to 0
            } else {
                head to 0
            }
        }

        // 【第一章】标题
        RE_BRACKETED.find(line)?.let { m ->
            val head = cleanTitle(m.groupValues.getOrElse(1) { "" })
            val inner = cleanTitle(m.groupValues.getOrElse(2) { "" })
            val tail = cleanTitle(m.groupValues.getOrElse(3) { "" })
            val parts = listOf(head, inner, tail).filter { it.isNotEmpty() }
            return parts.joinToString(" ") to 0
        }

        // 第X章 / 第X节 / 第X回
        RE_NUMBERED.find(line)?.let { m ->
            val num = m.groupValues.getOrElse(1) { "" }
            val unit = RE_UNIT.find(line)?.groupValues?.getOrNull(1) ?: "章"
            val tail = cleanTitle(m.groupValues.getOrElse(2) { "" })
            val rebuilt = cleanTitle("第$num$unit")
            return (if (tail.isNotEmpty()) "$rebuilt $tail" else rebuilt) to 0
        }

        // 第X卷 / 第X部 / 第X篇
        RE_VOLUME.find(line)?.let { m ->
            val num = m.groupValues.getOrElse(1) { "" }
            val unit = RE_UNIT.find(line)?.groupValues?.getOrNull(1) ?: "卷"
            val tail = cleanTitle(m.groupValues.getOrElse(2) { "" })
            val rebuilt = cleanTitle("第$num$unit")
            return (if (tail.isNotEmpty()) "$rebuilt $tail" else rebuilt) to 0
        }

        // Chapter 12
        RE_ENGLISH.find(line)?.let { m ->
            val tail = cleanTitle(m.groupValues.getOrElse(2) { "" })
            val rebuilt = cleanTitle(line.substring(0, line.length - tail.length))
            return (if (tail.isNotEmpty()) "$rebuilt $tail" else rebuilt) to 0
        }

        if (allowLowConfidence) {
            RE_LOW_CONFIDENCE.find(line)?.let { m ->
                val tail = cleanTitle(m.groupValues.getOrElse(2) { "" })
                // 排除「3.5」这类小数，避免把正文里的数字当章节
                if (tail.isNotEmpty() && !tail[0].isDigit()) {
                    return cleanTitle("${m.groupValues.getOrElse(1) { "" }}、$tail") to 1
                }
            }
            RE_BARE_NUMBER.find(line)?.let { m ->
                return "第 ${m.groupValues.getOrElse(1) { "" }} 章" to 1
            }
        }

        return null
    }

    /** 扫描全文，收集候选标题偏移。只扫行首，且跳过超长行 —— 这是避免误切的关键。 */
    private fun collectCandidates(text: String, allowLowConfidence: Boolean): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        var lineStart = 0

        while (lineStart <= text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd == -1) lineEnd = text.length

            val line = text.substring(lineStart, lineEnd)
            if (line.isNotBlank() && line.trim().length <= MAX_TITLE_LINE_LENGTH) {
                matchTitleLine(line, allowLowConfidence)?.let { (title, confidence) ->
                    candidates.add(Candidate(lineStart, title, confidence))
                }
            }

            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
        }
        return candidates
    }

    /** 按段落边界把长文本切成兜底块。 */
    private fun chunkByParagraph(text: String, chunkSize: Int): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val length = text.length
        var index = 0
        var cursor = 0

        while (cursor < length) {
            var end = minOf(cursor + chunkSize, length)
            if (end < length) {
                // 向后寻找最近的换行，避免切断句子；最多再多看 20% 长度
                val searchLimit = minOf(length, end + chunkSize / 5)
                val newline = text.indexOf('\n', end)
                if (newline != -1 && newline < searchLimit) end = newline + 1
            }
            val content = text.substring(cursor, end)
            if (content.isNotBlank() || index == 0) {
                index++
                chapters.add(
                    Chapter(
                        index = index - 1,
                        title = "第 $index 节",
                        content = content,
                        start = cursor,
                        length = content.length,
                        detected = false,
                    )
                )
            }
            cursor = end
        }
        return chapters
    }

    /**
     * 把整本书的文本切成章节。
     *
     * @param text 已归一化换行（\n）的全文
     * @param fallbackChunkSize 无章节特征时的分块大小
     */
    fun split(text: String, fallbackChunkSize: Int = DEFAULT_FALLBACK_CHUNK_SIZE): List<Chapter> {
        if (text.isEmpty()) return emptyList()

        // 先只用高置信规则扫一遍
        var candidates = collectCandidates(text, allowLowConfidence = false)

        // 只有在「完全没有高置信标题」时才启用低置信规则。
        // 单一高置信标题本身就是有效信息，应当保留（Web 端曾因更激进的判据丢过标题）。
        if (candidates.isEmpty()) {
            candidates = collectCandidates(text, allowLowConfidence = true)
        }

        // 仍然没有章节特征 → 按段落兜底切块
        if (candidates.isEmpty()) return chunkByParagraph(text, fallbackChunkSize)

        val chapters = mutableListOf<Chapter>()
        for (i in candidates.indices) {
            val current = candidates[i]
            val end = if (i + 1 < candidates.size) candidates[i + 1].offset else text.length
            val content = text.substring(current.offset, end)
            chapters.add(
                Chapter(
                    index = i,
                    title = current.title.ifEmpty { "第 ${i + 1} 章" },
                    content = content,
                    start = current.offset,
                    length = content.length,
                    detected = true,
                )
            )
        }

        // 正文之前若有实质内容（书名、作者、简介），补一个「前言」章
        val firstOffset = candidates[0].offset
        if (firstOffset > 0) {
            val preface = text.substring(0, firstOffset)
            if (preface.trim().length > 20) {
                chapters.add(
                    0,
                    Chapter(
                        index = 0,
                        title = "前言",
                        content = preface,
                        start = 0,
                        length = preface.length,
                        detected = false,
                    )
                )
                // 重排 index：插入前言后后续章节序号整体后移
                for (i in chapters.indices) {
                    chapters[i] = chapters[i].copy(index = i)
                }
            }
        }

        return chapters
    }

    /** 判断文本是否「看起来已经分好章」（供导入流程提示用户）。 */
    fun hasChapterMarkers(text: String): Boolean =
        collectCandidates(text, allowLowConfidence = false).size >= 2
}
