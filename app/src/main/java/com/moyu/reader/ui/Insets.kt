package com.moyu.reader.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 系统栏（状态栏 / 手势条 / 挖孔）适配。
 *
 * 为什么必须有这个文件：
 *   MainActivity 开了 `enableEdgeToEdge()`，内容会一直画到状态栏与导航栏底下 ——
 *   这本身是想要的，阅读页的护色遮罩需要盖住整屏。但**内容必须自己让开系统栏**，
 *   否则在真机上就是「正文第一行钻进状态栏」「底部导航被手势条压住」。
 *   没有这一层，边到边就等于内容被系统 UI 吃掉一块。
 *
 * 为什么用 safeDrawing 而不是 systemBars：
 *   `safeDrawing` = `systemBars` ∪ `displayCutout` ∪ `ime`。
 *   小米 14 这类居中挖孔屏横屏时挖孔落在左右边缘，只处理 systemBars 会漏掉它。
 *   safeDrawing 一次覆盖全，且是官方推荐的「内容不该被遮挡」边界。
 */

/** 顶部安全区（状态栏 + 挖孔）。 */
val safeTop: Dp
    @Composable get() = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

/** 底部安全区（导航栏 / 手势条）。 */
val safeBottom: Dp
    @Composable get() = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

/** 左右安全区（横屏挖孔）。取较宽的一侧，保证两侧对称留白。 */
val safeHorizontal: Dp
    @Composable get() {
        val direction = LocalLayoutDirection.current
        val values = WindowInsets.safeDrawing.asPaddingValues()
        return maxOf(
            values.calculateLeftPadding(direction),
            values.calculateRightPadding(direction),
        )
    }

/**
 * 让开顶部系统栏。只加内边距，不动背景 ——
 * 顶部栏的背景色仍延伸到状态栏底下，文字与按钮落到安全区内。
 */
fun Modifier.safeDrawingTopPadding(): Modifier = composed { padding(top = safeTop) }

/** 让开底部系统栏。用于底部导航栏与阅读页地脚。 */
fun Modifier.safeDrawingBottomPadding(): Modifier = composed { padding(bottom = safeBottom) }

/** 让开上下（不含左右）。用于滚动列表，让首尾项不被系统栏压住。 */
fun Modifier.safeDrawingVerticalPadding(): Modifier = composed {
    padding(top = safeTop, bottom = safeBottom)
}

/** 让开全部安全区。用于底部弹出面板这类需要完整避开系统 UI 的内容块。 */
fun Modifier.safeDrawingPadding(): Modifier = composed {
    val direction = LocalLayoutDirection.current
    val values = WindowInsets.safeDrawing.asPaddingValues()
    padding(
        top = values.calculateTopPadding(),
        bottom = values.calculateBottomPadding(),
        start = values.calculateLeftPadding(direction),
        end = values.calculateRightPadding(direction),
    )
}

/** 状态栏高度（像素）。供分页计算使用：可用高度必须减去它。 */
@Composable
fun statusBarHeightPx(): Int {
    val density = LocalDensity.current
    return with(density) { safeTop.roundToPx() }
}

/** 导航栏高度（像素）。供分页计算使用。 */
@Composable
fun navigationBarHeightPx(): Int {
    val density = LocalDensity.current
    return with(density) { safeBottom.roundToPx() }
}

/**
 * 安全区的保守下限。
 *
 * 意义：万一某个 ROM 在极早期阶段返回 0（或 Compose 拿不到 insets），
 * 也不会退化成「内容贴边」。取值偏保守而不是偏大 ——
 * 多留一点空白只是不够精致，被系统栏盖住则是功能缺陷。
 */
object InsetFallback {
    /** 状态栏保守高度（主流机型 24–28dp，小米 14 约 28dp）。 */
    val STATUS_BAR = 24.dp

    /** 手势条保守高度（主流 ROM 约 16dp）。 */
    val GESTURE_BAR = 16.dp
}
