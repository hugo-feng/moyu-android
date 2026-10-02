package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.reader.ReadingStatsCalculator
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.StatsViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.MoyuTopBar
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 阅读统计页。
 *
 * 信息层级：总览卡片 → 半年热力图（可点选单日）→ 洞察 → 最近会话。
 * 统计口径全部由 ReadingStatsCalculator 提供（与 Web 验证器同源），
 * 这里只负责把数字排成可读的层级，不重复实现任何聚合逻辑。
 */
@Composable
fun StatsScreen(
    factory: MoyuViewModelFactory,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val viewModel: StatsViewModel = viewModel(factory = factory)
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        MoyuTopBar(title = "阅读统计", onBack = onBack)

        val current = stats
        // 首次加载时 stats 还是 null，显示占位；
        // 之后即便在刷新也继续显示上一次的统计结果，避免页面闪成空白。
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在统计…",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
            return@Column
        }

        if (current.totalSessions == 0) {
            EmptyState(
                icon = Icons.Filled.BarChart,
                title = "还没有阅读记录",
                description = "读一会儿书，这里会出现你的阅读时长、连续天数与热力图。",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // —— 总览 ——
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        value = ReadingStatsCalculator.formatSeconds(current.totalSeconds),
                        label = "累计阅读",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = "${current.totalDays} 天",
                        label = "有阅读的天数",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        value = "${current.currentStreak} 天",
                        label = "当前连续 · 最长 ${current.longestStreak} 天",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = formatChars(current.totalChars),
                        label = "累计字数",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        value = "${current.charsPerMinute}",
                        label = "阅读速度（字/分）",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = ReadingStatsCalculator.formatSeconds(current.last7DaysSeconds),
                        label = "最近 7 天",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // —— 热力图 ——
            SectionHeader("阅读热力图")
            Heatmap(
                weeks = ReadingStatsCalculator.buildHeatmap(current.daily, 26),
                onSelectDate = { viewModel.selectDate(it) },
            )
            HeatmapLegend()

            // 选中某天的详情
            if (selectedDate != null) {
                val day = selectedDate!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = day.date,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.text,
                        )
                        Text(
                            text = if (day.seconds > 0) {
                                "${ReadingStatsCalculator.formatSeconds(day.seconds)} · ${formatChars(day.chars)} 字"
                            } else {
                                "这天没有阅读"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                        )
                    }
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.primary,
                        modifier = Modifier.clickable { viewModel.clearSelectedDate() },
                    )
                }
            }

            // —— 洞察 ——
            SectionHeader("阅读洞察")
            ReadingStatsCalculator.buildInsights(current.daily).forEach { insight ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.primary),
                    )
                    Text(
                        text = insight,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            // —— 阅读日历（最近有阅读的天） ——
            val activeDays = current.daily.filter { it.seconds > 0 }.sortedByDescending { it.date }.take(14)
            if (activeDays.isNotEmpty()) {
                SectionHeader("最近阅读")
                activeDays.forEach { day ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = day.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(
                            text = ReadingStatsCalculator.formatSeconds(day.seconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.primary,
                            modifier = Modifier.width(90.dp),
                        )
                        Text(
                            text = "${day.sessions} 次 · ${formatChars(day.chars)} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = moyuPalette().textSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val palette = moyuPalette()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = palette.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 热力图：7 行（周日起）× N 列。
 *
 * 单元格是可点击的按钮，点击显示当天详情 —— 这是微信读书/GitHub 的通用交互。
 * 网格用 Column + Row 手工排布而不是 LazyVerticalGrid：
 * 26×7 = 182 个格子，一次全画的开销完全可以接受，
 * 而且这样能精确控制「一列一周、周日起」的布局语义。
 */
@Composable
private fun Heatmap(
    weeks: List<ReadingStatsCalculator.HeatmapWeek>,
    onSelectDate: (String) -> Unit,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.days.forEach { day ->
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(heatColor(day.level, day.future, palette.divider))
                            .clickable(enabled = !day.future) { onSelectDate(day.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatmapLegend() {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "少",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
        Spacer(Modifier.width(6.dp))
        (0..4).forEach { level ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(heatColor(level, false, palette.divider)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "多",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
    }
}

/** 热力等级 → 颜色。用主色的不同透明度，保证与主题同族。 */
@Composable
private fun heatColor(level: Int, future: Boolean, fallback: Color): Color {
    val palette = moyuPalette()
    if (future) return Color.Transparent
    return when (level) {
        0 -> fallback.copy(alpha = 0.45f)
        1 -> palette.primary.copy(alpha = 0.28f)
        2 -> palette.primary.copy(alpha = 0.48f)
        3 -> palette.primary.copy(alpha = 0.70f)
        else -> palette.primary
    }
}

/** 大数字用「万」表示，避免总览卡片里的数字过长。 */
private fun formatChars(chars: Int): String =
    if (chars >= 10000) String.format("%.1f 万", chars / 10000.0) else "$chars"
