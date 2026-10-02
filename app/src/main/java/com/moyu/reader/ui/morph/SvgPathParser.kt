package com.moyu.reader.ui.morph

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG `d` 路径解析与弧长重采样。
 *
 * ## 为什么完全不依赖 android.graphics.PathMeasure
 *
 * 最初这版用 `PathMeasure.getPosTan()` 做等距采样，看起来是最省事的做法 ——
 * 但它在 **Robolectric 下返回的长度恒为 0**（原生方法没有影子实现），
 * 于是所有路径都解析成空，17 条形变测试里 12 条失败。
 *
 * 更关键的是：依赖平台能力就意味着**离线测不了**，而这套算法恰恰是本机
 * 唯一能验证正确性的途径（没有可用模拟器）。因此改为自己积分贝塞尔弧长 ——
 * 这与 morphicons 的 JS 版做法**完全一致**（8 点高斯-勒让德求积），
 * 顺带让两端的采样结果可以逐点对齐。
 *
 * ## 输出
 *
 * 每条子路径 → 固定 64 个按弧长等距分布的点 + 是否闭合。
 * 点数固定是算法的前提：子路径配对靠下标一一对应，点数不一致就无法插值。
 */
internal object SvgPathParser {

    /** 每条子路径采样多少个点。与 [MorphEngine.SAMPLES_PER_SUBPATH] 一致。 */
    private const val SAMPLES = MorphEngine.SAMPLES_PER_SUBPATH

    /** 一段三次贝塞尔：起点、两个控制点、终点。 */
    private class Cubic(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float)

    /** 一条子路径：若干段三次贝塞尔 + 是否闭合。 */
    private class Contour {
        val cubics = ArrayList<Cubic>()
        var closed = false
    }

    fun parse(d: String): List<MorphEngine.Sampled> {
        val contours = parseCubics(d)
        val out = ArrayList<MorphEngine.Sampled>(contours.size)
        for (c in contours) {
            val sampled = sampleContour(c)
            if (sampled != null) out.add(sampled)
        }
        return out
    }

    // ============================================================
    // 第一步：解析成三次贝塞尔
    // ============================================================

    private fun parseCubics(d: String): List<Contour> {
        val contours = ArrayList<Contour>()
        var current = Contour()
        var hasCurrent = false

        var cx = 0f
        var cy = 0f
        // 上一条曲线的第二控制点（S / T 的隐式反射要用）
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var startX = 0f
        var startY = 0f
        var lastCmd = ' '

        fun finish(closed: Boolean) {
            if (!hasCurrent) return
            current.closed = closed
            if (current.cubics.isNotEmpty()) contours.add(current)
            current = Contour()
            hasCurrent = false
        }

        /** 直线段也转成三次贝塞尔（控制点落在 1/3、2/3 处），统一后续处理。 */
        fun lineTo(x: Float, y: Float) {
            current.cubics.add(
                Cubic(
                    cx, cy,
                    cx + (x - cx) / 3f, cy + (y - cy) / 3f,
                    cx + (x - cx) * 2f / 3f, cy + (y - cy) * 2f / 3f,
                    x, y,
                ),
            )
            cx = x; cy = y
            hasCurrent = true
        }

        fun quadTo(qx: Float, qy: Float, x: Float, y: Float) {
            // 二次升阶为三次：C1 = Q0 + 2/3(Q1−Q0)，C2 = Q2 + 2/3(Q1−Q2)
            current.cubics.add(
                Cubic(
                    cx, cy,
                    cx + 2f / 3f * (qx - cx), cy + 2f / 3f * (qy - cy),
                    x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
                    x, y,
                ),
            )
            lastCtrlX = qx; lastCtrlY = qy
            cx = x; cy = y
            hasCurrent = true
        }

        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            current.cubics.add(Cubic(cx, cy, x1, y1, x2, y2, x, y))
            lastCtrlX = x2; lastCtrlY = y2
            cx = x; cy = y
            hasCurrent = true
        }

