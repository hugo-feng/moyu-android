package com.moyu.reader.ui.screens

import com.moyu.reader.ui.theme.moyuPalette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.prefs.ShelfLayout
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ShelfFilter
import com.moyu.reader.ui.ShelfStats
import com.moyu.reader.ui.ShelfViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.components.MoyuChip
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.SegmentedControl
import com.moyu.reader.ui.components.ThinProgressBar
import com.moyu.reader.ui.theme.moyuPalette
import java.io.File

/**
 * 书架页 —— 应用首页。
 *
 * 信息层级（自上而下，与主流阅读 App 一致）：问候语与概览 → 继续阅读 → 工具栏 → 筛选 → 书籍网格。
 * 「继续阅读」放在最显眼的位置是因为它是最高频的动作：
 * 用户打开阅读器的绝大多数时候就是想接着上次的地方读。
 */
@Composable
fun ShelfScreen(
    factory: MoyuViewModelFactory,
    onOpenBook: (String) -> Unit,
    onOpenSearch: (String?) -> Unit,
    onOpenImport: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    val viewModel: ShelfViewModel = viewModel(factory = factory)
    val items by viewModel.items.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val continueItem by viewModel.continueReading.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val palette = moyuPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        item {
            ShelfHeader(
                greeting = viewModel.greeting(),
                stats = stats,
                onOpenStats = onOpenStats,
            )
        }

        if (continueItem != null) {
            item {
                ContinueReadingCard(
                    item = continueItem!!,
                    onClick = { onOpenBook(continueItem!!.book.id) },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }

        item {
            ShelfToolbar(
                layout = settings.shelfLayout,
                onLayoutChange = { viewModel.setLayout(it) },
                sort = settings.shelfSort,
                onSortChange = { viewModel.setSort(it) },
                onSearch = { onOpenSearch(null) },
                onImport = onOpenImport,
            )
        }

        item {
            ShelfFilters(
                current = filter,
                groups = groups.map { it.id to it.name },
                onSelect = { viewModel.setFilter(it) },
                onCreateGroup = { name -> viewModel.createGroup(name) },
            )
        }

        if (items.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = if (filter == ShelfFilter.All) "书架还空着" else "这个筛选下没有书",
                    description = if (filter == ShelfFilter.All) {
                        "导入本机的 TXT / EPUB / PDF 文件，或先加载一本示例书开始体验。"
                    } else {
                        "换一个筛选条件试试，或者导入新书。"
                    },
                    action = {
                        if (filter == ShelfFilter.All) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MoyuPrimaryButton(
                                    text = "导入本地书籍",
                                    icon = Icons.Filled.Upload,
                                    onClick = onOpenImport,
                                )
                                MoyuTextButton(
                                    text = if (importing) "正在准备示例书…" else "加载示例书",
                                    onClick = { viewModel.loadSampleBook() },
                                    enabled = !importing,
                                )
                            }
                        }
                    },
                )
            }
        } else if (settings.shelfLayout == ShelfLayout.GRID) {
            item {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((items.size / 3 + 1) * 200).dp.coerceAtMost(6000.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    userScrollEnabled = false,
                ) {
                    items(items, key = { it.book.id }) { item ->
                        BookGridCard(item = item, onClick = { onOpenBook(item.book.id) })
                    }
                }
            }
        } else {
            items(items, key = { it.book.id }) { item ->
                BookListRow(
                    item = item,
                    onClick = { onOpenBook(item.book.id) },
                    onNotes = onOpenNotes,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 顶部：问候语 + 概览数字 + 统计入口。 */
@Composable
private fun ShelfHeader(
    greeting: String,
    stats: ShelfStats,
    onOpenStats: () -> Unit,
) {
    val palette = moyuPalette()
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = Icons.Filled.ChevronRight,
                contentDescription = "阅读统计",
                onClick = onOpenStats,
            )
        }
        Text(
            text = "我的书架",
            style = MaterialTheme.typography.headlineLarge,
            color = palette.text,
            modifier = Modifier.padding(top = 4.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(1.dp)
                .background(palette.divider),
        )

        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            StatCell(value = "${stats.total}", label = "书籍数")
            StatCell(value = "${stats.finished}", label = "已读完")
            StatCell(
                value = com.moyu.reader.reader.ReadingStatsCalculator.formatSeconds(stats.weekSeconds),
                label = "本周阅读时长",
            )
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    val palette = moyuPalette()
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.primary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
    }
}

/** 继续阅读大卡。 */
@Composable
private fun ContinueReadingCard(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book = item.book, percent = item.percent, finished = item.finished, width = 54.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = "继续阅读",
                style = MaterialTheme.typography.labelSmall,
                color = palette.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = item.book.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = readingProgressLabel(item),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun readingProgressLabel(item: com.moyu.reader.data.model.ShelfItem): String = when {
    item.finished -> "已读完"
    item.unread -> "未开始 · ${item.book.chapterCount} 章"
    else -> "${(item.percent * 100).toInt()}% · 共 ${item.book.chapterCount} 章"
}

/** 工具栏：布局切换 + 排序 + 搜索/导入。 */
@Composable
private fun ShelfToolbar(
    layout: ShelfLayout,
    onLayoutChange: (ShelfLayout) -> Unit,
    sort: com.moyu.reader.data.prefs.ShelfSort,
    onSortChange: (com.moyu.reader.data.prefs.ShelfSort) -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedControl(
                options = listOf(
                    ShelfLayout.GRID to "网格",
                    ShelfLayout.LIST to "列表",
                ),
                selected = layout,
                onSelect = onLayoutChange,
            )
            Spacer(Modifier.weight(1f))
            MoyuTextButton(text = "搜索", icon = Icons.Filled.Search, onClick = onSearch)
            MoyuTextButton(text = "导入", icon = Icons.Filled.Upload, onClick = onImport)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShelfViewModel.SORT_OPTIONS.forEach { (value, label) ->
                MoyuChip(
                    text = label,
                    active = value == sort,
                    onClick = { onSortChange(value) },
                )
            }
        }
    }
}

