package com.moyu.reader.ui.screens

import com.moyu.reader.ui.theme.moyuPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.data.prefs.FontFamilyId
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ThemeId
import com.moyu.reader.reader.DictionaryProvider
import com.moyu.reader.ui.ReaderViewModel
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.components.MoyuChip
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.SegmentedControl
import com.moyu.reader.ui.safeDrawingBottomPadding
import com.moyu.reader.ui.safeDrawingTopPadding
import com.moyu.reader.ui.theme.fontDisplayName
import com.moyu.reader.ui.theme.paletteFor
import com.moyu.reader.ui.theme.themeDisplayName

/**
 * 阅读器的子组件集合。
 *
 * 与 ReaderScreen 分文件的原因：主文件已经承担了手势、分页、状态编排，
 * 再塞进十几个面板会让它难以阅读。这些子组件是**纯展示 + 回调**的，
 * 依赖只有 ViewModel 与设置，因此拆开后两边都更清晰。
 */

// ============================================================
// 顶部栏
// ============================================================

@Composable
fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    speaking: Boolean,
    /** 当前页是否已有书签，决定右上角书签图标是镂空还是填充。 */
    bookmarked: Boolean,
    onBack: () -> Unit,
    onSpeak: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 背景铺到状态栏底下（保持沉浸），内容下移到安全区内。
            // 顺序是「先背景、后 padding」，反过来会在状态栏处留一条透明缝。
            .background(palette.surface)
            .safeDrawingTopPadding()
            .height(54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.AutoMirrored.Filled.ArrowBack, "返回书架", onBack)
        /**
         * 书名与章节名**左对齐**。
         *
         * 原先这里是 `horizontalAlignment = CenterHorizontally`，
         * 于是两行文字在剩余空间里居中 —— 但左右两侧的按钮数量不同
         * （左边一个返回，右边三个），居中之后视觉上明显偏左，
         * 看起来就像「没有对齐」。用户明确指出了这一点。
         *
         * 改成左对齐：书名紧接着返回按钮开始，与下方正文的左边界
         * 基本对齐，读起来更稳。
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = bookTitle,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        /**
         * 右上角依次是：搜索、书签、朗读。
         *
         * 搜索与书签原先在底部工具栏里，用户要求把它们移到顶栏右边 ——
         * 底栏因此瘦了下来（见 ReaderBottomBar 的注释）。
         *
         * 书签用**两个不同图标**（BookmarkBorder ↔ Bookmark）而不是同一个换色：
         * 只换颜色在浅色主题下对比太弱，看不出状态变化。
         */
        IconAction(Icons.Filled.Search, "搜索全书内容", onSearch)
        IconAction(
            icon = if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            contentDescription = if (bookmarked) "取消本页书签" else "为本页加书签",
            onClick = onToggleBookmark,
        )
        IconAction(
            icon = if (speaking) Icons.Filled.Pause else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (speaking) "停止朗读" else "开始朗读",
            onClick = onSpeak,
        )
    }
}

// ============================================================
// 底部工具栏
// ============================================================

@Composable
fun ReaderBottomBar(
    chapterIndex: Int,
    chapterCount: Int,
    onChapterSeek: (Int) -> Unit,
    onToc: () -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    // 拖动进度时用本地状态，松手才真正跳转：
    // 否则每移动一格都会触发一次「加载章节 + 重新分页」，卡顿非常明显。
    var dragValue by remember(chapterIndex) { mutableStateOf(chapterIndex.toFloat()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 背景铺到手势条底下，内容抬到安全区之上。
            // 顺序「先背景、后 padding」不能反，否则手势条处会留一条透明缝。
            .background(palette.surface)
            .safeDrawingBottomPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 ${chapterIndex + 1}/$chapterCount 章",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
            Slider(
                value = dragValue,
                onValueChange = { dragValue = it },
                onValueChangeFinished = { onChapterSeek(dragValue.toInt()) },
                valueRange = 0f..(chapterCount - 1).coerceAtLeast(0).toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderToolButton("目录", Icons.AutoMirrored.Filled.List, onToc)
            ReaderToolButton("笔记", Icons.Filled.Edit, onNotes)

            /**
             * 「设置」进入阅读设置面板。
             *
             * 原先这里叫「排版」，点开只是字号行距那一组。用户要求改名成设置 ——
             * 因为下面这些已经全部并进同一个面板：排版、夜间、自动阅读、
             * 翻页方式、朗读。名字叫「排版」会让人以为夜间与自动阅读不在里面，
             * 反而找不到。
             *
             * 这是**阅读器内部**的设置面板，与底栏那个全局设置页是两回事：
             * 读书时最常调的是字号/主题/自动阅读，跳出去再回来会打断阅读。
             */
            ReaderToolButton("设置", Icons.Filled.FormatSize, onSettings)
        }
    }
}

@Composable
private fun ReaderToolButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val palette = moyuPalette()
    Column(
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = palette.textSecondary, modifier = Modifier.size(21.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ============================================================
// 通用底部面板
// ============================================================

/** 通用底部面板容器：半透明遮罩 + 从下弹出的面板。 */
@Composable
fun ReaderSheetContainer(
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.76f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onDismiss),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(maxHeightFraction)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(moyuPalette().surface)
                // 阻止点击穿透到遮罩（否则点面板内部也会关闭）
                .clickable(enabled = false) { },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 9.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(moyuPalette().divider),
                )
                content()
            }
        }
    }
}

