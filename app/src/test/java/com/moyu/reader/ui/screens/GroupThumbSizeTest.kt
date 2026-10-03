package com.moyu.reader.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架分组卡的几何。
 *
 * ## 为什么值得写测试
 *
 * 用户的原话：「书架页为什么是横置的，你自己看看他像番茄的吗？」
 * 检查后发现根因是**尺寸写错**：缩略图只给了固定高度、宽度交给 `weight(1f)`
 * 撑满，于是每块都变成横着的方块（约 165×105，宽高比 1.57），
 * 四块拼在一起整张卡片就成了横卡。
 *
 * 这类错误有两个特点，所以必须用断言守住：
 *   1. **只有拿到真机、真的盯着看才会发现**，读代码看不出问题；
 *   2. 它曾经以「只有色块」的形式出现过（高度推算过大把文字挤出卡片），
 *      说明这一处的尺寸很容易被再次改坏。
 *
 * 竖版 2:3 是主流阅读器（番茄、微信读书）书架上封面的统一比例，
 * 也是本应用书架网格里用的比例 —— 分组卡不该是例外。
 */
class GroupThumbSizeTest {

    @Test
    fun `分组缩略封面必须是竖版 2 比 3`() {
        // 覆盖常见屏宽下算出来的各种列宽，包括极端值
        listOf(120.dp, 148.dp, 176.dp, 220.dp, 320.dp).forEach { columnWidth ->
            val size = groupThumbSize(columnWidth)
            assertTrue(
                "列宽 $columnWidth 时缩略图算出了 ${size.width}×${size.height} —— " +
                    "高度必须大于宽度（竖版），否则就是用户看到的「横置」",
                size.height > size.width,
            )
            assertEquals(
                "列宽 $columnWidth 时宽高比应为 2:3",
                1.5f,
                size.height.value / size.width.value,
                0.001f,
            )
        }
    }

    @Test
    fun `缩略图两块加中间缝不会超出卡片宽度`() {
        listOf(148.dp, 176.dp, 240.dp).forEach { columnWidth ->
            val size = groupThumbSize(columnWidth)
            val used = size.width * 2 + 4.dp + 8.dp * 2
            assertTrue(
                "列宽 $columnWidth 时两列缩略图 + 间距 + 内边距共 $used，超过了列宽",
                used <= columnWidth,
            )
        }
    }
}
