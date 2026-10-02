package com.moyu.reader.ui.morph

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 图标形变引擎（Kotlin 版）。
 *
 * 这是 morphicons（TypeScript，MIT）核心算法的移植，规则与它逐条对齐：
 * 归一化为三次贝塞尔 → 按弧长重采样成等距点列 → 2D Procrustes 求最优相似变换
 * → 在「相似变换 + 残余形变」两个空间里分别插值（极坐标插值）。
 *
 * ## 为什么移植而不是找现成的
 *
 * 用的是同一个算法、同一份图标路径数据，因此 Android 端与浏览器验证器里的
 * 中间帧是同一套数学算出来的。没有 Kotlin/Compose 绑定可用（只有 Flutter 移植），
 * 而「在验证器里看好了、到手机上不一样」是必须避免的。
 *
 * ## 与 JS 版的两处实现差异（结果等价）
 *
 * 1. **重采样用 [PathMeasure]** 而不是自己积分贝塞尔弧长。
 *    JS 版没有平台能力，只能用 8 点高斯-勒让德积分近似贝塞尔弧长；
 *    Android 的 PathMeasure 本身就是精确的弧长参数化实现，
 *    用它比手写积分更准，也少一大段代码。
 * 2. **不自行解析 SVG 的 `d`**，直接交给 [android.graphics.Path] 的解析能力。
 *    七种描边图元与弧线命令都由系统处理，避免自己写一个容易出错的路径解析器。
 *
 * 这两处差异都只影响「怎么拿到点列」，而点列之后的配对、对齐、插值完全一致。
 */
object MorphEngine {

    /**
     * 每个子路径的重采样点数。
     *
     * 与 JS 版一致取 64。这个数决定形状精度与每帧开销：
     * 太小会让圆角变成折线，太大则无谓地增加插值计算量。
     * 24×24 网格上的线性图标，64 点已经足够平滑。
     */
    const val SAMPLES_PER_SUBPATH = 64

    /** 子路径配对的弧长权重（参考实现 LEN_WEIGHT）。 */
    private const val LEN_WEIGHT = 0.35f

    /** 对齐打分里旋转项的权重（参考实现 LAMBDA）。 */
    private const val LAMBDA = 0.05f

    /**
     * 全局对齐的采纳门槛（参考实现 GLOBAL_EPS）。
     *
     * 全局残差超过它就说明两个图标的整体姿态本就不同，
     * 强行把所有子路径一起转会扭曲形状 —— 此时宁可各转各的。
     */
    private const val GLOBAL_EPS = 0.005f

    /** 子路径数超过这个值就不做全排列（参考实现 PERM_MAX）。 */
    private const val PERM_MAX = 8

    /** 一个重采样后的子路径：等距点列 + 是否闭合。 */
    class Sampled(val points: FloatArray, val closed: Boolean) {
        /** 形心，用于子路径配对与极坐标插值。 */
        val centroidX: Float
        val centroidY: Float

        init {
            var sx = 0f
            var sy = 0f
            val count = points.size / 2
            for (i in 0 until count) {
                sx += points[i * 2]
                sy += points[i * 2 + 1]
            }
            centroidX = if (count == 0) 0f else sx / count
            centroidY = if (count == 0) 0f else sy / count
        }

        /** 弧长（点列多边形的周长），用于子路径配对的代价函数。 */
        val length: Float by lazy {
            var sum = 0f
            val count = points.size / 2
            val segments = if (closed) count else count - 1
            for (i in 0 until segments) {
                val j = (i + 1) % count
                sum += hypot(
                    (points[j * 2] - points[i * 2]).toDouble(),
                    (points[j * 2 + 1] - points[i * 2 + 1]).toDouble(),
                ).toFloat()
            }
            sum
        }
    }

    /**
     * 把一段 SVG 路径重采样成等距点列。
     *
     * 输入是**单个 `d` 字符串**（可以含多条 `M` 子路径）——
     * 这与 JS 版一致：不是「d 字符串的数组」，传数组会被当成节点列表。
     *
     * 实现交给 [SvgPathParser]：它自己解析命令并**逐条子路径**采样。
     * 这里刻意不用 `PathMeasure.nextContour()` —— 它在不同 Android 版本上
     * 对同一 Path 里的多条子路径处理不一致，会让形变在某些机型上只画出第一笔。
     */
    fun resample(d: String): List<Sampled> = SvgPathParser.parse(d)

