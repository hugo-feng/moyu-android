package com.moyu.reader.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.ui.HistoryEntry
import com.moyu.reader.ui.HistoryViewModel
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.safeBottom
import com.moyu.reader.ui.safeTop
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 阅读历史页（底部标签的第二格）。
 *
 * ## 为什么它取代了「笔记」标签
 *
 * 原先这里是一个**全局**笔记列表 —— 把所有书的书签与划线混在一起。
 * 书一多，那个页面的实际用途就消失了：想找某本书的笔记，
 * 在一堆混杂条目里翻比直接打开那本书更慢。
 *
 * 笔记没有消失，只是回到了它该在的地方：阅读器里的「本书笔记」面板。
 * 而「我最近读了什么、读了多久」这件事，恰恰需要一个跨书的全局视角 ——
 * 这才是标签位该承载的内容。
 *
 * ## 数据从哪来
 *
 * 每次进入/离开阅读器都会结算一条阅读会话（见 StatsRepository）。
 * 这里把会话**按书聚合**：逐条列出会话会得到几十上百条「读了 3 分钟」，
 * 看不出任何东西；用户想知道的是「这本书我什么时候读的、一共读了多久」。
 */
@Composable
fun HistoryScreen(
    factory: com.moyu.reader.ui.MoyuViewModelFactory,
    onOpenBook: (String) -> Unit,
) {
    val viewModel: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    var confirmClear by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.surface),
        ) {
            // 书架页的标题区也在这个位置，两边保持一致，切标签时不会跳动
            Text(
                text = "阅读历史",
                style = MaterialTheme.typography.headlineLarge,
                color = palette.text,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(
                    start = 22.dp,
                    end = 22.dp,
                    top = safeTop + 18.dp,
                    bottom = 10.dp,
                ),
            )

            if (entries.isEmpty()) {
                EmptyHistory()
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 ${entries.size} 本 · 累计 ${
                            formatDuration(entries.sumOf { it.totalSeconds })
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    MoyuTextButton(
                        text = if (confirmClear) "确认清空" else "清空",
                        onClick = {
                            if (confirmClear) {
                                viewModel.clearHistory()
                                confirmClear = false
                            } else {
                                confirmClear = true
                            }
                        },
                    )
                }
                if (confirmClear) {
                    Text(
                        text = "清空只删除阅读时长记录，不影响书籍、进度与笔记。",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留出底栏高度 + 手势条，否则最后一项被压在底栏下面
                    contentPadding = PaddingValues(bottom = 80.dp + safeBottom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.bookId }) { entry ->
                        HistoryRow(entry = entry, onClick = { onOpenBook(entry.bookId) })
                    }
                }
            }
        }

        if (message != null) {
            ToastMessage(
                text = message!!,
                onDismiss = { viewModel.consumeMessage() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    val palette = moyuPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "还没有阅读记录",
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "开始读一本书之后，这里会记录每次阅读的时长与时间。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
    }
}

/** 一行阅读历史。 */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面用固定宽而不是 fillMaxWidth：这里的封面是「缩略图」，
        // 拉伸到列宽会挤掉右侧的文字信息。
        HistoryCover(coverPath = entry.coverPath, title = entry.bookTitle)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = entry.bookTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(BookRepository.formatRelativeTime(entry.lastReadAt))
                    append(" · 共 ")
                    append(formatDuration(entry.totalSeconds))
                    append(" · ")
                    append(entry.sessionCount)
                    append(" 次")
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 把时长格式化成「1 小时 20 分钟」这类可读文案。
 *
 * 只保留两级单位：用户在历史列表里关心的是量级，
 * 「1 小时 20 分 35 秒」这种精确到秒对判断「我读了多久」没有帮助，
 * 反而让一行文字太长被截断。
 */
internal fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0 分钟"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分钟"
        hours > 0 -> "$hours 小时"
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}

/**
 * 历史行的小封面（42dp 宽）。
 *
 * 不复用书架那个 `BookCover`：它要一个完整的 `Book` 对象，
 * 而历史列表只查了会话与书名 —— 为了画一张缩略图去把整本书查出来
 * （含简介、字数、章节数等用不到的字段）不划算。
 *
 * 样式与书架封面**刻意保持一致**（同样的渐变、同样的圆角方向），
 * 这样同一个应用里的封面不会出现两种长相。
 */
@Composable
private fun HistoryCover(coverPath: String?, title: String) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp)
    val file = coverPath?.let { java.io.File(it) }

    Box(
        modifier = Modifier
            .width(42.dp)
            .fillMaxWidth()
            .aspectRatio(3f / 4.3f)
            .clip(shape)
            .background(coverGradient(title)),
    ) {
        if (file != null && file.exists()) {
            coil.compose.AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
