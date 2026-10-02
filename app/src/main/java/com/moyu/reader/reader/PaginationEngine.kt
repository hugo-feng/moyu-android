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
            return maxOf(1, contentHeightFor(isFirstPage) / lineHeight)
        }

        /**
         * 指定页真正可用的高度。
         *
         * 章首页要扣掉标题块；其余页不扣。
         * 分页不仅按行数约束，还要按这个高度二次校验（见 `paginate` 的回退逻辑）。
         */
        fun contentHeightFor(isFirstPage: Boolean): Int =
            if (isFirstPage) (contentHeight - firstPageHeaderHeight).coerceAtLeast(1) else contentHeight
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

        /**
         * 探测切片的长度（字符数）。
         *
         * 这个值的唯一作用是「别对两万字的章节做全量排版」，**绝不能**小到
         * 反过来限制判断 —— 早期版本取 `maxLines * charsPerLine * 2`，
         * 结果切片短于真实可用行数，`layout.lineCount` 顶到的是**切片的行数**
         * 而不是可用行数，于是「整段放得下」这个判断永远成立：
         * 实测表现为分页在第一页就结束，整章被当成一页排出去，底部一大截被裁掉。
         *
         * 现在取一个与行数无关的固定上限：足够容纳一页（一般 30~150 行、
         * 每行至多百余字符），又不至于接近整章长度。
         * 关键是它**只用于省时间，不参与任何判断**（判断一律看实测高度）。
         */
        val probeChars = 8192

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
            val availableHeight = metrics.contentHeightFor(isFirstPage)

            val remaining = content.substring(start)
            val probeLength = minOf(remaining.length, probeChars)
            val slice = remaining.substring(0, probeLength)

            val layout = buildLayout(slice, width, paint)

            /**
             * 判定「这一页能放多少」，**只看实际高度**，不看行号推算。
             *
             * 之所以不用 `layout.lineCount <= maxLines` 来判断整段是否放得下：
             * 那个行数是在**探测切片**上量出来的。切片若被截短，行数就被人为压低，
             * 判断随之失真。而高度是切片真实排版出来的结果，切片本身已经
             * 取得足够长（见 probeChars），因此它可靠得多。
             */
            if (layout.height <= availableHeight) {
                // 探测切片本身就装得下。若切片已覆盖到章末，这一页就是最后一页；
                // 否则说明切片还没取完但已够一页 —— 仍然以整段切片为界，
                // 因为它的高度确实没超。
                pages.add(Page(pages.size, start, start + probeLength))
                start += probeLength
                if (probeLength >= remaining.length) break
                continue
            }

            /**
             * 装不下：从能放的最后一行往前退，直到**实测高度**确实装得进为止。
             *
             * 起始行数取 `min(lineCount, maxLines) - 1`：
             * 先按行数上限猜一个位置，再用高度校验它。两者取较小值，
             * 是因为高度与行数都可能成为约束（行距小的时候是高度先到，
             * 行距大的时候是行数先到）。
             *
             * 终止条件必须**同时**保证两件事：
             *   1. 不会无限循环 —— 每轮 lines 严格递减，且 lines 降到 0 就停；
             *   2. 每页至少前进一个字符 —— 即使某一行本身就高过容器
             *      （极端大字号），也要保证 `endInSlice` 落到第一行行尾而不是 0，
             *      否则整个分页会卡住。下面的 `lineEndAtLeastOneLine` 就是为此。
             */
            var lines = (minOf(layout.lineCount, maxLines) - 1).coerceAtLeast(0)
            var endInSlice = lineEndAtLeastOneLine(layout, lines)
            var endLayout = buildLayout(slice.substring(0, endInSlice), width, paint)

            while (endLayout.height > availableHeight && lines > 0) {
                lines--
                endInSlice = lineEndAtLeastOneLine(layout, lines)
                endLayout = buildLayout(slice.substring(0, endInSlice), width, paint)
            }

            var end = start + endInSlice

            // 双保险：无论如何都要前进，否则外层 while 会空转
            if (end <= start) {
                end = minOf(start + lineEndAtLeastOneLine(layout, 0), content.length)
            }
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
     *
     * `setIncludePad(false)` 必须与渲染侧的 `includeFontPadding = false` **成对出现**。
     *
     * 这是真机上「正文最后一行被裁掉、只剩字顶」的根因：
     * 这里本来就不含字体 padding，而 Compose 的 `Text` **默认包含**。
     * 同一段文字于是「量出来矮、画出来高」，最后一行越过底边被裁。
     * 早先靠把行高估大（fontSize × 行距倍数，比字体自然行高大约 30%）
     * 侥幸掩盖了这个差异，但它依赖字体度量恰好落在某个范围 ——
     * 换字体或换字号就可能失效，不能算修好。两侧口径一致才是正解。
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

    /**
     * 取第 `line` 行的行尾，但**至少保证第一行**。
     *
     * 为什么需要下限：如果某个单行本身就高过容器（超大字号 + 极矮视口），
     * 回退循环会一路退到 0 行，`getLineEnd(0)` 之外就没有可用的边界了。
     * 那时若不兜底，这一页的字符数会变成 0，外层循环再也不前进 —— 分页卡死。
     * 宁可让这一行被裁（用户能立刻看出字号太大），也不能让应用卡住。
     */
    private fun lineEndAtLeastOneLine(layout: StaticLayout, line: Int): Int {
        val safeLine = line.coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        return layout.getLineEnd(safeLine).coerceAtLeast(1)
    }

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