/** 筛选与分组。 */
@Composable
private fun ShelfFilters(
    current: ShelfFilter,
    groups: List<Pair<String, String>>,
    onSelect: (ShelfFilter) -> Unit,
    onCreateGroup: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoyuChip("全部", current == ShelfFilter.All, onClick = { onSelect(ShelfFilter.All) })
            MoyuChip("在读", current == ShelfFilter.Reading, onClick = { onSelect(ShelfFilter.Reading) })
            MoyuChip("未读", current == ShelfFilter.Unread, onClick = { onSelect(ShelfFilter.Unread) })
            MoyuChip("已读完", current == ShelfFilter.Finished, onClick = { onSelect(ShelfFilter.Finished) })
        }

        if (groups.isNotEmpty() || creating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                groups.forEach { (id, name) ->
                    MoyuChip(name, current == ShelfFilter.Group(id), onClick = { onSelect(ShelfFilter.Group(id)) })
                }
            }
        }

        if (creating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                MoyuTextButton(
                    text = "创建",
                    onClick = {
                        onCreateGroup(newName)
                        newName = ""
                        creating = false
                    },
                    enabled = newName.isNotBlank(),
                )
                MoyuTextButton(text = "取消", onClick = { creating = false; newName = "" })
            }
        } else {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                MoyuTextButton(
                    text = "新建分组",
                    icon = Icons.Filled.Add,
                    onClick = { creating = true },
                )
            }
        }
    }
}

/** 网格布局下的书籍卡片。 */
@Composable
private fun BookGridCard(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
) {
    val palette = moyuPalette()
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
    ) {
        BookCover(
            book = item.book,
            percent = item.percent,
            finished = item.finished,
            unread = item.unread,
            width = null,
        )
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp, start = 1.dp, end = 1.dp),
        )
        Text(
            text = if (item.unread) item.book.author else item.lastReadLabel,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 1.dp, end = 1.dp),
        )
    }
}

/** 列表布局下的行。 */
@Composable
private fun BookListRow(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    onNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book = item.book, percent = item.percent, finished = item.finished, width = 50.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.book.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.book.author} · ${item.book.chapterCount} 章",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (item.percent > 0f) {
                ThinProgressBar(
                    progress = item.percent,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    text = if (item.finished) "已读完" else "${(item.percent * 100).toInt()}% · ${item.lastReadLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = "未开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        IconAction(
            icon = Icons.Filled.Search,
            contentDescription = "本书笔记",
            onClick = onNotes,
        )
    }
}

/**
 * 书籍封面。
 *
 * 有真实封面图（EPUB 提取或用户设置）就显示图片；
 * 否则用**书名哈希**生成一个稳定的渐变书脊封面。
 * 用哈希而不是随机：同一本书每次渲染的颜色必须一致，
 * 否则滚动列表时封面颜色会跳变，观感很差。
 */
@Composable
fun BookCover(
    book: Book,
    percent: Float = 0f,
    finished: Boolean = false,
    unread: Boolean = false,
    width: androidx.compose.ui.unit.Dp? = 104.dp,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(3f / 4.3f)
            .clip(shape)
            .background(coverGradient(book.title)),
    ) {
        val coverFile = book.coverPath?.let { File(it) }
        if (coverFile != null && coverFile.exists()) {
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = book.title.take(9),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.94f),
                fontWeight = FontWeight.Medium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 11.dp, top = 11.dp, end = 26.dp),
            )
            Text(
                text = when (book.format) {
                    BookFormat.TXT -> "TXT"
                    BookFormat.EPUB -> "EPUB"
                    BookFormat.PDF -> "PDF"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFD9584A)),
            )
        }

        if (percent > 0f && !finished) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.28f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.92f)),
                )
            }
        }

        if (finished) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "已读完",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 由书名生成稳定的渐变。
 *
 * 色相限制在暖色到青绿的区间（20°~200°），并压低饱和度，
 * 保证与纸感主题协调，而不会出现刺眼的荧光色。
 */
fun coverGradient(title: String): Brush {
    var hash = 17
    for (ch in title) hash = hash * 31 + ch.code
    val hue1 = 20 + (Math.abs(hash) % 180)
    val hue2 = (hue1 + 28) % 360
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue1.toFloat(), 0.32f, 0.42f),
            Color.hsl(hue2.toFloat(), 0.28f, 0.30f),
        )
    )
}

