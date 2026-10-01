package com.moyu.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 通用界面组件。
 *
 * 集中放在一处而不是散在各屏幕里：这些组件承载了本应用的视觉语言
 * （圆角尺度、留白节奏、文字层级），集中后才能保证「同一个东西在每页长得一样」。
 * 如果让每个屏幕各自实现一遍按钮和分组标题，几周后必然出现四五种圆角。
 */

/** 顶部栏。左/中/右三段式，中间标题居中。 */
@Composable
fun MoyuTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(palette.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
        } else {
            Spacer(Modifier.width(48.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.widthIn(min = 48.dp),
        ) {
            actions()
        }
    }
}

/** 仅图标的按钮。触摸目标固定 48dp，满足可访问性下限。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = moyuPalette().textSecondary,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 带文字的次要按钮。 */
@Composable
fun MoyuTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = palette.textSecondary)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.38f),
        )
    }
}

/** 主按钮（强调色填充）。 */
@Composable
fun MoyuPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) palette.primary else palette.primary.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** 分组标题（设置页、笔记页的章节分组）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = moyuPalette().textSecondary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** 一行设置项。[trailing] 放控件。 */
@Composable
fun SettingRow(
    label: String,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 设置项之间的细分隔线。 */
@Composable
fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(1.dp)
            .background(moyuPalette().divider.copy(alpha = 0.55f)),
    )
}

/** 分段控件。 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.divider.copy(alpha = 0.35f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) palette.card else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) palette.primary else palette.textSecondary,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** 可横滑的标签组（排序、筛选）。 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 单个标签。
 *
 * 参数顺序刻意把 `onClick` 放在 `modifier` 之前：这样调用方可以用尾随 lambda
 * （`MoyuChip("全部", active) { ... }`），这是 Compose 的习惯写法。
 * 若把 modifier 放最后，尾随 lambda 会被解析成 modifier，编译报错且不易察觉。
 */
@Composable
fun MoyuChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.primary.copy(alpha = 0.14f) else palette.divider.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) palette.primary else palette.textSecondary,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 空状态。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val palette = moyuPalette()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = palette.textSecondary.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.35f,
        )
        if (action != NO_ACTION) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** 用于判断是否传入了 action 的哨兵（Compose 无法直接判断 lambda 是否为空）。 */
private val NO_ACTION: @Composable () -> Unit = {}

/** 进度条（阅读进度、导入进度）。 */
@Composable
fun ThinProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = moyuPalette().divider,
    barColor: Color = moyuPalette().primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(barColor),
        )
    }
}

/** 让子内容继承一个更淡的前景色（用于禁用态）。 */
@Composable
fun DimmedContent(alpha: Float = 0.5f, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = alpha)) {
        content()
    }
}
