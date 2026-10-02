package com.moyu.reader.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.max

/**
 * 正文容器高度与行数的关系。
 *
 * ## 这个测试为什么存在
 *
 * 真机上出现过「正文最后一行被裁掉、只剩字顶一点」。根因是**两侧口径不一致**：
 *
 *   - `PaginationEngine` 用 `setIncludePad(false)`（不含字体 padding）
 *   - 渲染侧的 Compose `Text` **默认包含**字体 padding
 *
 * 于是同一段文字「量出来矮、画出来高」，最后一行越过底边被裁。
 * 更隐蔽的是它只在某些字体度量下暴露：正文行高是 `字号 × 行距倍数`
 * （默认约 1.7 倍字号），比字体自然行高大不少，差异常被掩盖；
 * 一旦行距调到接近 1.0，掩盖的量就不够了。
 *
 * 所以这里断言的是**不变量**而不是某个具体数字：
 * 用与渲染完全相同的参数排版 N 行，其总高度必须不超过容器的可用高度。
 * 只要两侧口径一致，这条在**任何字体、任何字号、任何行距**下都成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaginationHeightInvariantTest {

    /** 与 PaginationEngine.buildLayout 完全相同的排版参数。 */
    private fun layout(text: String, width: Int, paint: android.text.TextPaint): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private fun paint(textSizePx: Float, bold: Boolean = false) =
        PaginationEngine.buildTextPaint(
            textSizePx = textSizePx,
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
        )

    /**
     * 文字行必须包含换行。
     *
     * Robolectric 的 StaticLayout **不会对不含换行的 CJK 长文本断行**
     * （实测 600 字在 948px 宽下只排出 1 行），拿它验证「行数与高度」
     * 会得到恒为 1 行的假结果，测试等于没写。
     */
    private val content = (1..400).joinToString("\n") { "第${it}行正文内容，用来把这一章撑到足够长以排出多行。" }

    /**
     * 核心不变量：**分页引擎给出的每一页**，其文字单独排版后都必须装得进容器。
     *
     * 这里必须走真实的 `PaginationEngine.paginate`，不能自己复刻一遍取行逻辑 ——
     * 复刻就等于「用被测代码的错误假设去验证被测代码」，永远验不出问题。
     * （早先这版测试就是这么写的，结果引擎改了它还是报同样的错，等于没测。）
     *
     * 之所以要「单独排版每一页的文字」再量高度：这是渲染时的实际情况 ——
     * 页面拿到的是本页的字符串，由 Compose 自行排版。若分页时的边界
     * 与渲染时的断行不一致，这里量出的高度就会超过容器。
     */
    private fun assertEnginePagesFit(
        contentWidth: Int,
        contentHeight: Int,
        textSizePx: Float,
        lineHeightMultiplier: Float,
        note: String,
    ) {
        val lineHeightPx = textSizePx * lineHeightMultiplier
        val metrics = PaginationEngine.Metrics(
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            lineHeight = max(1, lineHeightPx.toInt()),
        )
        val p = paint(textSizePx)
        val pages = PaginationEngine.paginate(content, metrics, p)

        assert(pages.size > 1) { "$note：应当分出多页，实际 ${pages.size} 页" }

        var worstOverflow = 0
        var worstPage = -1
        var worstLines = 0
        for (page in pages) {
            val text = PaginationEngine.pageText(content, page)
            // 渲染时的正文会把行首空白剥掉、并可能插入段落空行；
            // 这里按最宽松的情形估：直接用原文切片排版。
            if (text.isBlank()) continue
            val l = layout(text, contentWidth, p)
            val overflow = l.height - contentHeight
            if (overflow > worstOverflow) {
                worstOverflow = overflow
                worstPage = page.index
                worstLines = l.lineCount
            }
        }

        if (worstOverflow > 0) {
            // 诊断：把每页的字符数与实际行数打出来，看清是哪一页异常
            println("--- $note ---")
            println("容器高=$contentHeight 行高=${"%.1f".format(lineHeightPx)} maxLines=${metrics.maxLines}")
            println("总页数=${pages.size} 正文字符数=${content.length}")
            for (page in pages) {
                val t = PaginationEngine.pageText(content, page)
                if (t.isBlank()) continue
                val l = layout(t, contentWidth, p)
                println("  第${page.index}页: 字符=${t.length} 行数=${l.lineCount} 高=${l.height} ${if (l.height > contentHeight) "← 超出 ${l.height - contentHeight}" else ""}")
            }
        }

        assert(worstOverflow <= 0) {
            "$note：第 $worstPage 页的文字实际高出容器 ${worstOverflow}px" +
                "（容器 ${contentHeight}px、行高 ${"%.1f".format(lineHeightPx)}px、$worstLines 行）。" +
                "超出部分会被 clipToBounds 裁掉 —— 真机上就是「底部字只剩字顶」。"
        }
    }

    @Test
    fun `常见排版参数下每一页都装得进容器`() {
        // 覆盖用户可能调到的整个范围，而不是只测默认值 ——
        // 这个 bug 恰恰是在偏离默认值时才暴露的。
        val widths = listOf(948, 748, 720)
        val heights = listOf(2016, 1800, 1600)
        val sizes = listOf(14f, 19f, 24f, 30f)
        val multipliers = listOf(1.0f, 1.2f, 1.5f, 1.7f, 2.2f)

        var checked = 0
        for (w in widths) {
            for (h in heights) {
                for (s in sizes) {
                    for (m in multipliers) {
                        assertEnginePagesFit(w, h, s, m, "宽${w} 高${h} 字号${s} 行距${m}")
                        checked++
                    }
                }
            }
        }
        println("已在 $checked 组参数下验证「每一页都装得进容器」")
    }

    @Test
    fun `行距越小越容易暴露口径不一致`() {
        // 行距接近 1.0 时，字体 padding 与断行误差占的比例最大 ——
        // 这正是真机报障时的参数区间。单独测一遍，防止以后有人
        // 把 includeFontPadding 改成 true 又恰好只在默认行距下自测。
        for (m in listOf(1.0f, 1.05f, 1.1f, 1.15f)) {
            assertEnginePagesFit(948, 2016, 19f, m, "行距 ${m}")
            assertEnginePagesFit(948, 1600, 24f, m, "行距 ${m} 大字号")
        }
    }

    @Test
    fun `分页口径与实际渲染高度一致`() {
        /**
         * 直接验证「量出来的高度」与「行数 × 行高」相符。
         *
         * 两侧口径一致时，StaticLayout 的高度应当约等于 `行数 × 行高`
         * （行高由 StaticLayout 自己按字体度量决定，这里只验证不出现
         * 「比行数 × 行高还多出一大截」的情况 —— 那正是字体 padding 的痕迹）。
         */
        val textSizePx = 19f * 3f // 小米 14 的 density 约 3
        val width = 948
        val l = layout(content.take(2000), width, paint(textSizePx))

        assert(l.lineCount > 1) { "应当排出多行，实际 ${l.lineCount} 行（测试数据可能不含换行）" }

        val lineHeight = l.getLineBottom(1) - l.getLineBottom(0)
        val expected = l.lineCount * lineHeight
        val excess = l.height - expected

        println("行数=${l.lineCount} 单行高=$lineHeight 实测总高=${l.height} 理论=$expected 差=$excess")
        // 允许最后一行 descent 造成的一点差异，但不应达到「一整行」的量级
        assert(excess <= lineHeight) {
            "实际高度比「行数 × 行高」多出 ${excess}px（超过一整行 $lineHeight），" +
                "说明仍有未对齐的纵向留白（字体 padding 或 lineSpacing 额外量）。"
        }
    }

    @Test
    fun `分页结果拼接后与原文逐字相同`() {
        // 分页只是「切片」，不能丢字也不能重复 —— 这是最基本的不变量。
        val metrics = PaginationEngine.Metrics(
            contentWidth = 948,
            contentHeight = 2016,
            lineHeight = ceil(19f * 3f * 1.7f).toInt(),
        )
        val pages = PaginationEngine.paginate(content, metrics, paint(19f * 3f))

        assert(pages.size > 1) { "应当分出多页，实际 ${pages.size} 页" }
        assert(pages.first().start == 0) { "首页应从 0 开始" }
        assert(pages.last().end == content.length) { "末页应到章末" }

        val joined = pages.joinToString("") { PaginationEngine.pageText(content, it) }
        assert(joined == content) {
            "分页拼接后与原文不一致：拼接长度 ${joined.length}，原文 ${content.length}"
        }
        // 页边界必须严格连续，不能有重叠或空隙
        for (i in 0 until pages.size - 1) {
            assert(pages[i].end == pages[i + 1].start) {
                "第 $i 页与第 ${i + 1} 页边界不连续：${pages[i].end} != ${pages[i + 1].start}"
            }
        }
    }
}
