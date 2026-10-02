package com.moyu.reader.ui.morph

import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos

/**
 * 会形变的描边图标（Android / Compose）。
 *
 * 与 Web 端的 `MorphGlyph` 是同一套算法、同一份路径数据、同一组弹簧参数，
 * 因此两端的中间帧与手感是对齐的。
 *
 * ## 为什么用 Canvas 画而不是用矢量图标资源
 *
 * 形变每帧都要写出**全新的路径**，静态的 `ImageVector` 无法表达。
 * Canvas + `Path` 是唯一能做到「每帧重建几何、不触发重新布局」的方式，
 * 这也是 morphicons 在 Web 上用 `<path d=...>` 直接改属性的等价做法。
 *
 * ## 首次绘制不播动画
 *
 * 打开页面时图标应当直接呈现目标形状，而不是从某个起点「飞」过来。
 * 因此第一次组合时把弹簧直接置为终点。
 */
@Composable
fun MorphIcon(
    /** 目标图标（由若干条 d 字符串组成） */
    paths: List<String>,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Dp = 1.8.dp,
    preset: MorphSpringPreset = MorphSpringPreset.SNAPPY,
) {
    val targetD = remember(paths) { MorphIcons.join(paths) }
    val target = remember(targetD) { MorphEngine.resample(targetD) }

    // 当前形状的点列：形变过程中不断被覆写
    var current by remember { mutableStateOf<List<MorphEngine.Sampled>?>(null) }
    // 触发重绘的令牌（点列是可变数组，改内容不会触发重组，因此用一个计数驱动）
    var frame by remember { mutableFloatStateOf(0f) }

    val spring = remember { MorphSpring(preset.stiffness, preset.damping) }
    // 上一次成功建立方案的目标，用于判断「目标是否真的变了」
    var lastTargetD by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(targetD) {
        if (target.isEmpty()) return@LaunchedEffect

        val from = current
        if (from == null) {
            // 首次绘制：直接呈现目标形状，不播动画
            current = target
            spring.snapToEnd()
            lastTargetD = targetD
            frame += 1f
            return@LaunchedEffect
        }

        val plan = MorphEngine.buildPlan(from, target) ?: run {
            current = target
            frame += 1f
            return@LaunchedEffect
        }
        val buffers = MorphEngine.allocOutputs(plan)

        // 中途打断：从当前中间形状重新出发，弹簧归零但保留速度（动量连续）
        spring.restart()

        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = (now - lastNanos) / 1_000_000_000f
                    spring.advance(dt)
                }
                lastNanos = now
            }
            MorphEngine.interpolate(plan, spring.position, buffers)
            // 把缓冲复制成新的 Sampled，供下一轮「打断」当起点用
            current = buffers.mapIndexed { i, buf ->
                MorphEngine.Sampled(buf.copyOf(), plan.closedFlags.getOrElse(i) { false })
            }
            frame += 1f
            if (spring.isSettled) break
        }
        lastTargetD = targetD
    }

    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semanticsLabel(contentDescription)
                } else {
                    Modifier
                },
            ),
    ) {
        val shape = current ?: return@Canvas
        val scale = this.size.minDimension / 24f
        val stroke = strokeWidth.toPx()

        // 24×24 网格居中映射到画布
        val offsetX = (this.size.width - 24f * scale) / 2f
        val offsetY = (this.size.height - 24f * scale) / 2f

        translate(offsetX, offsetY) {
            val path = ComposePath()
            for (s in shape) {
                val n = s.points.size / 2
                if (n == 0) continue
                path.moveTo(s.points[0] * scale, s.points[1] * scale)
                for (i in 1 until n) {
                    path.lineTo(s.points[i * 2] * scale, s.points[i * 2 + 1] * scale)
                }
                if (s.closed) path.close()
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/** 语义标签（供无障碍读取）。 */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }

/** 未使用，保留以便将来做「按进度手动驱动」（如跟随手势）。 */
@Suppress("unused")
internal fun pathFromSampled(shape: List<MorphEngine.Sampled>, scale: Float): Path {
    val path = Path()
    for (s in shape) {
        val n = s.points.size / 2
        if (n == 0) continue
        path.moveTo(s.points[0] * scale, s.points[1] * scale)
        for (i in 1 until n) {
            path.lineTo(s.points[i * 2] * scale, s.points[i * 2 + 1] * scale)
        }
        if (s.closed) path.close()
    }
    return path
}

/** 供调用方计算期望尺寸。 */
internal val IntrinsicIconSize = Size(24f, 24f)
