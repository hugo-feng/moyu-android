package com.moyu.reader.ui.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 把形变引擎的中间帧导出成 SVG，用于人工目视确认。
 *
 * ## 为什么需要这个
 *
 * 本机没有可用的模拟器，形变动画**无法在真机上观察**。
 * 单元测试能保证「端点精确、不发散、配对正确」，但保证不了
 * 「中间帧看起来像不像那么回事」—— 那需要眼睛。
 *
 * 因此这里把引擎在各 t 值下的输出写成 SVG：SVG 与 Android 的 Path 是同一套
 * 几何模型，可以直接在浏览器里打开，也就把「看不见的 Android 渲染」
 * 变成了「看得见的矢量图」。这是在没有设备的前提下能做到的最强验证。
 *
 * 产物写在 `build/morph-preview/` 下，用浏览器打开 index.html 即可。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorphPreviewExportTest {

    /** 与 Web 验证器一致的取色，便于两端并排对比。 */
    private val ink = "#33302B"

    private val pairs = listOf(
        "夜间" to (MorphIcons.MOON to MorphIcons.SUN),
        "搜索" to (MorphIcons.SEARCH to MorphIcons.CLOSE),
        "书签" to (MorphIcons.BOOKMARK to MorphIcons.BOOKMARK_CHECK),
    )

    @Test
    fun `导出三对图标的形变中间帧`() {
        val outDir = File("build/morph-preview").apply { mkdirs() }
        val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        val sections = StringBuilder()

        for ((label, icons) in pairs) {
            val (fromPaths, toPaths) = icons
            val from = MorphEngine.resample(MorphIcons.join(fromPaths))
            val to = MorphEngine.resample(MorphIcons.join(toPaths))
            val plan = MorphEngine.buildPlan(from, to)

            assertTrue("「$label」这一对应当能建立形变方案", plan != null)
            val buffers = MorphEngine.allocOutputs(plan!!)

            val frames = steps.joinToString("") { t ->
                MorphEngine.interpolate(plan, t, buffers)
                val d = MorphEngine.serialize(buffers, plan.closedFlags)
                """
                <div class="frame">
                  <svg viewBox="0 0 24 24" width="72" height="72" fill="none" stroke="$ink"
                       stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="$d"/>
                  </svg>
                  <div class="t">t=$t</div>
                </div>
                """.trimIndent()
            }

            sections.append(
                """
                <section>
                  <h2>$label（子路径 ${plan.subpathCount}）</h2>
                  <div class="row">$frames</div>
                </section>
                """.trimIndent(),
            )
        }

        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <meta charset="utf-8">
            <title>墨阅 · 图标形变中间帧</title>
            <style>
              body { margin: 0; padding: 24px; background: #F7F3EA; color: #33302B;
                     font-family: system-ui, "Microsoft YaHei", sans-serif; }
              h1 { font-size: 18px; margin: 0 0 4px; }
              p.note { font-size: 12px; color: #6a6459; margin: 0 0 20px; max-width: 720px; line-height: 1.6; }
              section { margin-bottom: 24px; }
              h2 { font-size: 13px; color: #5c564d; font-weight: 600; margin: 0 0 8px; }
              .row { display: flex; gap: 14px; align-items: flex-start; }
              .frame { text-align: center; }
              .frame svg { background: #fff; border-radius: 12px;
                           box-shadow: 0 1px 4px rgba(0,0,0,.10); }
              .t { font-size: 10px; color: #6a6459; margin-top: 4px; }
            </style>
            <h1>墨阅 · 图标形变中间帧（由 Android 引擎计算）</h1>
            <p class="note">
              这些路径是 <strong>Kotlin 版形变引擎</strong>在 t = 0 / 0.25 / 0.5 / 0.75 / 1 处的真实输出，
              序列化成 SVG 后渲染。SVG 与 Android 的 Path 是同一套几何模型，
              因此这张图就是「手机上会画出来什么」——在没有可用模拟器的前提下，
              这是能对形变做的最直接的目视验证。
              两端使用同一份路径数据与同一组弹簧参数（见 morphIcons.ts / MorphIcons.kt）。
            </p>
            $sections
            </html>
        """.trimIndent()

        File(outDir, "index.html").writeText(html, Charsets.UTF_8)

        val index = File(outDir, "index.html")
        assertTrue("预览文件应当生成", index.exists() && index.length() > 1000)
        println("形变预览已导出：${index.absolutePath}")
    }

    /**
     * 走完一整轮形变动画（模拟 60fps 直到弹簧静止）。
     *
     * 这条替代了原本想写的 Compose 组件测试 —— 那个需要 `ui-test-junit4`，
     * 而项目只把它配在 androidTest（要有真机）。这里改为验证**动画时序**本身：
     * 用弹簧驱动引擎逐帧插值，确认每一帧都能产出合法路径、且最终停在 t=1。
     *
     * 之所以要单独测：真实故障是「弹簧永不静止导致每帧都在重绘」，
     * 那在真机上表现为发热与掉帧，而端点断言抓不到它。
     */
    @Test
    fun `完整动画时序逐帧产出合法路径并收敛到目标`() {
        for ((label, icons) in pairs) {
            val (fromPaths, toPaths) = icons
            val from = MorphEngine.resample(MorphIcons.join(fromPaths))
            val to = MorphEngine.resample(MorphIcons.join(toPaths))
            val plan = MorphEngine.buildPlan(from, to)!!

            val spring = MorphSpring(MorphSpringPreset.SNAPPY.stiffness, MorphSpringPreset.SNAPPY.damping)
            spring.restart()
            val buffers = MorphEngine.allocOutputs(plan)

            var frames = 0
            while (spring.advance(1f / 60f) && frames < 600) {
                MorphEngine.interpolate(plan, spring.position, buffers)
                val d = MorphEngine.serialize(buffers, plan.closedFlags)
                assertTrue("「$label」第 $frames 帧应产出非空路径", d.length > 4)
                frames++
            }

            assertTrue("「$label」应在 10 秒内收敛，实际 $frames 帧", frames < 600)

            // 收敛后必须**足够接近**目标形状。
            //
            // 不能断言「与 t=1 逐字符相同」：弹簧在 |x−1| < 0.001 且 |v| < 0.02
            // 时判定静止（与参考实现同口径），此时并未精确等于 1。
            // 那点差距在 24px 网格上约 0.001px，远低于一个像素，
            // 强行吸附到 1 反而会让动画在最后一帧有个肉眼看不见但真实存在的突跳。
            //
            // 这里断言的是真正重要的性质：静止时的形状与目标形状的**最大逐点偏差**
            // 必须远小于一个像素的量级。
            MorphEngine.interpolate(plan, spring.position, buffers)
            val settled = buffers.map { it.copyOf() }
            MorphEngine.interpolate(plan, 1f, buffers)
            var worst = 0f
            for (k in settled.indices) {
                for (i in settled[k].indices) {
                    val diff = kotlin.math.abs(settled[k][i] - buffers[k][i])
                    if (diff > worst) worst = diff
                }
            }
            assertTrue(
                "「$label」静止时与目标形状的最大偏差应远小于 1 个用户单位，实际 $worst",
                worst < 0.05f,
            )
        }
    }
}
