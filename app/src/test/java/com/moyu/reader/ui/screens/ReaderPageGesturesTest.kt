package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阅读页手势层的行为测试（在 JVM 上真的注入触摸事件，不需要模拟器）。
 *
 * ## 为什么必须有这个测试类
 *
 * 「滚动模式完全不能滚」前后修了两轮都没修对，根因不在正文控件，而在于
 * **手势层是一张盖在正文之上的全屏兄弟层**：Compose 的命中测试在碰到
 * 没有声明共享的指针输入节点时会直接截断，下面的 `verticalScroll`
 * **一个事件都收不到**。这种错误靠读代码、靠「我改成了不消费事件」
 * 都看不出来 —— 只有真的往界面里注入一次滑动、再看滚动位置变没变，
 * 才能证伪。
 *
 * 所以这三条测试守的是：
 *   1. 父节点上的手势层**不能**挡住子树的滚动（回归测试，就是上面那个 bug）；
 *   2. 点击仍然能被手势层收到（子树没人消费时）；
 *   3. 子树消费过的手势（例如点到了一个按钮）**不能**再被当成翻页点击。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPageGesturesTest {

    @get:Rule
    val compose = createComposeRule()

    /** 够长的正文，保证一定滚得动。 */
    private val lines = (1..200).map { "第 $it 行：这是一段用于验证滚动的手势测试正文。" }

    @Test
    fun `父节点上的手势层不会挡住子树的滚动`() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var taps = 0

        compose.setContent {
            scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 与阅读页完全一样的挂法：手势层挂在**根节点**上
                    .readerPageGestures(
                        gestureKey = Unit,
                        flipThresholdPx = 120f,
                        onTap = { _, _ -> taps++; true },
                        onHorizontalSwipe = { },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .testTag("body"),
                ) {
                    lines.forEach { Text(it) }
                }
            }
        }

        compose.onNodeWithTag("body").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue(
            "向上滑动后滚动位置必须变大，实际 ${scroll.value} —— " +
                "为 0 说明手势层把命中测试截断了，子树根本没收到事件",
            scroll.value > 0,
        )
        assertEquals("这是一次滑动，不该被判成点击", 0, taps)
    }

    @Test
    fun `子树没人消费时点击仍然会被手势层收到`() {
        var taps = 0
        var lastX = -1f
        var width = 0

        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readerPageGestures(
                        gestureKey = Unit,
                        flipThresholdPx = 120f,
                        onTap = { position, widthPx ->
                            taps++
                            lastX = position.x
                            width = widthPx
                            true
                        },
                        onHorizontalSwipe = { },
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text("正文".repeat(50), modifier = Modifier.testTag("body"))
                }
            }
        }

        compose.onNodeWithTag("body").performTouchInput { click(Offset(30f, 30f)) }
        compose.waitForIdle()

        assertEquals("普通一次点击必须被收到", 1, taps)
        assertTrue("点击位置应当被传出来", lastX >= 0f && width > 0)
    }

    @Test
    fun `子树消费过的手势不会再被当成点击`() {
        var taps = 0

        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readerPageGestures(
                        gestureKey = Unit,
                        flipThresholdPx = 120f,
                        onTap = { _, _ -> taps++; true },
                        onHorizontalSwipe = { },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.LightGray)
                        .testTag("button")
                        // clickable 会消费 down/up —— 模拟读者点到工具栏按钮
                        .clickable { },
                )
            }
        }

        compose.onNodeWithTag("button").performTouchInput { click(Offset(20f, 20f)) }
        compose.waitForIdle()

        assertEquals(
            "子树已经消费的点击不该再触发翻页热区（否则点按钮会顺带翻页）",
            0,
            taps,
        )
    }

    /**
     * 把「老写法为什么必然失效」也钉住。
     *
     * 这不是在测我们的代码，而是在测 **Compose 的命中测试规则**：
     * 一个 `Modifier.pointerInput` 建出来的全屏兄弟层，会把命中测试截断，
     * 画在它下面的滚动容器收不到任何事件 —— 无论那层有没有消费事件。
     *
     * 如果哪天这条测试开始失败（滚动位置不再为 0），说明 Compose 改了这条规则，
     * 那时才可以考虑回到「叠加一层手势」的写法。在此之前，
     * 手势层必须留在父节点上。
     */
    @Test
    fun `盖在正文之上的兄弟手势层会让滚动彻底失效`() {
        lateinit var scroll: androidx.compose.foundation.ScrollState

        compose.setContent {
            scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .testTag("body"),
                ) {
                    lines.forEach { Text(it) }
                }
                // 老写法：全屏兄弟层 + pointerInput（默认不与兄弟共享命中）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown(requireUnconsumed = false)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.all { !it.pressed }) break
                                    }
                                }
                            }
                        },
                )
            }
        }

        compose.onNodeWithTag("body").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(
            "兄弟手势层没有声明 sharePointerInputWithSiblings，命中测试会被它截断，" +
                "下面的 verticalScroll 一个事件都收不到 —— 滚动位置必须纹丝不动。" +
                "若这里不再是 0，说明 Compose 改了命中规则，可以把结论推翻了。",
            0,
            scroll.value,
        )
    }

    @Test
    fun `横向滑动会触发热区回调`() {
        var swipes = 0
        var lastTotalX = 0f

        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readerPageGestures(
                        gestureKey = Unit,
                        flipThresholdPx = 120f,
                        onTap = { _, _ -> true },
                        onHorizontalSwipe = { swipes++; lastTotalX = it },
                    ),
            ) {
                // 铺满整屏再打 tag：这样 swipeLeft() 的默认起止点跨越整个宽度，
                // 位移一定超过阈值（挂在正文 Text 上时，节点宽度只有几十像素，
                // 起止点会算出 startX < endX 而直接抛参数错误）
                Box(modifier = Modifier.fillMaxSize().testTag("body")) {
                    Text("正文".repeat(10))
                }
            }
        }

        compose.onNodeWithTag("body").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("一次左滑应当触发一次横滑回调", 1, swipes)
        assertTrue("左滑的累计位移应当为负，实际 $lastTotalX", lastTotalX < 0f)
    }
}
