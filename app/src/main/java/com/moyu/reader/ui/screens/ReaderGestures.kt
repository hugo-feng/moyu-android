package com.moyu.reader.ui.screens

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed

/**
 * 阅读页的手势层。
 *
 * ## 它必须挂在**父节点**上，绝不能是一张盖在正文之上的兄弟层
 *
 * 这一点是这个文件存在的全部理由，也是「滚动模式完全不能滚」反复修不好的
 * 真正原因：
 *
 * Compose 的命中测试在碰到一个指针输入节点、而它没有声明
 * 「与兄弟共享」时，会**直接截断**，画在它下面的兄弟节点根本不会进入命中路径。
 * 源码见 `NodeCoordinator.PointerInputSource.shareWithSiblings`：
 *
 * ```kotlin
 * if (child.outerCoordinator.shouldSharePointerInputWithSiblings()) {
 *     hitTestResult.acceptHits(); return true   // 继续命中下面的兄弟
 * }
 * return false                                  // 就此打住
 * ```
 *
 * 而 `Modifier.pointerInput` 建出来的节点，`sharePointerInputWithSiblings`
 * 默认就是 **false**。于是：
 *
 * ```
 * Box {                       // 之前的写法
 *     Column(verticalScroll)  // ← 收不到任何事件，因为它在下面
 *     Box(fillMaxSize().pointerInput{})   // ← 全屏手势层，把命中截断了
 * }
 * ```
 *
 * 「只要不消费事件，滚动就能继续收到拖动」—— 这个想法是错的：
 * **事件压根没送到滚动层**。换正文控件（SelectionContainer / BasicTextField /
 * 普通 Text）当然也都没用。
 *
 * 现在的写法：手势层是**根节点自己**（父），子树（滚动容器、按钮、面板）
 * 都是它的孩子。父子之间不会互相截断，且 Main 阶段是冒泡的 ——
 * 子节点先处理。子节点消费过的手势（滚动、点到了按钮、拖了进度条），
 * 这里就不再重复处理。
 *
 * ## 判定规则
 *
 *   - 位移没超过 `touchSlop` 且子树没消费 → 算点击，交给 [onTap]；
 *   - 横向累计位移超过阈值、且大于纵向位移 → 算横滑，交给 [onHorizontalSwipe]；
 *   - 纵向拖动不作处理（子树里的 `verticalScroll` 已经在滚了）。
 *
 * 之所以要**累计**位移而不是看单帧增量：一次滑动会产生很多个超过阈值的
 * move 事件，逐帧判定会让用户一划连翻好几页。
 *
 * @param onTap 点击：参数是按下位置与这一层的宽度（像素），由调用方按三热区决定行为
 * @param onHorizontalSwipe 横滑：参数是累计横向位移（负=向左划）
 */
internal fun Modifier.readerPageGestures(
    gestureKey: Any?,
    /** 点击时是否交给调用方。面板打开等场景直接返回 false 即可完全不处理点击。 */
    onTap: (position: Offset, widthPx: Int) -> Boolean,
    onHorizontalSwipe: (totalX: Float) -> Unit,
    /** 横滑阈值（像素）。默认 56dp 的手感在真机上与主流阅读器接近。 */
    flipThresholdPx: Float,
): Modifier = pointerInput(gestureKey) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 子树是否已经接手这次手势（滚动正文、点到了按钮、拖了进度条…）
            var consumed = down.isConsumed
            var totalX = 0f
            var totalY = 0f
            var moved = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) consumed = true
                if (!change.pressed) break
                // 必须用 IgnoreConsumed：滚动层消费过的位移用 positionChange()
                // 读出来是 0，位移就永远累计不起来
                val delta = change.positionChangeIgnoreConsumed()
                totalX += delta.x
                totalY += delta.y
                if (!moved && kotlin.math.hypot(totalX, totalY) > slop) moved = true
            }

            if (consumed) continue

            if (!moved) {
                onTap(down.position, size.width)
            } else if (kotlin.math.abs(totalX) > flipThresholdPx &&
                kotlin.math.abs(totalX) > kotlin.math.abs(totalY)
            ) {
                onHorizontalSwipe(totalX)
            }
        }
    }
}
