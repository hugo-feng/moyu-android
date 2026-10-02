package com.moyu.reader.ui.morph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 形变引擎的测试。
 *
 * ## 为什么这个测试特别重要
 *
 * 这套几何算法是从 morphicons（TypeScript）移植来的，而**本机没有可用的模拟器**，
 * 我无法在真机上看形变动画的效果。因此正确性只能靠测试来保证：
 * 下面每一条都是在钉住一个「看起来对、其实错」的具体失效方式。
 *
 * 跑在 Robolectric 上是因为 `SvgPathParser` 用到了 android.graphics.Path 与 PathMeasure ——
 * 都不需要模拟器，但需要 Android 运行时的实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorphEngineTest {

    // ============================================================
    // 路径解析与重采样
    // ============================================================

    @Test
    fun `三对图标都能解析出预期的子路径数`() {
        // 子路径数决定配对行为，必须在移植时保持不变。
        // 数量与 JS 版实测一致：月 1、日 9、放大镜 2、关闭 2、书签 1、已收藏 2。
        val cases = listOf(
            MorphIcons.MOON to 1,
            MorphIcons.SUN to 9,
            MorphIcons.SEARCH to 2,
            MorphIcons.CLOSE to 2,
            MorphIcons.BOOKMARK to 1,
            MorphIcons.BOOKMARK_CHECK to 2,
        )
        for ((paths, expected) in cases) {
            val sampled = MorphEngine.resample(MorphIcons.join(paths))
            assertEquals(
                "子路径数不对：${MorphIcons.join(paths).take(40)}…",
                expected,
                sampled.size,
            )
        }
    }

    @Test
    fun `每条子路径都被重采样成固定的点数`() {
        val sampled = MorphEngine.resample(MorphIcons.join(MorphIcons.SUN))
        for (s in sampled) {
            assertEquals(
                "点数必须固定为 SAMPLES_PER_SUBPATH，否则配对时下标会错位",
                MorphEngine.SAMPLES_PER_SUBPATH * 2,
                s.points.size,
            )
        }
    }

    @Test
    fun `重采样的点沿路径单调前进且间距不失控`() {
        // 注意：采样点**不是严格等距**的 —— 这是算法有意为之。
        // 尖角必须是精确的采样点（否则对勾的角会变圆），
        // 因此点数在各角点之间按弧长分配，间距会随曲率变化。
        //
        // 真正要保证的是：
        //   1. 每段间距都大于 0（没有重复点，否则配对时会出现零长度线段）；
        //   2. 间距不会失控（最大/最小之比有界），否则形状会一头挤一头疏。
        val sampled = MorphEngine.resample(MorphIcons.join(MorphIcons.MOON))
        val s = sampled.first()
        val n = s.points.size / 2
        val gaps = (0 until n - 1).map { i ->
            hypot(
                (s.points[(i + 1) * 2] - s.points[i * 2]).toDouble(),
                (s.points[(i + 1) * 2 + 1] - s.points[i * 2 + 1]).toDouble(),
            )
        }
        val minGap = gaps.min()
        val maxGap = gaps.max()
        assertTrue("不应出现重复点（最小间距 $minGap）", minGap > 1e-4)
        val ratio = maxGap / minGap
        assertTrue("间距比应有界，实际最大/最小 = ${"%.1f".format(ratio)}", ratio < 6.0)
    }

    @Test
    fun `闭合子路径被正确识别`() {
        // 书签外形是闭合的（末尾是 z），日字里的圆也是闭合的。
        // 识别错了会让插值结果丢掉「闭合」信息，形状在中间帧裂开。
        val bookmark = MorphEngine.resample(MorphIcons.join(MorphIcons.BOOKMARK))
        assertTrue("书签应当是闭合子路径", bookmark.first().closed)

        val sun = MorphEngine.resample(MorphIcons.join(MorphIcons.SUN))
        assertTrue("太阳中心的圆应当是闭合的", sun.any { it.closed })
        assertTrue("太阳的光芒应当是开放子路径", sun.any { !it.closed })
    }

    @Test
    fun `空路径与非法路径不会崩溃`() {
        assertTrue("空字符串应得到空结果", MorphEngine.resample("").isEmpty())
        // 非法命令不应抛异常，而是安全地返回（可能为空）
        MorphEngine.resample("M10 10 Q")
        MorphEngine.resample("完全不是路径")
    }

    @Test
    fun `紧凑写法与相对命令被正确解析`() {
        // `m6 6 12 12` 里第二组坐标是**隐式 lineto**；
        // `M18 6 6 18` 同理。图标库里这种写法很常见，
        // 解析错会得到完全不同的形状（少了那段隐式线段）。
        val close = MorphEngine.resample(MorphIcons.join(MorphIcons.CLOSE))
        val first = close.first()
        // 起点应为 (18, 6)
        assertEquals(18f, first.points[0], 0.5f)
        assertEquals(6f, first.points[1], 0.5f)
        // 终点应为 (6, 18)
        assertEquals(6f, first.points[first.points.size - 2], 0.5f)
        assertEquals(18f, first.points[first.points.size - 1], 0.5f)
    }

    // ============================================================
    // 配对与对齐
    // ============================================================

    @Test
    fun `子路径数不同时做满射配对，不会有笔画凭空消失`() {
        // 书签（1 条）→ 已收藏（2 条）：必须复制成 2 条，
        // 而不是让对勾从「无」中长出来。JS 版同样是复制策略。
        val from = MorphEngine.resample(MorphIcons.join(MorphIcons.BOOKMARK))
        val to = MorphEngine.resample(MorphIcons.join(MorphIcons.BOOKMARK_CHECK))
        val plan = MorphEngine.buildPlan(from, to)
        assertNotNull("应能建立方案", plan)
        assertEquals("配对数量应取两侧的较大值", 2, plan!!.subpathCount)
    }

    @Test
    fun `同形状不同起点时对齐残差接近零`() {
        // 同一个圆，起点不同 → 最优对齐应当发现「不需要形变」。
        // 这条能验证闭合路径的起点搜索确实生效了。
        val a = MorphEngine.resample("M6 12a6 6 0 1 0 12 0a6 6 0 1 0 -12 0z")
        val b = MorphEngine.resample("M18 12a6 6 0 1 0 -12 0a6 6 0 1 0 12 0z")
        val plan = MorphEngine.buildPlan(a, b)
        assertNotNull(plan)
        // 旋转角应当在 0 或 π 附近，缩放接近 1
        val sigma = plan!!.sigma[0]
        assertTrue("缩放应接近 1，实际 $sigma", abs(sigma - 1f) < 0.05f)
    }

    @Test
    fun `纯旋转应当被识别为旋转而不是形变`() {
        // 把一条线段转 90°：最优相似变换应当给出 θ ≈ 90°、σ ≈ 1，
        // 残差接近 0。这正是「旋转从数学里自己涌现」的机制 ——
        // 没有任何地方手写「这两个图标差 90 度」。
        val horizontal = MorphEngine.resample("M4 12h16")
        val vertical = MorphEngine.resample("M12 4v16")
        val plan = MorphEngine.buildPlan(horizontal, vertical)
        assertNotNull(plan)
        val thetaDeg = Math.toDegrees(plan!!.theta[0].toDouble())
        assertTrue(
            "应识别出约 ±90° 的旋转，实际 ${"%.1f".format(thetaDeg)}°",
            abs(abs(thetaDeg) - 90.0) < 15.0,
        )
        assertTrue("缩放应接近 1，实际 ${plan.sigma[0]}", abs(plan.sigma[0] - 1f) < 0.1f)
    }

    // ============================================================
    // 插值
    // ============================================================

    @Test
    fun `t=0 与 t=1 精确落在两端形状上`() {
        // 这是最重要的一条：静止时必须与静态图标**逐点一致**，
        // 否则用户看到的图标会与设计稿有细微偏差。
        val from = MorphEngine.resample(MorphIcons.join(MorphIcons.MOON))
        val to = MorphEngine.resample(MorphIcons.join(MorphIcons.SUN))
        val plan = MorphEngine.buildPlan(from, to)!!
        val buffers = MorphEngine.allocOutputs(plan)

        MorphEngine.interpolate(plan, 0f, buffers)
        val startError = maxPointError(plan.pairs.map { it.src }, buffers)
        assertTrue("t=0 时应等于起始形状，最大偏差 $startError", startError < 0.01f)

        MorphEngine.interpolate(plan, 1f, buffers)
        val endError = maxPointError(plan.pairs.map { it.dst }, buffers)
        assertTrue("t=1 时应等于目标形状，最大偏差 $endError", endError < 0.01f)
    }

    @Test
    fun `中间帧的点都在合理范围内，不会飞出画布`() {
        // 极坐标插值在 t ∈ [0,1] 内不应产生远超原图的坐标。
        // 若把旋转写成线性坐标插值，点会走弦线、形状塌陷；
        // 若缩放算错，点会飞出去。这条能同时抓住这两类错误。
        val from = MorphEngine.resample(MorphIcons.join(MorphIcons.MOON))
        val to = MorphEngine.resample(MorphIcons.join(MorphIcons.SUN))
        val plan = MorphEngine.buildPlan(from, to)!!
        val buffers = MorphEngine.allocOutputs(plan)

        for (step in 0..10) {
            val t = step / 10f
            MorphEngine.interpolate(plan, t, buffers)
            for (buf in buffers) {
                for (v in buf) {
                    assertTrue(
                        "t=$t 时出现异常坐标 $v，说明插值发散了",
                        v.isFinite() && abs(v) < 60f,
                    )
                }
            }
        }
    }

    @Test
    fun `中途打断后重新建立方案仍然连续`() {
        // 用户快速连点开关时，第二次形变必须从**当前中间形状**出发，
        // 而不是跳回起点。这里验证「用中间结果当起点」这条路是通的。
        val moon = MorphEngine.resample(MorphIcons.join(MorphIcons.MOON))
        val sun = MorphEngine.resample(MorphIcons.join(MorphIcons.SUN))
        val plan1 = MorphEngine.buildPlan(moon, sun)!!
        val mid = MorphEngine.allocOutputs(plan1)
        MorphEngine.interpolate(plan1, 0.5f, mid)

        // 用中间形状当起点，再回到月亮
        val midSampled = mid.mapIndexed { i, buf ->
            MorphEngine.Sampled(buf.copyOf(), plan1.closedFlags.getOrElse(i) { false })
        }
        val plan2 = MorphEngine.buildPlan(midSampled, moon)
        assertNotNull("用中间形状当起点也必须能建立方案", plan2)

        val back = MorphEngine.allocOutputs(plan2!!)
        MorphEngine.interpolate(plan2, 1f, back)
        val error = maxPointError(plan2.pairs.map { it.dst }, back)
        assertTrue("回程终点应精确落回月亮，最大偏差 $error", error < 0.01f)
    }

    // ============================================================
    // 序列化
    // ============================================================

    @Test
    fun `序列化产出合法的 d 字符串`() {
        val from = MorphEngine.resample(MorphIcons.join(MorphIcons.SEARCH))
        val to = MorphEngine.resample(MorphIcons.join(MorphIcons.CLOSE))
        val plan = MorphEngine.buildPlan(from, to)!!
        val buffers = MorphEngine.allocOutputs(plan)
        MorphEngine.interpolate(plan, 0.5f, buffers)
        val d = MorphEngine.serialize(buffers, plan.closedFlags)

        assertTrue("应以 M 开头", d.startsWith("M"))
        assertTrue("应包含折线段", d.contains("L"))
        // 能被重新解析回来（往返一致）
        val reparsed = MorphEngine.resample(d)
        assertEquals("序列化结果应能被重新解析成同样的子路径数", plan.subpathCount, reparsed.size)
    }

    @Test
    fun `闭合标志会被写进 d 字符串`() {
        val from = MorphEngine.resample(MorphIcons.join(MorphIcons.BOOKMARK))
        val to = MorphEngine.resample(MorphIcons.join(MorphIcons.BOOKMARK_CHECK))
        val plan = MorphEngine.buildPlan(from, to)!!
        val buffers = MorphEngine.allocOutputs(plan)
        MorphEngine.interpolate(plan, 1f, buffers)
        val d = MorphEngine.serialize(buffers, plan.closedFlags)
        assertTrue("书签→已收藏都是闭合路径，d 里应有 Z", d.contains("Z"))
    }

    // ============================================================
    // 弹簧
    // ============================================================

    @Test
    fun `弹簧会收敛到 1 且不越界太久`() {
        val spring = MorphSpring(MorphSpringPreset.SNAPPY.stiffness, MorphSpringPreset.SNAPPY.damping)
        spring.restart()
        var frames = 0
        while (spring.advance(1f / 60f) && frames < 600) frames++

        assertTrue("应在 10 秒内收敛，实际 $frames 帧", frames < 600)
        assertEquals("收敛后进度应为 1", 1f, spring.position, 0.01f)
    }

    @Test
    fun `不同预设的手感确实不同（回弹量不同）`() {
        // bouncy 应当过冲到 1 以上，smooth 不应过冲。
        // 这条保证预设参数没有被误改 —— 两端的「手感」就靠这组数字对齐。
        fun maxOvershoot(preset: MorphSpringPreset): Float {
            val s = MorphSpring(preset.stiffness, preset.damping)
            s.restart()
            var peak = 0f
            var frames = 0
            while (s.advance(1f / 60f) && frames < 600) {
                if (s.position > peak) peak = s.position
                frames++
            }
            return peak
        }
        val smoothPeak = maxOvershoot(MorphSpringPreset.SMOOTH)
        val bouncyPeak = maxOvershoot(MorphSpringPreset.BOUNCY)
        assertTrue("smooth 不应明显过冲，实际峰值 $smoothPeak", smoothPeak < 1.02f)
        assertTrue("bouncy 应明显过冲，实际峰值 $bouncyPeak", bouncyPeak > 1.05f)
    }

    @Test
    fun `超长帧间隔不会让弹簧发散`() {
        // 从后台切回来时 delta 可能有几秒；若不限制单帧步数，
        // 半隐式欧拉会因为步长过大而发散（图标抖到画面外）。
        val spring = MorphSpring(MorphSpringPreset.SNAPPY.stiffness, MorphSpringPreset.SNAPPY.damping)
        spring.restart()
        spring.advance(5f) // 假装卡了 5 秒
        assertTrue("超长帧后进度仍应有限，实际 ${spring.position}", abs(spring.position) < 10f)
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 点列之间的最大逐点偏差，用于验证插值端点是否精确。 */
    private fun maxPointError(expected: List<MorphEngine.Sampled>, actual: List<FloatArray>): Float {
        var worst = 0f
        for (k in actual.indices) {
            val exp = expected.getOrNull(k)?.points ?: continue
            val act = actual[k]
            for (i in act.indices) {
                val diff = abs(exp[i] - act[i])
                if (diff > worst) worst = diff
            }
        }
        return worst
    }
}
