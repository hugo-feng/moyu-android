package com.moyu.reader.ui.morph

import kotlin.math.abs

/**
 * 阻尼谐振子（弹簧）驱动的进度积分器。
 *
 * 与 morphicons 的 JS 版完全一致：
 *
 *     ẍ = k·(1 − x) − c·ẋ
 *
 * 用**半隐式欧拉**以固定步长 h = 1/240 s 积分。半隐式欧拉在这个步长下
 * 稳定范围是 ω·h ≲ 2；取 k = 420 时 ω ≈ 20.5，ω·h ≈ 0.085，
 * 余量非常充足，所以不需要更贵的 RK4。
 *
 * 为什么要固定步长而不是用「两帧之间的真实 dt」：
 * 掉帧时真实 dt 会变大，固定步长下只要多积分几次即可保持稳定；
 * 若直接把大 dt 喂给积分器，弹簧会发散（表现为图标抖到画面外）。
 */
class MorphSpring(
    stiffness: Float,
    damping: Float,
) {
    private val k = stiffness
    private val c = damping

    /** 当前进度。0 = 起始形状，1 = 目标形状。 >1 表示过冲。 */
    var position: Float = 1f
        private set

    /** 当前速度。用于中途打断时保留动量。 */
    var velocity: Float = 0f
        private set

    /** 是否已经完全静止。 */
    val isSettled: Boolean
        get() = abs(1f - position) < 0.001f && abs(velocity) < 0.02f

    /** 从当前形状重新出发（中途打断时调用）。 */
    fun restart() {
        position = 0f
    }

    /** 直接跳到终点（无动画，用于首次绘制）。 */
    fun snapToEnd() {
        position = 1f
        velocity = 0f
    }

    /**
     * 推进一帧。
     *
     * @param deltaSeconds 距离上一帧的真实时间；内部会切成固定步长
     * @return 是否仍需继续动画
     */
    fun advance(deltaSeconds: Float): Boolean {
        if (isSettled) {
            position = 1f
            velocity = 0f
            return false
        }

        // 限制单帧最大时长：从后台切回来时 delta 可能有好几秒，
        // 不限制的话会一次性积分上千步，卡住主线程。
        val clamped = deltaSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
        var remaining = clamped
        while (remaining > 0f) {
            val step = if (remaining > FIXED_STEP) FIXED_STEP else remaining
            integrate(step)
            remaining -= step
        }
        return !isSettled
    }

    /** 半隐式欧拉的一步：先更新速度、再用新速度更新位置。 */
    private fun integrate(h: Float) {
        val accel = k * (1f - position) - c * velocity
        velocity += accel * h
        // 速度上限：防止极端打断累积出荒谬的动量（JS 版同样有 ±14 的钳制）
        velocity = velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        position += velocity * h
    }

    companion object {
        private const val FIXED_STEP = 1f / 240f
        private const val MAX_FRAME_SECONDS = 0.1f
        private const val MAX_VELOCITY = 14f
    }
}

/**
 * 弹簧预设，数值与 `src/ui/morphIcons.ts` 的 SPRING_PRESETS 一致。
 *
 * ζ = c / (2√k) 是阻尼比：
 *   smooth  ζ = 1.00  临界阻尼，无回弹
 *   snappy  ζ ≈ 0.73  快速、轻微回弹（默认）
 *   bouncy  ζ ≈ 0.40  明显回弹，偏俏皮
 */
enum class MorphSpringPreset(val stiffness: Float, val damping: Float) {
    SMOOTH(170f, 26f),
    SNAPPY(420f, 30f),
    BOUNCY(300f, 14f),
}