    /** 一对子路径之间的「配对方案」。 */
    class Pairing(val src: Sampled, val dst: Sampled)

    /**
     * 建立形变方案。
     *
     * 三件事，与 JS 版一一对应：
     *   1. **子路径配对**：代价 = 形心距离 + 0.35 × 弧长差；
     *      数量不等时从多的一侧向少的一侧做**满射**配对（复制而非消失），
     *      这样不会有笔画凭空出现或消失。
     *   2. **朝向与起点**：闭合子路径有「从哪个点算起」的自由度，
     *      两个方向 × N 个起点都试一遍，取对齐后残差最小的那个。
     *   3. **对齐**：对每个配对求 2D Procrustes 最优相似变换（旋转 θ、缩放 σ、平移）。
     */
    class Plan(
        val pairs: List<Pairing>,
        val theta: FloatArray,
        val sigma: FloatArray,
        val srcCentroid: FloatArray,
        val dstCentroid: FloatArray,
        val closedFlags: BooleanArray,
        /** 目标点云在「源坐标系」里的形式（已扣除旋转与缩放），插值时用它做混合 */
        val targetLocal: List<FloatArray>,
    ) {
        val subpathCount: Int get() = pairs.size
    }

    fun buildPlan(src: List<Sampled>, dst: List<Sampled>): Plan? {
        if (src.isEmpty() || dst.isEmpty()) return null

        // —— 1. 子路径配对（满射：从多的一侧复制） ——
        val pairs = ArrayList<Pairing>()
        if (src.size == dst.size) {
            /**
             * 数量相同：求**最小代价置换**，与 morphicons 的 JS 版一致。
             *
             * 这里最初用贪心，结果形状明显不对：放大镜→关闭时，
             * 贪心把「圆 → 斜线1、手柄 → 斜线2」配成对，中间帧扭成一个「才」字；
             * 而正确的是「圆 → 斜线1、手柄 → 斜线2」这种全局最优配对。
             * 图标子路径数很少（≤8），穷举置换的开销可以忽略。
             */
            val assignment = minCostAssignment(src, dst)
            for (i in src.indices) {
                val j = assignment[i]
                if (j >= 0) pairs.add(Pairing(src[i], dst[j]))
            }
        } else {
            /**
             * 数量不等：从多的一侧向少的一侧做**满射**配对 ——
             * 多出来的子路径**复制**最近的少侧子路径，而不是凭空出现或消失。
             * 复制在静止时完全看不出来（同样的描边重叠在一起），
             * 中途分开的过程读起来像「分裂」，比从无到有自然得多。
             */
            val srcIsMany = src.size > dst.size
            val many = if (srcIsMany) src else dst
            val few = if (srcIsMany) dst else src

            // 每个「多」侧下标 → 配到的「少」侧下标
            val assignManyToFew = minCostAssignmentUnequal(many, few)
            for (i in many.indices) {
                val j = assignManyToFew[i]
                val a = many[i]
                val b = few[j]
                pairs.add(if (srcIsMany) Pairing(a, b) else Pairing(b, a))
            }
        }

        if (pairs.isEmpty()) return null

        // —— 2 + 3. 每个配对求朝向、起点与局部相似变换 ——
        val count = pairs.size
        val theta = FloatArray(count)
        val sigma = FloatArray(count)
        val srcCentroid = FloatArray(count * 2)
        val dstCentroid = FloatArray(count * 2)
        val closedFlags = BooleanArray(count)
        // 目标点云搬进「源坐标系」后的形式（参考实现里的 bT）
        val targetLocal = ArrayList<FloatArray>(count)
        // 权重：残差累加时要用
        val residuals = FloatArray(count)

        for (k in 0 until count) {
            val a = pairs[k].src
            val b = pairs[k].dst
            closedFlags[k] = a.closed && b.closed
            val al = bestAlignment(a, b, closedFlags[k])
            theta[k] = al.theta
            sigma[k] = al.sigma
            srcCentroid[k * 2] = al.srcCx
            srcCentroid[k * 2 + 1] = al.srcCy
            dstCentroid[k * 2] = al.dstCx
            dstCentroid[k * 2 + 1] = al.dstCy
            residuals[k] = al.residual
            targetLocal.add(al.bLocal.copyOf())
        }

        /**
         * 4. **全局对齐**（参考实现的 applyGlobal）。
         *
         * 这是「中间帧不纠缠」的关键一步，也是最初漏掉的一步：
         * 如果每条子路径都各转各的，那么月亮变成太阳时，
         * 9 条光芒会各自绕自己的形心旋转、互不相干地漂移，
         * 中间帧就成了一团墨渍。
         *
         * 做法是把**所有子路径的点拼成一个大点云**求一次全局相似变换
         * （质心按点数加权，因此长笔画权重更大），
         * 只有这次全局对齐足够好（残差 < GLOBAL_EPS）才采纳 ——
         * 否则说明两个图标的整体姿态本来就不同，强行一起转会扭曲形状。
         */
        if (count > 1) {
            val total = count * SAMPLES_PER_SUBPATH
            val ga = FloatArray(total * 2)
            val gb = FloatArray(total * 2)
            for (k in 0 until count) {
                pairs[k].src.points.copyInto(ga, k * SAMPLES_PER_SUBPATH * 2)
                // bO：目标的原始点云
                pairs[k].dst.points.copyInto(gb, k * SAMPLES_PER_SUBPATH * 2)
            }
            val gca = centroidOf(ga)
            val gcb = centroidOf(gb)
            val g = procrustesOf(ga, gb, gca, gcb)
            if (g.residual < GLOBAL_EPS) {
                val cosNeg = cos(-g.theta)
                val sinNeg = sin(-g.theta)
                for (k in 0 until count) {
                    val bLocal = targetLocal[k]
                    val bcx = dstCentroid[k * 2]
                    val bcy = dstCentroid[k * 2 + 1]
                    for (i in 0 until SAMPLES_PER_SUBPATH) {
                        val bx = pairs[k].dst.points[i * 2] - bcx
                        val by = pairs[k].dst.points[i * 2 + 1] - bcy
                        bLocal[i * 2] = (bx * cosNeg - by * sinNeg) / g.sigma
                        bLocal[i * 2 + 1] = (bx * sinNeg + by * cosNeg) / g.sigma
                    }
                    // 把全局旋转叠加到这一条的局部旋转上
                    theta[k] += g.theta
                }
            }
        }

        return Plan(
            pairs = pairs,
            theta = theta,
            sigma = sigma,
            srcCentroid = srcCentroid,
            dstCentroid = dstCentroid,
            closedFlags = closedFlags,
            targetLocal = targetLocal,
        )
    }

