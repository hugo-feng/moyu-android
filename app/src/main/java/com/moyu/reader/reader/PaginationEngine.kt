package com.moyu.reader.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 分页引擎（Android 版）。
 *
 * 与 Web 验证器的策略差异，以及为什么：
 *
 *   Web 端用 Canvas 估算字符宽度（中日韩按 1em、ASCII 按 0.5em）再做二分查找 ——
 *   这是因为浏览器里拿不到真正的断行结果，只能近似。
 *
 *   Android 端**不需要近似**：`StaticLayout` 就是系统排版的真实实现，
 *   直接问它「这段文字排完占多少行」，得到的是与最终显示完全一致的断行。
 *   因此这里用 StaticLayout 精确排版，分页结果不会出现「与视觉差半行」的问题。
 *
 * 输出同样是「页码 → 字符区间」的索引表，因此书签/笔记/搜索跳转
 * 与 Web 端共用同一套「全局字符偏移」坐标系。
 */
object PaginationEngine {

    /** 一页的字符区间（右开区间）。 */
    data class Page(val index: Int, val start: Int, val end: Int)

    /**
     * 排版度量。
     *
     * `contentHeight` 是**正文区**的真实高度（已扣掉页边距、系统栏安全区与地脚页码）。
     * `firstPageHeaderHeight` 是章首页额外占用的标题块高度（章节序号 + 大标题）；
     * 只有第一页要扣它，其余页不扣。
     *
     * 为什么要把标题单独拎出来：早先只有一个 `contentHeight`，
     * 而章首页渲染时会多出一整块标题，分页却按「没有标题」的高度算行数，
     * 于是首页多排 3 行、末行被裁掉 —— 用户不会发现自己少读了字，是最危险的一类错误。
     * （每页的地脚页码高度已经含在 contentHeight 里，由渲染侧实测得到。）
     */
    data class Metrics(
        val contentWidth: Int,
        val contentHeight: Int,
        val lineHeight: Int,
        val firstPageHeaderHeight: Int = 0,
    ) {
        /** 每页最多容纳的行数（不含章首页标题）。 */
        val maxLines: Int get() = linesFor(isFirstPage = false)

        /** 指定页能容纳的行数。 */
        fun linesFor(isFirstPage: Boolean): Int {
            if (lineHeight <= 0) return 1
            val usable = if (isFirstPage) contentHeight - firstPageHeaderHeight else contentHeight
            return maxOf(1, usable / lineHeight)
        }
    }