        val lexer = Lexer(d)
        var currentCommand = ' '

        while (true) {
            /**
             * 取命令：只有当前字符是字母才取；否则视为**隐式重复上一条命令**。
             * 早期版本在条件里取命令、遇到数字就 break，导致整条路径解析为空。
             */
            val letter = lexer.tryCommand()
            val cmd = letter
                ?: currentCommand.takeIf { it != ' ' && lexer.hasNextNumber() }
                ?: break
            currentCommand = cmd

            when (cmd) {
                'M', 'm' -> {
                    finish(false)
                    val x = lexer.nextNumber()
                    val y = lexer.nextNumber()
                    cx = if (cmd == 'm') cx + x else x
                    cy = if (cmd == 'm') cy + y else y
                    startX = cx; startY = cy
                    hasCurrent = true
                    // 后续裸坐标对是隐式 lineto
                    while (lexer.hasNextNumber()) {
                        val nx = lexer.nextNumber()
                        val ny = lexer.nextNumber()
                        val tx = if (cmd == 'm') cx + nx else nx
                        val ty = if (cmd == 'm') cy + ny else ny
                        lineTo(tx, ty)
                    }
                }

                'L', 'l' -> while (lexer.hasNextNumber()) {
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    lineTo(if (cmd == 'l') cx + x else x, if (cmd == 'l') cy + y else y)
                }

                'H', 'h' -> while (lexer.hasNextNumber()) {
                    val x = lexer.nextNumber()
                    lineTo(if (cmd == 'h') cx + x else x, cy)
                }

                'V', 'v' -> while (lexer.hasNextNumber()) {
                    val y = lexer.nextNumber()
                    lineTo(cx, if (cmd == 'v') cy + y else y)
                }

                'C', 'c' -> while (lexer.hasNextNumber()) {
                    val x1 = lexer.nextNumber(); val y1 = lexer.nextNumber()
                    val x2 = lexer.nextNumber(); val y2 = lexer.nextNumber()
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    val rel = cmd == 'c'
                    cubicTo(
                        if (rel) cx + x1 else x1, if (rel) cy + y1 else y1,
                        if (rel) cx + x2 else x2, if (rel) cy + y2 else y2,
                        if (rel) cx + x else x, if (rel) cy + y else y,
                    )
                }

                'S', 's' -> while (lexer.hasNextNumber()) {
                    val x2 = lexer.nextNumber(); val y2 = lexer.nextNumber()
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    val rel = cmd == 's'
                    val rx = if (lastCmd in "CcSs") 2 * cx - lastCtrlX else cx
                    val ry = if (lastCmd in "CcSs") 2 * cy - lastCtrlY else cy
                    cubicTo(
                        rx, ry,
                        if (rel) cx + x2 else x2, if (rel) cy + y2 else y2,
                        if (rel) cx + x else x, if (rel) cy + y else y,
                    )
                }

                'Q', 'q' -> while (lexer.hasNextNumber()) {
                    val x1 = lexer.nextNumber(); val y1 = lexer.nextNumber()
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    val rel = cmd == 'q'
                    quadTo(
                        if (rel) cx + x1 else x1, if (rel) cy + y1 else y1,
                        if (rel) cx + x else x, if (rel) cy + y else y,
                    )
                }

                'T', 't' -> while (lexer.hasNextNumber()) {
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    val rel = cmd == 't'
                    val rx = if (lastCmd in "QqTt") 2 * cx - lastCtrlX else cx
                    val ry = if (lastCmd in "QqTt") 2 * cy - lastCtrlY else cy
                    quadTo(rx, ry, if (rel) cx + x else x, if (rel) cy + y else y)
                }

                'A', 'a' -> while (lexer.hasNextNumber()) {
                    val rx = lexer.nextNumber(); val ry = lexer.nextNumber()
                    val rot = lexer.nextNumber()
                    val largeArc = lexer.nextNumber() != 0f
                    val sweep = lexer.nextNumber() != 0f
                    val x = lexer.nextNumber(); val y = lexer.nextNumber()
                    val rel = cmd == 'a'
                    val ex = if (rel) cx + x else x
                    val ey = if (rel) cy + y else y
                    arcTo(::cubicTo, cx, cy, rx, ry, rot, largeArc, sweep, ex, ey)
                    cx = ex; cy = ey
                    hasCurrent = true
                }

                'Z', 'z' -> {
                    if (hasCurrent) {
                        // 闭合：补一段回到起点的线（若尚未重合），再标记闭合。
                        // 必须补这一段，否则闭合图形会缺一条边，形状明显不完整。
                        if (hypot((startX - cx).toDouble(), (startY - cy).toDouble()) > 0.01) {
                            lineTo(startX, startY)
                        }
                        finish(true)
                        cx = startX; cy = startY
                    }
                }

                else -> return contours
            }

            lastCmd = cmd
        }

