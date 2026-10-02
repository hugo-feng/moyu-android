package com.moyu.reader.reader

/**
 * 正文渲染文本的组装与「渲染下标 ↔ 章内偏移」换算。
 *
 * ## 为什么组装与换算必须放在一起
 *
 * 渲染时会**插入内容**：段首加全角缩进、段落之间补空行。
 * 于是渲染文本比源文本长，两者下标不再一一对应。
 *
 * 早先这两件事分别写在两个地方：组装在 UI 层的 `buildPageText`，
 * 长按取词处则按「页起始 + 渲染下标」估算源位置。结果必然错位 ——
 * 每多一个缩进/空行就多漂一点，越往后偏得越远，
 * 用户长按一句话想划线，划到的是别处。
 *
 * 现在两者共用同一个 [layoutOf]：组装按它插字符，换算按它记账。
 * **只有一处规则，就不可能不同步。**
 *
 * 放在 `reader` 包而不是 UI 层，是因为这套换算必须能被单元测试覆盖 ——
 * 它是标注功能的正确性核心，而 UI 层的 private 函数测不到。
 */
object PageTextComposer {

    /** 一段正文在渲染文本与源文本里的位置。 */
    data class LineSpan(
        /** 该段在**渲染文本**里的起始下标 */
        val renderStart: Int,
        /** 该段在**章内源文本**里的起始下标 */
        val sourceStart: Int,
        /** 该段在渲染文本里的字符数（与源文本一致 —— 正文本身不做改写） */
        val length: Int,
        /**
         * 该段的内容（已剥掉行首空白）。
         *
         * 直接存下来而不是让调用方用 `sourceStart` 去源文本里再切一次：
         * 后者需要知道「页起始」才能换算成页内下标，很容易算错 ——
         * 我第一版就是这么写的，绕了一圈还得靠一个占位函数兜底。
         */
        val text: String,
    )

    /** 一页的完整排版信息。 */
    data class Layout(
        val lines: List<LineSpan>,
        /** 渲染文本的总长度 */
        val renderLength: Int,
        /** 段首缩进字符数（0 表示不缩进） */
        val indentLength: Int,
        /** 段落之间插入的换行数 */
        val paragraphBreaks: Int,
    )

    /** 段首缩进字符数。 */
    fun indentLength(indentEm: Float): Int =
        if (indentEm > 0f) indentEm.toInt().coerceIn(0, 4) else 0

    /** 段落之间插入的换行数（至少 1，否则段落会粘在一起）。 */
    fun paragraphBreakCount(paragraphSpacingMultiplier: Float): Int =
        paragraphSpacingMultiplier.coerceIn(0.5f, 3f).toInt().coerceAtLeast(1)

    /**
     * 计算一页的排版布局。
     *
     * 这是组装与换算的**唯一真相来源**：`buildPageText` 按它插字符，
     * [renderOffsetToChapterOffset] 按它记账，两者不可能不同步。
     *
     * 规则（与早先的 `buildPageText` 完全一致，只是抽了出来）：
     *   - 空行折叠：连续空行不各占一个段落间距，只算一次
     *     （`split('\n')` 会把空行变成空串，空串不产生 LineSpan）
     *   - 每段剥掉行首空白，避免与首行缩进叠加成双倍缩进
     *   - 首段不加缩进前置的段落间距
     */
    fun layoutOf(
        pageText: String,
        pageStart: Int,
        indentEm: Float,
        paragraphSpacingMultiplier: Float,
    ): Layout {
        val indentLen = indentLength(indentEm)
        val breaks = paragraphBreakCount(paragraphSpacingMultiplier)

        val lines = ArrayList<LineSpan>()
        var render = 0
        var cursor = pageStart
        var firstEmitted = true

        for (rawLine in pageText.split('\n')) {
            val trimmed = rawLine.trimStart()
            val leading = rawLine.length - trimmed.length
            val sourceLineStart = cursor + leading

            if (trimmed.isNotEmpty()) {
                if (!firstEmitted) render += breaks
                firstEmitted = false
                render += indentLen

                lines.add(LineSpan(render, sourceLineStart, trimmed.length, trimmed))
                render += trimmed.length
            }

            // +1 是被 split 吃掉的那个换行符
            cursor += rawLine.length + 1
        }

        return Layout(lines, render, indentLen, breaks)
    }

    /**
     * 渲染文本下标 → 章内字符偏移。
     *
     * 落在缩进、段落空行或行尾换行上时归到**该段段首** ——
     * 那些位置本就不对应任何源字符，归到段首比返回 null 更符合直觉
     * （用户长按段首空白，想标的就是这一段）。
     *
     * @return 章内偏移；整页为空时返回 null
     */
    fun renderOffsetToChapterOffset(
        pageText: String,
        pageStart: Int,
        indentEm: Float,
        paragraphSpacingMultiplier: Float,
        renderOffset: Int,
    ): Int? {
        if (renderOffset < 0) return null
        val layout = layoutOf(pageText, pageStart, indentEm, paragraphSpacingMultiplier)
        if (layout.lines.isEmpty()) return null

        for (span in layout.lines) {
            val spanEnd = span.renderStart + span.length
            if (renderOffset < spanEnd) {
                // 落在这一段的起始之前（即缩进里）→ 归到段首
                if (renderOffset <= span.renderStart) return span.sourceStart
                return span.sourceStart + (renderOffset - span.renderStart)
            }
        }

        // 超出渲染文本末尾（长按到最后一行的空白区）→ 归到最后一个字
        val last = layout.lines.last()
        return last.sourceStart + last.length - 1
    }
}