    /**
     * 用 StaticLayout 精确分页。
     *
     * 算法：对剩余文本构造 StaticLayout，取它能容纳的行数对应的字符位置作为页边界。
     * 关键点是 StaticLayout 的 `getLineEnd(line)` 给出**考虑了断行规则后**的真实行尾
     * （不会把标点甩到行首、不会切开英文单词），这正是自造估算做不到的。
     *
     * @param content 章节正文
     * @param metrics 排版度量
     * @param paint 正文画笔（字号、字体、字距必须与显示完全一致，否则分页会漂移）
     * @param maxLinesOverride 强制每页行数（测试用）
     */
    fun paginate(
        content: String,
        metrics: Metrics,
        paint: TextPaint,
        maxLinesOverride: Int? = null,
    ): List<Page> {
        if (content.isEmpty()) return listOf(Page(0, 0, 0))

        val width = maxOf(1, metrics.contentWidth)
        val pages = ArrayList<Page>(content.length / 400 + 1)

        var start = 0
        var guard = 0
        val maxIterations = content.length / 4 + 64

        /**
         * 每行大约能放多少字。
         *
         * 这里曾经写成 `maxLines * width` —— 把**像素宽度当成了字符数**，
         * 于是给 StaticLayout 的切片比实际需要大五六十倍（948px 宽、57px 字号
         * 实际每行只放得下约 16 字，却被当成 948 字）。后果不是结果错误，
         * 而是每一页都要对两万字做一次完整排版，翻页时明显卡顿。
         *
         * 中文按「一个字符约等于一个字号宽度」估算（与 Web 端分页口径一致）；
         * 估多了只是多排一点，估少了才会出错，所以再乘一个安全系数。
         */
        val textSize = paint.textSize.coerceAtLeast(1f)
        val charsPerLine = maxOf(1, (width / textSize).toInt())

        while (start < content.length) {
            // 防止病态输入导致死循环：迭代上限与文本长度成正比
            if (guard++ > maxIterations) break

            /**
             * 本页能放多少行。
             *
             * 章首页要额外扣掉标题块（章节序号 + 大标题）的高度，
             * 其余页不扣 —— 否则首页会多排几行，末行被裁掉。
             */
            val isFirstPage = pages.isEmpty()
            val maxLines = maxLinesOverride ?: metrics.linesFor(isFirstPage)

            val remaining = content.substring(start)
            // 只排版当前页可能容纳的量（maxLines 行），避免对超长章节做无谓的全量排版
            val probeChars = (maxLines * charsPerLine * 2).coerceAtLeast(maxLines * 4)
            val probeLength = minOf(remaining.length, probeChars)
            val slice = remaining.substring(0, probeLength)

            val layout = buildLayout(slice, width, paint)

            // 整段都放得下：这一页到章末
            if (layout.lineCount <= maxLines) {
                pages.add(Page(pages.size, start, content.length))
                break
            }

            // 第 maxLines 行的行尾即本页边界。注意行号从 0 开始，
            // 因此「第 maxLines 行」的索引是 maxLines - 1。
            val endInSlice = layout.getLineEnd(maxLines - 1)
            var end = start + endInSlice

            // 边界保护：必须至少前进 1 个字符，否则会死循环
            if (end <= start) end = minOf(start + 1, content.length)

            pages.add(Page(pages.size, start, end))
            start = end
        }

        if (pages.isEmpty()) pages.add(Page(0, 0, content.length))
        return pages
    }

    /**
     * 构造 StaticLayout。
     *
     * 用 Builder 而不是已废弃的构造函数：API 23+ 的 Builder 才能正确设置
     * breakStrategy / hyphenation，否则 Android 10+ 上会收到废弃警告且断行行为不一致。
     */
    private fun buildLayout(text: String, width: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            // 与阅读器显示保持一致的对齐与断行策略，
            // 否则「分页算出来的行」和「画出来的行」会对不上
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /** 二分查找：给定章内字符偏移，返回它所在的页序号。 */
    fun pageIndexForOffset(pages: List<Page>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        if (offset <= 0) return 0

        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (pages[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 取指定页的正文切片。 */
    fun pageText(content: String, page: Page): String =
        content.substring(page.start.coerceIn(0, content.length), page.end.coerceIn(0, content.length))

    /**
     * 构造与显示完全一致的正文字画。
     *
     * 必须由调用方传入与 UI 相同的字号/字体/字距；任何差异都会让分页与视觉错位。
     */
    fun buildTextPaint(
        textSizePx: Float,
        typeface: Typeface,
        letterSpacingEm: Float = 0.012f,
    ): TextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSizePx
        this.typeface = typeface
        // letterSpacing 在 StaticLayout 里通过 TextPaint 的 letterSpacing 生效
        this.letterSpacing = letterSpacingEm
        this.isSubpixelText = true
    }

    /**
     * 估算一屏大约多少字，用于导入时预估页数与阅读时长。
     * 中文按 1 个宽度单位、ASCII 按 0.5 计，与 Web 端口径一致。
     */
    fun estimateCharsPerScreen(metrics: Metrics, textSizePx: Float): Int {
        if (textSizePx <= 0f) return 1
        val charsPerLine = metrics.contentWidth / textSizePx
        val lines = metrics.contentHeight / maxOf(1, metrics.lineHeight)
        return maxOf(1, (charsPerLine * lines).toInt())
    }
}
