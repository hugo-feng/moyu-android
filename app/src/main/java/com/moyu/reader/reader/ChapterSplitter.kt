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

    /**
     * 标题最长多少字之后就不像标题、而像正文了。
     *
     * 中文小说标题极少超过 20 字；超过说明这一行其实是正文句子
     * （只是恰好以「第 X 章」开头）。
     */
    private const val MAX_PLAUSIBLE_TITLE_LENGTH = 24

    /** 句末标点：出现它们说明这是正文句子，不是标题。 */
    private val PROSE_ENDINGS = Regex("[。！？；…]$|[，、]$")

    /**
     * 判断一行「读起来像不像正文」而不是标题。
     *
     * 用于拦住**短篇里被误认成分隔语的行** —— 见 [split] 里的说明。
     * 判据是长度与句末标点，与全文长短无关：按全文长度判断会误伤
     * 合法的短文本（测试固件就是短文本）。
     */
    private fun looksLikeProse(title: String): Boolean {
        val t = title.trim()
        if (t.length > MAX_PLAUSIBLE_TITLE_LENGTH) return true
        return PROSE_ENDINGS.containsMatchIn(t)
    }

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

    /**
     * 特殊篇名。与 Web 端 src/engine/chapters.ts 的 RE_SPECIAL 逐字对应。
     *
     * 这里曾经写成「篇名 + 可选分隔符 + 最多 30 字的尾巴」，实际会把
     * **以篇名开头的普通句子**整句当成标题：正文里的「前言部分内容。」
     * 会被切成一个叫「前言部分内容。 部分内容。」的章节（尾巴还被重复拼了一遍）。
     *
     * 收紧的依据是中文篇名的实际写法：篇名与标题之间一定有空白
     * （「楔子 雪夜」「番外 那年春深」），否则整行就是一个纯篇名（「前言」「楔子」）。
     *
     * 分组约定（改动正则时必须同步维护，取错组会让功能静默失效）：
     *   group1 = 分支 1 的纯篇名；group2 = 分支 2 的篇名，group3 = 分支 2 的标题。
     *
     * 分支 1 的尾部空白是 `[ \t\u3000]*` 而不是 `[\s\u3000]*`，这一点很关键：
     * `\s` 包含换行，会让分支 1 吃掉「楔子 雪夜」中间的空格后仍然匹配成功，
     * 于是整行被「纯篇名」分支抢走，真正的标题反而被当成了篇名。
     * 只允许空格与制表符，才能逼正则引擎在遇到「空格 + 标题」时改走分支 2。
     *
     * 用原始字符串（"""）而不是普通字符串：普通字符串里写 "\$" 会展开成
     * 字面反斜杠加美元符，正则收到的是「匹配字面 $ 字符」而非行尾锚点，
     * 结果是整个正则永远匹配不上 —— 这个坑已经踩过一次。
     */
    private val RE_SPECIAL = Regex(
        """^[\s\u3000]*(序章|序言|序|自序|前言|引子|楔子|引言|尾声|终章|完结章|后记|附录|番外[0-9零一二三四五六七八九十]*|外传|作者的话|作品相关|設定|设定|人物介绍)[ \t\u3000]*[:：.、]?[ \t\u3000]*${'$'}""" +
            """|^[\s\u3000]*(序章|序言|序|自序|前言|引子|楔子|引言|尾声|终章|完结章|后记|附录|番外[0-9零一二三四五六七八九十]*|外传|作者的话|作品相关|設定|设定|人物介绍)[\s\u3000]+(.{1,30})[\s\u3000]*${'$'}"""
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
            // 分支 2（篇名 + 空白 + 标题）命中时 group2/group3 有值，标题是「篇名 标题」；
            // 分支 1（纯篇名）命中时只有 group1，标题就是篇名本身。
            val marker = cleanTitle(m.groupValues.getOrElse(2) { "" })
            val tail = cleanTitle(m.groupValues.getOrElse(3) { "" })
            if (marker.isNotEmpty()) {
                return (if (tail.isNotEmpty()) "$marker $tail" else marker) to 0
            }
            return cleanTitle(m.groupValues.getOrElse(1) { "" }) to 0
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

        /**
         * 值不值得切 —— 只在「只有一个候选标题，且那个标题不像标题」时拦。
         *
         * ## 这一步在防什么
         *
         * 规则已经把「正文里的数字」这类误判挡掉了（只扫行首、跳过超长行、
         * 低置信需高置信为空才启用）。剩下的情况是：
         * **短篇小说里出现一行像章节标题的分隔语**。
         *
         * 例如一篇完整短篇，中间有一行「第二章」样式的分隔语 ——
         * 全文被切成两节：第一节是开头一小段，第二节是剩下的全部。
         * 用户看到的是「明明没有第二章，却自作聪明分成两节」。
         *
         * ## 为什么按「标题像不像」而不是按全文长度
         *
         * 起初我按全文长度拦（不足 8000 字且只有一个标题就不切），
         * 结果把大量**合法的短文本**也拦掉了 —— 测试固件本身就是短文本，
         * 于是「第二章 真正的标题」「楔子」这类明确标题全被判成分章失败。
         *
         * 可见长度不是判据。真正的差别在于**那一行读起来像不像标题**：
         *   - 像标题：短（十几个字以内）、没有句末标点、自身就是完整的一行
         *   - 像正文里被误认的行：很长（是完整句子被截断到行首）、
         *     或带句号/问号/感叹号
         *
         * 因此判据是「标题长度 + 有无句末标点」，与全文长短无关。
         * 拦住之后整篇作为一章，不会丢内容 ——
         * 错切会让正文被割裂，比不分章更糟。
         */
        val onlyCandidate = candidates.singleOrNull()
        if (onlyCandidate != null && looksLikeProse(onlyCandidate.title)) {
            return listOf(
                Chapter(
                    index = 0,
                    title = "全文",
                    content = text,
                    start = 0,
                    length = text.length,
                    detected = false,
                )
            )
        }

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
