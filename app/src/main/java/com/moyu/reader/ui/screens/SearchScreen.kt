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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.SearchHit
import com.moyu.reader.ui.SearchViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.MoyuChip
import com.moyu.reader.ui.components.MoyuTopBar
import com.moyu.reader.ui.theme.moyuPalette
import kotlinx.coroutines.delay

/**
 * 全文搜索页。
 *
 * 两个要点：
 *   1. **防抖 250ms**：边打字边全库扫描会让输入明显卡顿；
 *   2. **结果按章分组**：几百条结果平铺无法浏览，按章分组后可以快速定位。
 *
 * 关键词高亮用 excerpt 里的相对位置做精确区间替换，
 * 而不是简单地 split 关键词 —— 后者在关键词重复出现时会错位。
 */
@Composable
fun SearchScreen(
    factory: MoyuViewModelFactory,
    bookId: String?,
    onBack: () -> Unit,
    onJump: (bookId: String, chapterIndex: Int, chapterOffset: Int) -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(factory = factory)
    val query by viewModel.query.collectAsStateWithLifecycle()
    val hits by viewModel.hits.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    // 防抖：输入停止 250ms 后才真正搜索
    LaunchedEffect(query, options) {
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        viewModel.search(bookId)
    }

    val grouped = remember(hits) { hits.groupBy { it.bookId to it.chapterTitle } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        MoyuTopBar(
            title = if (bookId == null) "搜索全部书籍" else "本书搜索",
            onBack = onBack,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text("输入要查找的内容", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = palette.textSecondary)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = palette.textSecondary,
                            modifier = Modifier.clickable { viewModel.setQuery("") },
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 搜索选项
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoyuChip(
                text = "区分大小写",
                active = options.caseSensitive,
                onClick = { viewModel.setCaseSensitive(!options.caseSensitive) },
            )
            MoyuChip(
                text = "全词匹配",
                active = options.wholeWord,
                onClick = { viewModel.setWholeWord(!options.wholeWord) },
            )
            MoyuChip(
                text = if (options.maxPerChapter == null) "每章不限" else "每章 ${options.maxPerChapter} 条",
                active = options.maxPerChapter != null,
                onClick = {
                    viewModel.setMaxPerChapter(
                        when (options.maxPerChapter) {
                            null -> 20
                            20 -> 50
                            else -> null
                        }
                    )
                },
            )
        }

        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "搜索书库内容",
                description = "输入关键词即可在书籍正文中查找，结果会按章节分组显示。",
                modifier = Modifier.fillMaxSize(),
            )

            searching && hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("搜索中…", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            }

            hits.isEmpty() && summary.isNotEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "没有找到「$query」",
                description = "换一个关键词，或试试打开「全词匹配」。",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                grouped.forEach { (groupKey, groupHits) ->
                    val (groupBookId, chapterTitle) = groupKey
                    item(key = "head-$groupBookId-$chapterTitle") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.divider.copy(alpha = 0.28f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapterTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${groupHits.size} 处",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                            )
                        }
                    }

                    items(groupHits, key = { "${it.bookId}-${it.chapterIndex}-${it.chapterOffset}" }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            onClick = { onJump(hit.bookId, hit.chapterIndex, hit.chapterOffset) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    val palette = moyuPalette()

    // 精确高亮：按摘录内的相对位置切三段，而不是 split 关键词
    // （关键词在摘录里重复出现时，split 方案会高亮错位置）
    val annotated = remember(hit) {
        buildAnnotatedString {
            val start = hit.matchStart.coerceIn(0, hit.excerpt.length)
            val end = (hit.matchStart + hit.matchLength).coerceIn(start, hit.excerpt.length)
            append(hit.excerpt.substring(0, start))
            withStyle(SpanStyle(background = palette.primary.copy(alpha = 0.22f), fontWeight = FontWeight.Medium)) {
                append(hit.excerpt.substring(start, end))
            }
            append(hit.excerpt.substring(end))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "第 ${hit.chapterIndex + 1} 章",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(palette.divider.copy(alpha = 0.4f)),
    )
}