/** 面板标题栏。 */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    action: @Composable () -> Unit = {},
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
        action()
        IconAction(
            icon = androidx.compose.material.icons.Icons.Filled.Close,
            contentDescription = "关闭",
            onClick = onClose,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.divider),
    )
}

// ============================================================
// 目录面板
// ============================================================

@Composable
fun TocSheet(
    headers: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var reversed by remember { mutableStateOf(false) }

    ReaderSheetContainer(onDismiss = onDismiss) {
        SheetHeader(
            title = "目录",
            subtitle = "${headers.size} 章",
            onClose = onDismiss,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("筛选章节", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            MoyuChip(
                text = if (reversed) "倒序" else "正序",
                active = reversed,
                onClick = { reversed = !reversed },
            )
        }

        val indices = headers.indices.filter { i ->
            query.isBlank() || headers[i].contains(query.trim())
        }.let { if (reversed) it.reversed() else it }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(indices, key = { it }) { index ->
                val active = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .background(
                            if (active) moyuPalette().primary.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = moyuPalette().textSecondary,
                        modifier = Modifier.width(30.dp),
                    )
                    Text(
                        text = headers[index],
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) moyuPalette().primary else moyuPalette().text,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 46.dp)
                        .height(1.dp)
                        .background(moyuPalette().divider.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

// ============================================================
// 阅读设置面板
// ============================================================

/**
 * 阅读设置面板（底栏那个「设置」按钮打开的就是它）。
 *
 * 原先叫「排版」，只放字号行距；现在夜间与自动阅读也并了进来
 * （它们原先占着底栏的两个按钮位），所以整体改名为「设置」更准确。
 *
 * 与底栏那个全局设置页的区别：这里是**读书时随手要调的东西**。
 * 跳出去再回来会打断阅读，所以留在阅读器内部。
 */
@Composable
fun TypographySheet(
    viewModel: ReaderViewModel,
    onDismiss: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val palette = moyuPalette()
    val autoReading by viewModel.autoReading.collectAsStateWithLifecycle()
    val density = androidx.compose.ui.platform.LocalDensity.current

    ReaderSheetContainer(onDismiss = onDismiss) {
        SheetHeader(title = "排版", onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            // —— 主题 ——
            SheetGroupTitle("主题")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeId.entries.forEach { theme ->
                    val swatch = paletteFor(theme)
                    val active = settings.theme == theme
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(swatch.background)
                            .then(
                                if (active) {
                                    Modifier.background(swatch.primary.copy(alpha = 0.12f))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { viewModel.quickSetTheme(theme) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = themeDisplayName(theme),
                            style = MaterialTheme.typography.labelSmall,
                            color = swatch.text,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }

            // —— 字号 / 行距 / 边距 ——
            SheetGroupTitle("字号与间距")
            StepperRow(
                label = "正文字号",
                value = "${settings.fontSizeSp}px",
                onMinus = { viewModel.updateSettings { it.setFontSize(settings.fontSizeSp - 1) } },
                onPlus = { viewModel.updateSettings { it.setFontSize(settings.fontSizeSp + 1) } },
            )
            StepperRow(
                label = "行距",
                value = String.format("%.1f", settings.lineHeightMultiplier),
                onMinus = { viewModel.updateSettings { it.setLineHeight(settings.lineHeightMultiplier - 0.1f) } },
                onPlus = { viewModel.updateSettings { it.setLineHeight(settings.lineHeightMultiplier + 0.1f) } },
            )
            StepperRow(
                label = "段间距",
                value = String.format("%.1f", settings.paragraphSpacingMultiplier),
                onMinus = { viewModel.updateSettings { it.setParagraphSpacing(settings.paragraphSpacingMultiplier - 0.2f) } },
                onPlus = { viewModel.updateSettings { it.setParagraphSpacing(settings.paragraphSpacingMultiplier + 0.2f) } },
            )
            StepperRow(
                label = "页边距",
                value = "${settings.marginDp}px",
                onMinus = { viewModel.updateSettings { it.setMargin(settings.marginDp - 2) } },
                onPlus = { viewModel.updateSettings { it.setMargin(settings.marginDp + 2) } },
            )

            // —— 字体 ——
            //
            // 原先这里是 `FontFamilyId.entries.take(3)` —— 只渲染前三个，
            // 后面新增的字体选项**根本不会显示**。用户要求「添加更多字体」
            // 却看不到任何变化，有一半原因就在这里。
            //
            // 现在用可换行的 ChipRow 把全部字体都列出来：
            // 一行放不下会自然折行，不需要手动截断。
            SheetGroupTitle("字体")
            com.moyu.reader.ui.components.ChipRow(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FontFamilyId.entries.forEach { font ->
                    MoyuChip(
                        text = fontDisplayName(font),
                        active = settings.fontFamily == font,
                        onClick = { viewModel.updateSettings { it.setFontFamily(font) } },
                    )
                }
            }

            // —— 翻页方式 ——
            SheetGroupTitle("翻页方式")
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                SegmentedControl(
                    // 仿真与平移已删除（真机上会让前后页文字重叠），
                    // 详见 PageMode 的注释。
                    options = listOf(
                        PageMode.COVER to "覆盖",
                        PageMode.SCROLL to "滚动",
                        PageMode.NONE to "无动画",
                    ),
                    selected = settings.pageMode,
                    onSelect = { viewModel.setPageMode(it) },
                )
            }

            // —— 护眼与亮度 ——
            SheetGroupTitle("护眼与亮度")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "护眼色温",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                    modifier = Modifier.width(72.dp),
                )
                Slider(
                    value = settings.eyeCareWarmth,
                    onValueChange = { viewModel.updateSettings { store -> store.setEyeCare(it) } },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(settings.eyeCareWarmth * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.width(40.dp),
                )
            }
            // 亮度不在这里调：它是系统级设置，应用内再叠一层会与系统的
            // 自动亮度互相打架（拉低后仍被系统按环境光改动，找不到原因）。
            // 系统下拉栏已经能调，不必重复提供。

            // —— 自动阅读节奏 ——
            //
            // 「自动阅读」的**开关**也从底栏移到了这里（原先底栏有一个
            // 「自动/暂停」按钮）。用户要求把自动阅读放进设置 ——
            // 它是一次性开启后就让应用自己翻页的功能，不需要常驻一个按钮；
            // 常驻反而容易被误触，一碰就开始自动翻页。
            SheetGroupTitle("自动阅读")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (autoReading) "正在自动阅读" else "自动阅读已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                MoyuTextButton(
                    text = if (autoReading) "暂停" else "开始",
                    onClick = { viewModel.toggleAutoRead(density) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${settings.autoReadSecondsPerPage} 秒/页",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                    modifier = Modifier.width(88.dp),
                )
                Slider(
                    value = settings.autoReadSecondsPerPage.toFloat(),
                    onValueChange = { v -> viewModel.updateSettings { store -> store.setAutoReadSeconds(v.toInt()) } },
                    valueRange = 2f..60f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SheetGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = moyuPalette().textSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                icon = androidx.compose.material.icons.Icons.Filled.Remove,
                contentDescription = "减小$label",
                onClick = onMinus,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = palette.text,
                modifier = Modifier.width(58.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconAction(
                icon = androidx.compose.material.icons.Icons.Filled.Add,
                contentDescription = "增大$label",
                onClick = onPlus,
            )
        }
    }
}

// ============================================================
// 选中文字操作条
// ============================================================

@Composable
fun SelectionActions(
    selectionText: String,
    onLookup: () -> Unit,
    onBookmark: () -> Unit,
    onHighlight: (color: String, note: String) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var noteMode by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已选 ${selectionText.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "取消选择",
                onClick = onDismiss,
            )
        }

        if (noteMode) {
            androidx.compose.material3.OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("写点想法（可留空）", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MoyuTextButton(
                    text = "取消",
                    onClick = { noteMode = false; note = "" },
                    modifier = Modifier.weight(1f),
                )
                MoyuPrimaryButton(
                    text = "保存划线",
                    onClick = { onHighlight("#8A6A46", note) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionChip("划线", Icons.Filled.Edit) { noteMode = true }
                SelectionChip("查词", Icons.Filled.Search) { onLookup() }
                SelectionChip("书签", Icons.Filled.Bookmark) { onBookmark() }
                SelectionChip("复制", Icons.Filled.ContentCopy) { onCopy() }
            }
        }
    }
}

@Composable
private fun SelectionChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    MoyuTextButton(text = label, icon = icon, onClick = onClick)
}

// ============================================================
// 查词浮层
// ============================================================

@Composable
fun DictionaryPopup(
    loading: Boolean,
    result: DictionaryProvider.LookupResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result?.query ?: "查询中…",
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            result?.entries?.firstOrNull()?.phonetic?.let { phonetic ->
                Text(
                    text = phonetic,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
            IconAction(
                icon = androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "关闭查词",
                onClick = onDismiss,
            )
        }

        if (loading) {
            Text(
                text = "查询中…",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (result != null && result.miss) {
            Text(
                text = "内置词典未收录该词条。可以在「设置 → 词典」导入自定义词典，或选中更多字再查。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                result?.entries?.take(6)?.forEach { entry ->
                    entry.senses.take(3).forEach { sense ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            if (sense.pos != null) {
                                Text(
                                    text = sense.pos,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(34.dp),
                                )
                            }
                            Text(
                                text = sense.definition,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 阅读器内笔记面板
// ============================================================

@Composable
fun ReaderNotesSheet(
    viewModel: ReaderViewModel,
    onJump: (chapterIndex: Int, chapterOffset: Int) -> Unit,
    onOpenAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    ReaderSheetContainer(onDismiss = onDismiss) {
        SheetHeader(
            title = "本书笔记",
            subtitle = "${bookmarks.size} 书签 · ${highlights.size} 划线",
            onClose = onDismiss,
            action = {
                MoyuTextButton(text = "全部", onClick = onOpenAll)
            },
        )

        if (bookmarks.isEmpty() && highlights.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "还没有书签和笔记",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textSecondary,
                )
                Text(
                    text = "阅读时长按选中文字即可划线、写想法或添加书签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@ReaderSheetContainer
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(bookmarks, key = { it.id }) { bookmark ->
                NoteRow(
                    chapterLabel = "第 ${bookmark.chapterIndex + 1} 章",
                    text = bookmark.excerpt.ifEmpty { "（书签）" },
                    note = bookmark.note,
                    timeLabel = com.moyu.reader.data.repository.BookRepository.formatRelativeTime(bookmark.createdAt),
                    onClick = { onJump(bookmark.chapterIndex, bookmark.chapterOffset) },
                    onDelete = { viewModel.deleteBookmark(bookmark.id) },
                )
            }
            items(highlights, key = { it.id }) { highlight ->
                NoteRow(
                    chapterLabel = "第 ${highlight.chapterIndex + 1} 章",
                    text = highlight.text,
                    note = highlight.note,
                    timeLabel = com.moyu.reader.data.repository.BookRepository.formatRelativeTime(highlight.createdAt),
                    colorHex = highlight.color,
                    onClick = { onJump(highlight.chapterIndex, highlight.startOffset) },
                    onDelete = { viewModel.deleteHighlight(highlight.id) },
                )
            }
        }
    }
}

@Composable
private fun NoteRow(
    chapterLabel: String,
    text: String,
    note: String,
    timeLabel: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    colorHex: String? = null,
) {
    val palette = moyuPalette()
    // 删除采用二次确认：笔记是用户亲手留下的内容，误删的代价比多点一次高得多
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (colorHex != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(parseColor(colorHex)),
                )
            }
            Text(
                text = chapterLabel,
                style = MaterialTheme.typography.labelSmall,
                color = palette.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(onClick = onClick),
        )

        if (note.isNotEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.divider.copy(alpha = 0.3f))
                    .padding(9.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            MoyuTextButton(
                text = if (confirming) "确认删除" else "删除",
                onClick = { if (confirming) onDelete() else confirming = true },
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.divider.copy(alpha = 0.45f)),
    )
}

/** 解析 #RRGGBB 颜色；非法值退回主题主色，避免因数据问题崩溃。 */
private fun parseColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF8A6A46))

// ============================================================
// 提示条
// ============================================================

@Composable
fun ToastMessage(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(2200)
        onDismiss()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xEE2D2419))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFF7F3EA),
        )
    }
}
