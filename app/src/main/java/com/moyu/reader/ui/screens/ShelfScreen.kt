package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.moyu.reader.ui.ShelfGroup
import com.moyu.reader.ui.ShelfStats
import com.moyu.reader.ui.ShelfViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.components.MoyuChip
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.SegmentedControl
import com.moyu.reader.ui.components.ThinProgressBar
import com.moyu.reader.ui.safeBottom
import com.moyu.reader.ui.safeTop
import com.moyu.reader.ui.theme.moyuPalette
import java.io.File

/**
 * 书架页 —— 应用首页。
 *
 * 信息层级（自上而下，与主流阅读 App 一致）：问候语与概览 → 继续阅读 → 工具栏 → 筛选 → 书籍网格。
 * 「继续阅读」放在最显眼的位置是因为它是最高频的动作：
 * 用户打开阅读器的绝大多数时候就是想接着上次的地方读。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShelfScreen(
    factory: MoyuViewModelFactory,
    onOpenBook: (String) -> Unit,
    onOpenSearch: (String?) -> Unit,
    onOpenImport: () -> Unit,
    onOpenStats: () -> Unit,
    /**
     * 打开某本书的笔记页。
     *
     * 参数从「无」改成「bookId」：全局笔记标签已被阅读历史取代，
     * 笔记改为**按书**查看。书架列表里每行的「笔记」按钮需要告诉
     * 目标页面是哪本书，否则会打开一个空页面。
     */
    onOpenNotes: (String) -> Unit,
    /**
     * 当前页面是「书库」还是「书架」。
     *
     * 书库 = 全部导入的书；书架 = 用户主动加入的那些。
     * 两者共用这一个 Composable：列表形态、筛选、排序、分组、
     * 单本操作完全一致，唯一差别是标题与「加入/移出书架」那一个动作。
     */
    libraryMode: Boolean = false,
) {
    val viewModel: ShelfViewModel = viewModel(factory = factory)

    // 由页面决定进入哪种模式。用 LaunchedEffect 而不是构造参数：
    // ViewModel 是导航层级共享的，切换标签时它不会重建，
    // 必须在进入页面时显式告知当前是哪一边。
    LaunchedEffect(libraryMode) { viewModel.setLibraryMode(libraryMode) }
    val onToggleShelf: (com.moyu.reader.data.model.Book) -> Unit = { viewModel.toggleInShelf(it) }

    val items by viewModel.items.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val shelfCount by viewModel.shelfCount.collectAsStateWithLifecycle()
    val continueItem by viewModel.continueReading.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val groupCards by viewModel.groupCards.collectAsStateWithLifecycle()
    val openGroupName by viewModel.openedGroupName.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val palette = moyuPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
        // 书架是唯一没有顶栏的页面（内容直接顶到屏幕最上面），
        // 因此状态栏的安全区必须它自己让出来，否则问候语和「我的书架」会被压住。
        // 底部同时留出：底部导航栏高度 + 手势条，避免最后一行被遮。
        contentPadding = PaddingValues(
            top = safeTop,
            bottom = 80.dp + safeBottom,
        ),
    ) {
        /**
         * 书架模式**不显示顶部区块**。
         *
         * 用户的原话是「书架只应该显示书，乱七八糟的删掉，不应该直接克隆主页的布局」。
         * 原来两个模式共用同一套顶部（问候语 + 三个统计数字 + 继续阅读卡 +
         * 排序筛选标签 + 新建分组），于是书架页看起来就是主页的复制品，
         * 而它真正该做的事只有一件：把书架上的书列出来。
         *
         * 那套顶部属于**书库**（进入应用后的第一个页面，承担「总览」职责）：
         * 统计数字与统计入口留在那里，书架页保持干净。
         */
        if (libraryMode) {
            item {
                ShelfHeader(
                    greeting = viewModel.greeting(),
                    stats = stats,
                    libraryMode = true,
                    shelfCount = shelfCount,
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

            // 排序与布局已移到设置页（见 SettingsScreen 的外观与主题 / 书架分组）。
            // 这里只剩「搜索全书内容」与「导入」两个高频动作。
            item {
                SubHeaderActions(
                    onSearch = { onOpenSearch(null) },
                    onImport = onOpenImport,
                )
            }
        } else {
            item {
                ShelfTitleRow(
                    title = openGroupName ?: "书架",
                    subtitle = if (openGroupName != null) {
                        "${items.size} 本书 · 来自分组"
                    } else {
                        "${items.size} 本书"
                    },
                    onBack = if (openGroupName != null) {
                        { viewModel.closeGroup() }
                    } else {
                        null
                    },
                )
            }
        }

        /**
         * 分组卡片（仅书架、且未钻入任何分组时）。
         *
         * 形态向番茄小说学习：书架首页先给结构，而不是铺满几百本书的封面。
         * 每张卡片里露 4 本缩略封面 + 分组名 + 本数，点进去才是这个分组的书。
         *
         * 「全部」永远是第一张 —— 它保证用户随时能看到所有书，
         * 也提供了一个「我刚从分组里出来」的落脚点。
         */
        if (!libraryMode && openGroupName == null && groupCards.isNotEmpty()) {
            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 12.dp
                    val available = maxWidth - 28.dp
                    val minColumn = 148.dp
                    val columns = (((available + gap) / (minColumn + gap)).toInt()).coerceIn(2, 3)

                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        groupCards.forEach { group ->
                            GroupCard(
                                group = group,
                                onClick = { viewModel.openGroup(group.id) },
                                onDelete = if (group.id == ShelfViewModel.ALL_GROUP_ID) {
                                    null
                                } else {
                                    { viewModel.deleteGroup(group.id) }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        NewGroupCard(
                            onCreate = { name -> viewModel.createGroup(name) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        /**
         * 是否处在「分组总览」这一层。
         *
         * 总览下只显示分组卡片，**不显示书的空状态** ——
         * 那时书还没列出来，显示「书架还空着」会与上面的分组卡片自相矛盾
         * （卡片上明明写着有多少本）。
         */
        val atGroupOverview = !libraryMode && openGroupName == null && groupCards.isNotEmpty()
        if (items.isEmpty() && !atGroupOverview) {
            item {
                EmptyState(
                    icon = if (libraryMode) Icons.AutoMirrored.Filled.MenuBook else Icons.Filled.BookmarkBorder,
                    title = if (libraryMode) "书库还空着" else "书架还空着",
                    description = if (libraryMode) {
                        // 导入入口已移到设置页；这里同时说明去哪儿导入，
                        // 否则用户会停在空页面上找不到下一步
                        "在「设置 → 导入本地书籍」里把 TXT / EPUB / PDF 加进来，或先加载一本示例书。"
                    } else {
                        "书架只放你主动加入的书。去「书库」里挑一本，点「加入书架」它就会出现在这里。"
                    },
                    action = {
                        // 书库为空时给两个起步动作；书架为空时只需要一句指引 ——
                        // 书架的清空是用户自己造成的，给按钮反而显得啰嗦
                        if (libraryMode) {
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
        } else if (settings.shelfLayout == ShelfLayout.GRID) {            /**
             * 网格布局。
             *
             * 这里不用 LazyVerticalGrid，原因是一个真实的缺陷：
             * 之前把它嵌在 LazyColumn 的一个 item 里，为了让它「撑开」而硬编码了高度
             * `((books/3 + 1) * 200).dp` 并禁用它的滚动 —— 那个公式同时假设了「3 列」
             * 和「每行 200dp」，两个都不成立（实际每行约 237dp，窄屏只有 2 列）。
             * 于是容器高度小于内容高度，**最后一排书根本不会被组合出来，看不见也点不到**。
             *
             * FlowRow 会自然撑到内容高度，不需要任何高度预测；书架的书籍数量在几十本量级，
             * 一次性组合的开销完全可以接受（真正需要懒加载的是成千上万条的列表）。
             */
            item {
                /**
                 * 列数按可用宽度算，而不是写死。
                 *
                 * 每列最小 104dp（与原来的 GridCells.Adaptive 同口径），
                 * 再按 14dp 间隔推算最多能放几列；至少 1 列，避免极窄屏算出 0。
                 * 这样 2 列 / 3 列 / 4 列都能正确适配，也不会再有「算错列数」的问题。
                 */
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 14.dp
                    val available = maxWidth - 28.dp // 左右各 14dp 内边距
                    val minColumn = 104.dp
                    val columns = (((available + gap) / (minColumn + gap)).toInt()).coerceAtLeast(1)

                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // 注意这里用 forEach 而不是 LazyListScope.items：
                        // 网格分支里的局部变量 `items` 会遮蔽那个扩展函数。
                        items.forEach { item ->
                            BookGridCard(
                                item = item,
                                onClick = { onOpenBook(item.book.id) },
                                onToggleShelf = { onToggleShelf(item.book) },
                                groups = groups,
                                onAssignGroup = { gid -> viewModel.assignToGroup(item.book, gid) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        } else {
            items(items, key = { it.book.id }) { item ->
                BookListRow(
                    item = item,
                    onClick = { onOpenBook(item.book.id) },
                    onNotes = { onOpenNotes(item.book.id) },
                    onToggleShelf = { onToggleShelf(item.book) },
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
    libraryMode: Boolean,
    shelfCount: Int,
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
            text = if (libraryMode) "书库" else "书架",
            style = MaterialTheme.typography.headlineLarge,
            color = palette.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        // 副标题说明两边的区别 —— 不写的话用户不知道书库与书架差在哪，
        // 会以为「加入书架」是个没有作用的按钮。
        Text(
            text = if (libraryMode) {
                "本机全部书籍（$shelfCount 本已在书架）"
            } else {
                "已加入书架的书"
            },
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
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

/**
 * 书库页的次要操作行：搜索 + 导入。
 *
 * 布局切换与排序**不在这里** —— 它们已移到设置页。
 * 那两个是「设一次就不动」的偏好，每次进书库都看到一排标签
 * 只会让页面显得杂乱（用户的原话是「这太难看了」）。
 */

/**
 * 书库页的次要操作行：搜索 + 导入。
 *
 * 布局切换与排序**不在这里** —— 它们已移到设置页。
 * 那两个是「设一次就不动」的偏好，每次进书库都看到一排标签
 * 只会让页面显得杂乱（用户的原话是「这太难看了」）。
 */
@Composable
private fun SubHeaderActions(
    onSearch: () -> Unit,
    onImport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MoyuTextButton(text = "搜索", icon = Icons.Filled.Search, onClick = onSearch)
        MoyuTextButton(text = "导入", icon = Icons.Filled.Upload, onClick = onImport)
    }
}

/** 书架页的标题行：一个标题 + 一个数量，必要时带返回。 */
@Composable
private fun ShelfTitleRow(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 钻入分组后才显示返回：分组总览是这一栏的根，没有可返回的地方
        if (onBack != null) {
            IconAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回分组列表",
                onClick = onBack,
            )
        }
        Column(modifier = Modifier.padding(start = if (onBack != null) 0.dp else 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = palette.text,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 分组卡片：4 本缩略封面 + 名称 + 本数。
 *
 * 形态参考番茄小说：用「一组书的缩略图」表达一个分组，
 * 比一行文字标签更容易一眼分辨 —— 用户认封面比认名字快。
 *
 * 缩略图用固定的小尺寸 + 封面自身的渐变，**不复用完整的 BookCover**：
 * 那个组件带进度条、格式角标、「加入书架」按钮，缩到 40dp 宽全都看不清，
 * 反而变成一团噪声。
 */
@Composable
private fun GroupCard(
    group: ShelfGroup,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        // 2 列缩略图。用 Row/Column 手动排而不是 LazyVerticalGrid：
        // 只有 4 个固定格子，嵌套一个可滚动容器只会带来测量问题。
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) { col ->
                        val book = group.previewBooks.getOrNull(row * 2 + col)
                        GroupThumb(book = book, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group.color != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(parseHexColor(group.color) ?: palette.primary),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onDelete != null) {
                Text(
                    text = if (confirmDelete) "确认" else "✕",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (confirmDelete) Color(0xFFD9584A) else palette.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            if (confirmDelete) {
                                onDelete()
                                confirmDelete = false
                            } else {
                                confirmDelete = true
                            }
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = if (confirmDelete) "会删掉分组，书不受影响" else "共 ${group.count} 本",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
        )
    }
}

/** 分组卡片里的一个小缩略封面；[book] 为 null 时画一个空格子占位。 */
@Composable
private fun GroupThumb(book: com.moyu.reader.data.model.Book?, modifier: Modifier = Modifier) {
    val palette = moyuPalette()
    // 底色分两种类型（Brush / Color），必须用 if 包住整个 background 调用 ——
    // 写成 background(if (...) gradient else color) 推不出公共类型，编译不过。
    val base = modifier
        .aspectRatio(3f / 4.3f)
        .clip(RoundedCornerShape(4.dp))
    Box(
        modifier = if (book != null) {
            base.background(coverGradient(book.title))
        } else {
            base.background(palette.divider)
        },
    ) {
        if (book != null) {
            val file = book.coverPath?.let { File(it) }
            var failed by remember(book.coverPath) { mutableStateOf(false) }
            if (file != null && file.exists() && !failed) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 「新建分组」卡片：点开后在原位变成输入框，不弹对话框。 */
@Composable
private fun NewGroupCard(
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(enabled = !creating) { creating = true }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与 GroupCard 的缩略图区等高，这样两种卡片在同一行里高度一致
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 1.46f),
            contentAlignment = Alignment.Center,
        ) {
            if (creating) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("分组名", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (creating) {
            Row(
                modifier = Modifier.padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MoyuTextButton(
                    text = "创建",
                    enabled = name.isNotBlank(),
                    onClick = {
                        onCreate(name)
                        name = ""
                        creating = false
                    },
                )
                MoyuTextButton(text = "取消", onClick = { creating = false; name = "" })
            }
        } else {
            Text(
                text = "新建分组",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

/** 把 `#RRGGBB` 解析成 Color；解析不了返回 null（由调用方兜底）。 */
private fun parseHexColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    null
}

/** 筛选与分组。 */@Composable
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

/**
 * 网格布局下的书籍卡片。
 *
 * 长按弹出「移到分组」菜单 —— 这是把书放进分组的入口。
 * 之所以用长按而不是在卡片上再加一个按钮：书架页要保持干净
 * （用户明确要求「只显示书」），而移动分组是低频动作，
 * 藏在长按里既不影响观感，也不需要额外的界面空间。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    onToggleShelf: () -> Unit,
    groups: List<com.moyu.reader.data.model.BookGroup> = emptyList(),
    onAssignGroup: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var showGroupMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            /**
             * 长按弹分组菜单，点击进书。
             *
             * 用 `combinedClickable` 而不是在 Column 上叠一层 pointerInput：
             * 后者会与内部的「加入书架」按钮抢事件，导致按钮偶尔点不动。
             */
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (groups.isNotEmpty()) showGroupMenu = true },
            )
            .padding(2.dp),
    ) {
        if (showGroupMenu) {
            androidx.compose.material3.DropdownMenu(
                expanded = true,
                onDismissRequest = { showGroupMenu = false },
            ) {
                Text(
                    text = "移到分组",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                groups.forEach { g ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(g.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onAssignGroup(g.id)
                            showGroupMenu = false
                        },
                    )
                }
                if (item.book.groupId != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                "移出分组",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD9584A),
                            )
                        },
                        onClick = {
                            onAssignGroup(null)
                            showGroupMenu = false
                        },
                    )
                }
            }
        }
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
        /**
         * 「加入书架 / 移出书架」。
         *
         * 网格卡片的宽度有限，因此这里用**文字按钮**而不是图标 ——
         * 图标看不出一本书当前在不在书架里，而这两个动作的差别恰恰是状态。
         * 已加入时按钮文案变成「已在书架」，一眼能看出当前状态。
         */
        Text(
            text = if (item.book.inShelf) "已在书架" else "加入书架",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.book.inShelf) palette.textSecondary else palette.primary,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onToggleShelf)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** 列表布局下的行。 */
@Composable
private fun BookListRow(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    onNotes: () -> Unit,
    onToggleShelf: () -> Unit,
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
            icon = if (item.book.inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            contentDescription = if (item.book.inShelf) "移出书架" else "加入书架",
            onClick = onToggleShelf,
        )
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
            /**
             * 必须显式 `fillMaxWidth()` 再 `aspectRatio`，不能只靠上面的分支。
             *
             * `aspectRatio` 要有**确定的宽度约束**才能算出高度。网格里传的是
             * `width = null`（即 fillMaxWidth），看起来一样，但如果外层是
             * `weight(1f)` 这类由父级先分配、再测量子级的容器，
             * 宽度约束在测量时可能还是「未定」，`aspectRatio` 便算出异常高度 ——
             * 实测表现是封面被撑成很高的色块，**书名与格式角标被挤出可视区**，
             * 用户看到的就是「一个纯色矩形，什么字都没有」。
             */
            .fillMaxWidth()
            .aspectRatio(3f / 4.3f)
            .clip(shape)
            .background(coverGradient(book.title)),
    ) {
        val coverFile = book.coverPath?.let { File(it) }

        /**
         * 封面图是否真的能显示。
         *
         * `coverFile.exists()` **不足以**说明能显示 —— 文件可能存在但内容
         * 不是有效图片（写了一半、被别的程序截断、扩展名对不上格式）。
         * 这时 Coil 解码失败，`AsyncImage` 什么也不画，用户看到的就是
         * 底下的渐变 —— 也就是「只有一个色块，什么字都没有」。
         *
         * 因此把加载失败也当作「没有封面」处理：`onError` 里把状态置为
         * 加载失败，于是改画带书名的程序生成封面。宁可显示一个朴素的书脊，
         * 也不要显示一个没有辨识度的纯色矩形。
         */
        var coverFailed by remember(book.coverPath) { mutableStateOf(false) }
        val showCoverImage = coverFile != null && coverFile.exists() && !coverFailed

        if (showCoverImage) {
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!showCoverImage) {
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