    /** 点云质心（点数加权）。 */
    private fun centroidOf(pts: FloatArray): FloatArray {
        var sx = 0f
        var sy = 0f
        val n = pts.size / 2
        for (i in 0 until n) {
            sx += pts[i * 2]
            sy += pts[i * 2 + 1]
        }
        return floatArrayOf(if (n == 0) 0f else sx / n, if (n == 0) 0f else sy / n)
    }

    /** 点云的「能量」Σ|p−c|²，用于残差归一化。 */
    private fun energyOf(pts: FloatArray, cx: Float, cy: Float): Float {
        var e = 0f
        val n = pts.size / 2
        for (i in 0 until n) {
            val dx = pts[i * 2] - cx
            val dy = pts[i * 2 + 1] - cy
            e += dx * dx + dy * dy
        }
        return e
    }

    /**
     * 2D Procrustes：求使 Σ|σ·R(θ)·(a−cA) − (b−cB)|² 最小的 (θ, σ)。
     *
     * 有闭式解，不需要 SVD：
     *   θ* = atan2(Sxy − Syx, Sxx + Syy)，σ* = [cosθ(Sxx+Syy) + sinθ(Sxy−Syx)] / Σ|a−cA|²
     * 残差用闭式 σ²·na − 2σ·num + nb，比先算每点误差再求和更省也更稳。
     * 与参考实现逐项一致。
     */
    private fun procrustesOf(a: FloatArray, b: FloatArray, ca: FloatArray, cb: FloatArray): Alignment {
        val n = a.size / 2
        var sxx = 0f
        var sxy = 0f
        var syx = 0f
        var syy = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            val ax = a[i * 2] - ca[0]
            val ay = a[i * 2 + 1] - ca[1]
            val bx = b[i * 2] - cb[0]
            val by = b[i * 2 + 1] - cb[1]
            sxx += ax * bx
            syy += ay * by
            sxy += ax * by
            syx += ay * bx
            na += ax * ax + ay * ay
            nb += bx * bx + by * by
        }
        val theta = atan2(sxy - syx, sxx + syy)
        val num = cos(theta) * (sxx + syy) + sin(theta) * (sxy - syx)
        var sigma = if (na > 1e-12f) num / na else 1f
        if (!(sigma > 1e-6f)) sigma = 1e-6f
        val res2 = (sigma * sigma * na - 2f * sigma * num + nb).coerceAtLeast(0f)
        val residual = if (nb > 1e-12f) sqrt(res2 / nb) else 0f
        return Alignment(theta, sigma, ca[0], ca[1], cb[0], cb[1], residual)
    }

    private fun pairCost(a: Sampled, b: Sampled): Float {
        val d = hypot((a.centroidX - b.centroidX).toDouble(), (a.centroidY - b.centroidY).toDouble())
        return (d + 0.35f * abs(a.length - b.length)).toFloat()
    }

    /**
     * 最小代价置换（等数量配对）。
     *
     * 子路径数很少（图标一般 1~9 条），因此**穷举所有置换**取总代价最小的一组。
     * 超过 8 条时退化为贪心 —— 那已经是极端复杂的图标，
     * 穷举的阶乘开销（9! = 36 万）不值得。
     */
    private fun minCostAssignment(a: List<Sampled>, b: List<Sampled>): IntArray {
        val n = a.size
        if (n == 0) return IntArray(0)
        if (n > 8) return greedyAssignment(a, b)

        val cost = Array(n) { i -> FloatArray(n) { j -> pairCost(a[i], b[j]) } }
        val best = IntArray(n) { it }
        var bestCost = Float.MAX_VALUE

        // 堆算法生成全排列，避免递归分配
        val perm = IntArray(n) { it }
        val c = IntArray(n)
        var i = 0
        while (i < n) {
            var total = 0f
            for (k in 0 until n) total += cost[k][perm[k]]
            if (total < bestCost) {
                bestCost = total
                perm.copyInto(best)
            }
            i = 0
            while (i < n) {
                if (c[i] < i) {
                    val swap = if (i % 2 == 0) 0 else c[i]
                    val t = perm[swap]; perm[swap] = perm[i]; perm[i] = t
                    c[i]++
                    break
                } else {
                    c[i] = 0
                    i++
                }
            }
            // 第一轮已经评估过初始排列
            if (i == 0 && c.all { it == 0 }) break
        }
        return best
    }

    /**
     * 数量不等时的**满射**配对：每个「多」侧子路径都配一个「少」侧子路径。
     *
     * 做法与 JS 版一致：先给每个「少」侧找一个最合适的「多」侧（保证每个都出现），
     * 剩下的「多」侧各自复制最近的「少」侧。
     */
    private fun minCostAssignmentUnequal(many: List<Sampled>, few: List<Sampled>): IntArray {
        val result = IntArray(many.size) { -1 }
        if (few.isEmpty()) return result

        // 第一步：每个「少」侧挑一个还没被占用的「多」侧
        for (j in few.indices) {
            var bestI = -1
            var bestCost = Float.MAX_VALUE
            for (i in many.indices) {
                if (result[i] >= 0) continue
                val c = pairCost(many[i], few[j])
                if (c < bestCost) {
                    bestCost = c
                    bestI = i
                }
            }
            if (bestI >= 0) result[bestI] = j
        }

        // 第二步：剩下的「多」侧复制最近的「少」侧
        for (i in many.indices) {
            if (result[i] >= 0) continue
            var bestJ = 0
            var bestCost = Float.MAX_VALUE
            for (j in few.indices) {
                val c = pairCost(many[i], few[j])
                if (c < bestCost) {
                    bestCost = c
                    bestJ = j
                }
            }
            result[i] = bestJ
        }
        return result
    }

    /** 贪心配对（仅在子路径数超过 8 时作为退路）。 */
    private fun greedyAssignment(a: List<Sampled>, b: List<Sampled>): IntArray {
        val used = BooleanArray(b.size)
        val result = IntArray(a.size) { -1 }
        for (i in a.indices) {
            var bestJ = -1
            var bestCost = Float.MAX_VALUE
            for (j in b.indices) {
                if (used[j]) continue
                val c = pairCost(a[i], b[j])
                if (c < bestCost) {
                    bestCost = c
                    bestJ = j
                }
            }
            if (bestJ >= 0) {
                used[bestJ] = true
                result[i] = bestJ
            }
        }
        return result
    }

    private class Alignment(
        val theta: Float,
        val sigma: Float,
        val srcCx: Float,
        val srcCy: Float,
        val dstCx: Float,
        val dstCy: Float,
        /** 对齐后的归一化 RMS 残差：≈0 表示两个形状只差一个旋转/缩放。 */
        val residual: Float = 0f,
        /** 目标点云搬进源坐标系后的形式（参考实现的 bT）。只在 bestAlignment 里回填。 */
        var bLocal: FloatArray = FloatArray(0),
    )
    /**
     * 求把 a 对齐到 b 的最优相似变换。
     *
     * 自由度与参考实现一致：**两种遍历方向**，以及闭合回路的**起点自由度**。
     *
     * 两个容易写错的地方：
     *
     * 1. **反向遍历要「物理反转」点云**，而不是在算 S 矩阵时改用 b[n−1−i]。
     *    混用两套下标会让 bT 的构造与 S 矩阵的配对不一致，形状直接错位。
     *    这里统一成：先把要变的那一侧换成 `reversePts` 的结果，再照常按下标配对。
     *
     * 2. **起点自由度只施加在一侧**（闭合的那一侧；两侧都闭合时选 b）。
     *    两侧同时转是冗余的，而且会让搜索空间大 64 倍却没有额外收益。
     *
     * 打分：`res + λ·|θ|/π`，λ = 0.05 —— 在残差相近时偏爱旋转更小的方案，
     * 避免形状明明对齐得很好却绕一大圈。
     */
    private fun bestAlignment(a: Sampled, b: Sampled, bothClosed: Boolean): Alignment {
        val aPts = a.points
        val bPts = b.points
        val ca = floatArrayOf(a.centroidX, a.centroidY)
        val cb = floatArrayOf(b.centroidX, b.centroidY)

        val varyA = a.closed && !b.closed
        val base = if (varyA) aPts else bPts
        val offsets = if (a.closed || b.closed) SAMPLES_PER_SUBPATH else 1

        var bestScore = Float.MAX_VALUE
        var chosen: Alignment? = null
        // 选中的那一侧点云（可能是反转/循环移位过的 b），构造 bLocal 时要用它，
        // 否则会与 S 矩阵的配对错位。
        var selectedB = bPts

        for (dir in 0..1) {
            val walk = if (dir == 1) reversePts(base) else base
            for (off in 0 until offsets) {
                val cand = if (off != 0) rotatePts(walk, off) else walk
                val s = if (varyA) {
                    procrustesOf(cand, bPts, ca, cb)
                } else {
                    procrustesOf(aPts, cand, ca, cb)
                }
                val score = s.residual + LAMBDA * (abs(s.theta) / Math.PI.toFloat())
                if (score < bestScore) {
                    bestScore = score
                    chosen = s
                    selectedB = if (varyA) bPts else cand
                }
            }
        }

        val al = chosen!!
        // 把目标点云搬进「源坐标系」：平移到形心、反向旋转、再除以缩放。
        val n = SAMPLES_PER_SUBPATH
        val bLocal = FloatArray(n * 2)
        val cosNeg = cos(-al.theta)
        val sinNeg = sin(-al.theta)
        val bcx = al.dstCx
        val bcy = al.dstCy
        for (i in 0 until n) {
            val bx = selectedB[i * 2] - bcx
            val by = selectedB[i * 2 + 1] - bcy
            bLocal[i * 2] = (bx * cosNeg - by * sinNeg) / al.sigma
            bLocal[i * 2 + 1] = (bx * sinNeg + by * cosNeg) / al.sigma
        }
        return Alignment(
            al.theta, al.sigma, al.srcCx, al.srcCy, al.dstCx, al.dstCy, al.residual, bLocal,
        )
    }
    /** 反转点云的遍历方向。 */
    private fun reversePts(p: FloatArray): FloatArray {
        val n = p.size / 2
        val out = FloatArray(p.size)
        for (i in 0 until n) {
            out[i * 2] = p[(n - 1 - i) * 2]
            out[i * 2 + 1] = p[(n - 1 - i) * 2 + 1]
        }
        return out
    }

    /** 循环移位：闭合回路没有「起点」，这是它的自由度。 */
    private fun rotatePts(p: FloatArray, off: Int): FloatArray {
        val n = p.size / 2
        val out = FloatArray(p.size)
        for (i in 0 until n) {
            val j = (i + off) % n
            out[i * 2] = p[j * 2]
            out[i * 2 + 1] = p[j * 2 + 1]
        }
        return out
    }

    /**
     * 极坐标插值：在 t 处求形状。
     *
     * 直接把坐标线性插值会把旋转「塌掉」—— 点走弦线、形状在途中缩小并剪切。
     * 正确做法是把运动拆成「相似变换」与「残余形变」两部分，
     * 各自在自己的空间里插值：
     *
     *     P(t) = c(t) + σ^t · R(t·θ) · [ (1−t)·a + t·b̃ ]
     *
     * 其中 b̃ 是 b 被搬回 a 的坐标系后的点。这样：
     *   - 若 b 就是 a 旋转而来（残差≈0），运动是纯旋转；
     *   - 若没有旋转，就是干净的坐标形变；
     *   - 弹簧过冲（t > 1）时公式自然外推，旋转与缩放会轻微过冲再回来。
     */
    fun interpolate(plan: Plan, t: Float, out: List<FloatArray>) {
        val n = SAMPLES_PER_SUBPATH
        for (k in 0 until plan.subpathCount) {
            val a = plan.pairs[k].src.points
            val bLocal = plan.targetLocal[k]
            val theta = plan.theta[k]
            val sigma = plan.sigma[k]
            val acx = plan.srcCentroid[k * 2]
            val acy = plan.srcCentroid[k * 2 + 1]
            val bcx = plan.dstCentroid[k * 2]
            val bcy = plan.dstCentroid[k * 2 + 1]

            // 平移线性插值
            val cx = acx + (bcx - acx) * t
            val cy = acy + (bcy - acy) * t
            // 旋转线性、缩放对数线性（ℝ⁺ 上的测地线）
            val angle = t * theta
            val scale = sigma.toDouble().pow(t.toDouble()).toFloat()
            val ca = cos(angle)
            val sa = sin(angle)

            val target = out[k]
            for (i in 0 until n) {
                val px = a[i * 2] - acx
                val py = a[i * 2 + 1] - acy
                // 在 a 的坐标系里与 bT 线性混合
                val mx = px + (bLocal[i * 2] - px) * t
                val my = py + (bLocal[i * 2 + 1] - py) * t
                // 再施加相似变换
                target[i * 2] = cx + scale * (ca * mx - sa * my)
                target[i * 2 + 1] = cy + scale * (sa * mx + ca * my)
            }
        }
    }

    /**
     * 把插值结果序列化成 `d` 字符串。
     *
     * 用折线（M + 一串 L）而不是重建贝塞尔：每帧只写一条字符串，
     * 且 64 个采样点在 24×24 网格上肉眼已经看不出是折线。
     * 保留两位小数 —— 与 JS 版一致，既省体积又足以避免抖动。
     */
    fun serialize(points: List<FloatArray>, closed: BooleanArray): String {
        val sb = StringBuilder(256)
        for (k in points.indices) {
            val p = points[k]
            val n = p.size / 2
            if (n == 0) continue
            sb.append('M').append(fmt(p[0])).append(' ').append(fmt(p[1]))
            for (i in 1 until n) {
                sb.append('L').append(fmt(p[i * 2])).append(' ').append(fmt(p[i * 2 + 1]))
            }
            if (k < closed.size && closed[k]) sb.append('Z')
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        val rounded = Math.round(v * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    /** 为一次形变预分配输出缓冲，避免每帧分配（与 JS 版的 allocOutputs 对应）。 */
    fun allocOutputs(plan: Plan): List<FloatArray> =
        List(plan.subpathCount) { FloatArray(SAMPLES_PER_SUBPATH * 2) }

    /** 采样点数的两倍，供调用方预分配。 */
    val POINTS_PER_SUBPATH: Int get() = SAMPLES_PER_SUBPATH * 2

    /** 与 JS 版一致的缩放比工具，供将来做「按弧长归一化」时使用。 */
    internal fun safeLog(v: Float): Float = if (v > 1e-6f) ln(v.toDouble()).toFloat() else 0f
}