        finish(false)
        return contours
    }

    // ============================================================
    // 第二步：弧长重采样
    // ============================================================

    /**
     * 把一条子路径按弧长等距采样成固定点数。
     *
     * 两条关键处理，与 JS 版一致：
     *   1. **角点锚定**：切线不连续处（尖角）必须是精确的采样点，
     *      否则一个对勾的尖角会变成圆的。判定阈值是切线夹角超过约 57°。
     *   2. 其余点按弧长在角点之间分配，保证总点数严格等于 [SAMPLES]。
     */
    private fun sampleContour(contour: Contour): MorphEngine.Sampled? {
        val segments = contour.cubics
        if (segments.isEmpty()) return null

        // 每段细分成若干子段，用于近似弧长与求切线
        val fine = 24
        val pts = ArrayList<Float>(fine * segments.size * 2 + 4)
        val cumulative = ArrayList<Float>(fine * segments.size + 2)

        // 起点
        var totalLength = 0f
        pts.add(segments[0].x0); pts.add(segments[0].y0)
        cumulative.add(0f)

        for (seg in segments) {
            for (i in 1..fine) {
                val t = i / fine.toFloat()
                val (x, y) = evalCubic(seg, t)
                val px = pts[pts.size - 2]
                val py = pts[pts.size - 1]
                totalLength += hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
                pts.add(x); pts.add(y)
                cumulative.add(totalLength)
            }
        }
        if (totalLength <= 0.01f) return null

        // —— 找角点（切线夹角超过阈值处） ——
        val cornerIndices = ArrayList<Int>()
        val cornerThreshold = Math.toRadians(57.0)
        val n = pts.size / 2
        for (i in 1 until n - 1) {
            val ax = pts[(i + 1) * 2] - pts[i * 2]
            val ay = pts[(i + 1) * 2 + 1] - pts[i * 2 + 1]
            val bx = pts[i * 2] - pts[(i - 1) * 2]
            val by = pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]
            val na = hypot(ax.toDouble(), ay.toDouble())
            val nb = hypot(bx.toDouble(), by.toDouble())
            if (na < 1e-6 || nb < 1e-6) continue
            val dot = ((ax * bx + ay * by) / (na * nb)).coerceIn(-1.0, 1.0)
            val turn = acos(dot)
            if (turn > cornerThreshold) cornerIndices.add(i)
        }
        if (!contour.closed) {
            cornerIndices.add(0, 0)
            cornerIndices.add(n - 1)
        } else {
            // 闭合路径只锚定真实角点，不锚定任意的起点
            cornerIndices.sort()
        }
        if (cornerIndices.isEmpty()) cornerIndices.add(0)

        val out = FloatArray(SAMPLES * 2)

        if (cornerIndices.size < 2) {
            // 没有内部角点：整条路径按弧长均匀采样
            for (i in 0 until SAMPLES) {
                val target = totalLength * i / (SAMPLES - 1).toFloat()
                val (x, y) = pointAtLength(pts, cumulative, target)
                out[i * 2] = x; out[i * 2 + 1] = y
            }
            return MorphEngine.Sampled(out, contour.closed)
        }

        // 有角点：先把角点逐一放到位，剩下的名额按弧长分给各段
        val cornerLengths = cornerIndices.map { cumulative[it] }
        val result = arrayOfNulls<Pair<Float, Float>>(SAMPLES)
        // 角点数量可能超过采样点数（极端情况），此时退化为均匀采样
        if (cornerIndices.size >= SAMPLES) {
            for (i in 0 until SAMPLES) {
                val target = totalLength * i / (SAMPLES - 1).toFloat()
                val (x, y) = pointAtLength(pts, cumulative, target)
                out[i * 2] = x; out[i * 2 + 1] = y
            }
            return MorphEngine.Sampled(out, contour.closed)
        }

        for ((k, idx) in cornerIndices.withIndex()) {
            val slot = (k * (SAMPLES - 1)) / (cornerIndices.size - 1)
            result[slot] = pts[idx * 2] to pts[idx * 2 + 1]
        }

        // 在相邻角点之间按弧长补齐
        for (k in 0 until cornerIndices.size - 1) {
            val slotStart = (k * (SAMPLES - 1)) / (cornerIndices.size - 1)
            val slotEnd = ((k + 1) * (SAMPLES - 1)) / (cornerIndices.size - 1)
            val lenStart = cornerLengths[k]
            val lenEnd = cornerLengths[k + 1]
            val span = slotEnd - slotStart
            if (span <= 1) continue
            for (s in 1 until span) {
                val frac = s / span.toFloat()
                val target = lenStart + (lenEnd - lenStart) * frac
                val (x, y) = pointAtLength(pts, cumulative, target)
                result[slotStart + s] = x to y
            }
        }

        for (i in 0 until SAMPLES) {
            val p = result[i] ?: run {
                val target = totalLength * i / (SAMPLES - 1).toFloat()
                pointAtLength(pts, cumulative, target)
            }
            out[i * 2] = p.first
            out[i * 2 + 1] = p.second
        }

        return MorphEngine.Sampled(out, contour.closed)
    }

    /** 沿累计弧长查表求点（线性插值）。 */
    private fun pointAtLength(pts: List<Float>, cumulative: List<Float>, target: Float): Pair<Float, Float> {
        if (cumulative.size < 2) return pts[0] to pts[1]
        val total = cumulative.last()
        val t = target.coerceIn(0f, total)

        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < t) lo = mid + 1 else hi = mid
        }
        val i = lo.coerceAtLeast(1)
        val segLen = cumulative[i] - cumulative[i - 1]
        val frac = if (segLen <= 1e-6f) 0f else (t - cumulative[i - 1]) / segLen
        val x = pts[(i - 1) * 2] + (pts[i * 2] - pts[(i - 1) * 2]) * frac
        val y = pts[(i - 1) * 2 + 1] + (pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]) * frac
        return x to y
    }

    /** 三次贝塞尔在 t 处的点。 */
    private fun evalCubic(c: Cubic, t: Float): Pair<Float, Float> {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val d = 3f * u * t * t
        val e = t * t * t
        return (a * c.x0 + b * c.x1 + d * c.x2 + e * c.x3) to
            (a * c.y0 + b * c.y1 + d * c.y2 + e * c.y3)
    }

    // ============================================================
    // 椭圆弧 → 三次贝塞尔
    // ============================================================

    /**
     * SVG 椭圆弧转三次贝塞尔。
     *
     * 按 SVG 规范附录 F.6 做「端点参数 → 圆心参数」转换，
     * 再按不超过 90° 切片，每片用 α = (4/3)·tan(Δθ/4) 的控制点近似。
     */
    @Suppress("LongParameterList")
    private fun arcTo(
        cubicTo: (Float, Float, Float, Float, Float, Float) -> Unit,
        x1: Float,
        y1: Float,
        rxIn: Float,
        ryIn: Float,
        rotationDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x2: Float,
        y2: Float,
    ) {
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx < 1e-6f || ry < 1e-6f) {
            cubicTo(x1, y1, x2, y2, x2, y2)
            return
        }
        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x1 - x2) / 2.0
        val dy2 = (y1 - y2) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val lambda = (x1p * x1p) / (rx * rx.toDouble()) + (y1p * y1p) / (ry * ry.toDouble())
        if (lambda > 1.0) {
            val scale = sqrt(lambda)
            rx = (rx * scale).toFloat()
            ry = (ry * scale).toFloat()
        }

        val rx2 = rx.toDouble() * rx.toDouble()
        val ry2 = ry.toDouble() * ry.toDouble()
        val numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
        val denominator = rx2 * y1p * y1p + ry2 * x1p * x1p
        var coef = if (denominator <= 1e-12) 0.0 else sqrt((numerator / denominator).coerceAtLeast(0.0))
        if (largeArc == sweep) coef = -coef

        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-(ry * x1p) / rx)
        val ccx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
        val ccy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = if (len <= 1e-12) 0.0 else acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val theta1 = angle(1.0, 0.0, ux, uy)
        var deltaTheta = angle(ux, uy, vx, vy)
        if (!sweep && deltaTheta > 0) deltaTheta -= 2 * Math.PI
        if (sweep && deltaTheta < 0) deltaTheta += 2 * Math.PI

        val segments = ceil(abs(deltaTheta) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val delta = deltaTheta / segments
        val alpha = 4.0 / 3.0 * tan(delta / 4.0)

        var t = theta1
        var sx = x1.toDouble()
        var sy = y1.toDouble()
        for (i in 0 until segments) {
            val t2 = t + delta
            val cosT1 = cos(t); val sinT1 = sin(t)
            val cosT2 = cos(t2); val sinT2 = sin(t2)

            // 标准式：圆心 + 旋转后的椭圆参数方程
            val ex = ccx + rx * cosPhi * cosT2 - ry * sinPhi * sinT2
            val ey = ccy + rx * sinPhi * cosT2 + ry * cosPhi * sinT2

            val d1x = -rx * cosPhi * sinT1 - ry * sinPhi * cosT1
            val d1y = -rx * sinPhi * sinT1 + ry * cosPhi * cosT1
            val d2x = -rx * cosPhi * sinT2 - ry * sinPhi * cosT2
            val d2y = -rx * sinPhi * sinT2 + ry * cosPhi * cosT2

            cubicTo(
                (sx + alpha * d1x).toFloat(),
                (sy + alpha * d1y).toFloat(),
                (ex - alpha * d2x).toFloat(),
                (ey - alpha * d2y).toFloat(),
                ex.toFloat(),
                ey.toFloat(),
            )
            sx = ex; sy = ey
            t = t2
        }
    }

    // ============================================================
    // 词法
    // ============================================================

    /**
     * 极简词法分析器。
     *
     * 关键点是**隐式重复命令**与**紧凑数字**：`m6 6 12 12` 里 `6 6` 是 moveto、
     * `12 12` 是隐式 lineto；`1.5.5` 是两个数；`-1-2` 也是两个数。
     * 这些在真实图标库里很常见，处理不当会解析出错误形状。
     */
    private class Lexer(private val src: String) {
        private var pos = 0

        /** 若当前位置是命令字母则消费并返回，否则返回 null（可能是数字或已结束）。 */
        fun tryCommand(): Char? {
            skipSeparators()
            if (pos >= src.length) return null
            val c = src[pos]
            if (c.isLetter()) {
                pos++
                return c
            }
            return null
        }

        /** 下一个待读的是否是数字。 */
        fun hasNextNumber(): Boolean {
            var p = pos
            while (p < src.length) {
                val c = src[p]
                if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') p++ else break
            }
            if (p >= src.length) return false
            val c = src[p]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }

        fun nextNumber(): Float {
            skipSeparators()
            val start = pos
            if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (start == pos) return 0f
            return src.substring(start, pos).toFloatOrNull() ?: 0f
        }

        private fun skipSeparators() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }
    }
}
