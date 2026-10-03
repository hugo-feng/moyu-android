package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.model.displayName
import com.moyu.reader.data.model.ReadingStatus
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.ui.ShelfViewModel
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.ThinProgressBar
import com.moyu.reader.ui.safeBottom
import com.moyu.reader.ui.safeTop
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 书籍详情页。
 *
 * ## 为什么需要它
 *
 * 之前书库里点一本书**直接进阅读器**，结果没有任何地方能「把书加入书架」——
 * 分组、书架、书库三者之间的动线断了。用户的原话是
 * 「现在完全没有方法把书籍从书库导入书架内」。
 *
 * 布局参考番茄小说的详情页：
 *   顶部 左封面 + 右标题/作者/字数  →  「开始阅读」主按钮
 *        →  加入书架 / 目录 / 笔记 这类次要操作
 *        →  简介与元信息
 *
 * ## 与「长按菜单」的分工
 *
 * 书库里点书 → 详情页（先看信息再决定读不读）。
 * 详情页承担**所有单书操作**，卡片上只留一个「加入书架」快捷动作 ——
 * 这样书架页可以保持干净（用户要求「书架只应该显示书」），
 * 而功能一个都不少。
 */
@Composable
fun BookDetailScreen(
    factory: com.moyu.reader.ui.MoyuViewModelFactory,
    bookId: String,
    onBack: () -> Unit,
    /** 开始/继续阅读；restart = true 表示「重新阅读」，要从第一章开头读起 */
    onRead: (bookId: String, restart: Boolean) -> Unit,
    /** 点目录里的某一章：直接进阅读器并定位到那一章 */
    onOpenChapter: (bookId: String, chapterIndex: Int) -> Unit,
    /** 本书书签（笔记功能已删除，只保留书签） */
    onOpenBookmarks: (String) -> Unit,
) {
    val viewModel: ShelfViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
    val items by viewModel.items.collectAsStateWithLifecycle()
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()
    val tree by viewModel.tocTree.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    // 详情页可能从书库或书架进入，两边都查一下
    val item = remember(items, libraryItems, bookId) {
        items.firstOrNull { it.book.id == bookId }
            ?: libraryItems.firstOrNull { it.book.id == bookId }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    if (item == null) {
        // 书被删掉后详情页会短暂留在这里，直接给一个明确的空态而不是空白
        Box(modifier = Modifier.fillMaxSize().background(palette.surface)) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这本书已不在库中", color = palette.textSecondary)
                Spacer(Modifier.height(10.dp))
                MoyuTextButton(text = "返回", onClick = onBack)
            }
        }
        return
    }

    val book = item!!.book

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        // —— 顶栏：返回 + 编辑入口 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = safeTop, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Spacer(Modifier.weight(1f))
            IconAction(
                icon = if (book.inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (book.inShelf) "移出书架" else "加入书架",
                onClick = { viewModel.toggleInShelf(book) },
            )
        }

        // —— 头部：封面 + 元信息 ——
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            /**
             * 详情页的封面给**固定宽高**而不是 aspectRatio。
             *
             * 列表里的封面用 weight + aspectRatio 会在宽度约束未定时
             * 退化成用高度反推宽度（Pinterest 模式），算出接近正方形的块 ——
             * 这正是用户反复看到的「只有色块、书名被挤出容器」。
             * 详情页只有一个封面，直接给死尺寸最省事也最可靠。
             */
            BookCover(
                book = book,
                percent = item!!.percent,
                finished = item!!.finished,
                unread = item!!.unread,
                width = 118.dp,
                fixedHeight = 168.dp,
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = book.author.ifBlank { "佚名" },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(book.format.displayName)
                        append(" · ")
                        append(
                            when {
                                book.charCount >= 10000 -> "${book.charCount / 10000} 万字"
                                else -> "${book.charCount} 字"
                            }
                        )
                        append(" · ${book.chapterCount} 章")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        item!!.finished -> "已读完"
                        item!!.unread -> "未开始"
                        else -> "已读 ${(item!!.percent * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.primary,
                )
                Spacer(Modifier.height(6.dp))
                ThinProgressBar(progress = item!!.percent, modifier = Modifier.fillMaxWidth())
            }
        }

        // —— 主按钮：开始/继续/重新阅读 ——
        //
        // 「重新阅读」必须真的从第一章开头开始。之前三个文案走的是同一条路径，
        // 阅读器一律读数据库里的上次位置 —— 点「重新阅读」跟「继续阅读」
        // 一模一样，用户以为按钮坏了（审计里也是这么记的）。
        val restart = item!!.finished
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            MoyuPrimaryButton(
                text = when {
                    item!!.finished -> "重新阅读"
                    item!!.unread -> "开始阅读"
                    else -> "继续阅读"
                },
                icon = Icons.Filled.PlayArrow,
                onClick = { onRead(bookId, restart) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // —— 次要操作 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MoyuTextButton(
                text = if (book.inShelf) "移出书架" else "加入书架",
                icon = if (book.inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                onClick = { viewModel.toggleInShelf(book) },
            )
            MoyuTextButton(
                text = "书签",
                icon = Icons.Filled.Bookmarks,
                onClick = { onOpenBookmarks(bookId) },
            )
            MoyuTextButton(
                text = "删除",
                icon = Icons.Filled.Delete,
                onClick = { confirmDelete = true },
            )
        }

        if (confirmDelete) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "确认删除这本书？进度与笔记会一起删掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                MoyuTextButton(
                    text = "确认删除",
                    onClick = {
                        viewModel.delete(book)
                        confirmDelete = false
                        onBack()
                    },
                )
            }
        }

        // —— 目录 ——
        //
        // 番茄详情页在这里放的就是一条**可滑动的目录列表**（用户的原话：
        // 「他的目录不是这样的，是可滑动的列表来的」）。
        // 之前只给前 5 章 + 一个「查看全部」按钮，等于把目录藏在二级页里，
        // 想跳章还得先跳进阅读器再开目录。
        //
        // 这里给固定高度（而不是 heightIn）：LazyColumn 在无限高约束下
        // 无法测量，嵌在可滚动页面里必须有一个确定的高度。
        DetailSection(title = "目录", trailing = "共 ${tree.size} 章") {
            if (tree.isEmpty()) {
                Text(
                    text = "这本书还没有解析出章节",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(272.dp),
                ) {
                    itemsIndexed(tree) { index, node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChapter(bookId, index) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                modifier = Modifier.width(30.dp),
                            )
                            Text(
                                text = node.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // —— 简介 ——
        DetailSection(title = "简介") {
            Text(
                text = book.intro.ifBlank { "这本书没有简介。" },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )
        }

        Spacer(Modifier.height(24.dp + safeBottom))
    }

    // 详情页要能读到目录，但目录是按需加载的
    LaunchedEffect(bookId) { viewModel.loadToc(bookId) }
}

/** 详情页里的一个分区：小标题 + 右侧附注 + 内容。 */
@Composable
private fun DetailSection(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    val palette = moyuPalette()
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}
