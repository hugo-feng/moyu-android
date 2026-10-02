package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.NoteEntry
import com.moyu.reader.ui.NotesViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.MoyuTopBar
import com.moyu.reader.ui.components.SegmentedControl
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 笔记页。
 *
 * 同时服务两种场景：
 *   - 全局模式（bookId == null）：所有书的书签与划线，按书分组；
 *   - 书内模式（bookId != null）：只显示这本书。
 *
 * 删除一律二次确认：笔记是用户亲手留下的内容，误删的代价远高于多点一次。
 */
@Composable
fun NotesScreen(
    factory: MoyuViewModelFactory,
    bookId: String?,
    onBack: () -> Unit,
    onJump: (bookId: String, chapterIndex: Int, chapterOffset: Int) -> Unit,
) {
    val viewModel: NotesViewModel = viewModel(factory = factory)
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val kind by viewModel.kindFilter.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    LaunchedEffect(bookId) { viewModel.load(bookId) }

    val bookTitles = remember(books) { books.associate { it.id to it.title } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        MoyuTopBar(
            title = if (bookId == null) "笔记" else "本书笔记",
            subtitle = "${counts.first} 书签 · ${counts.second} 划线",
            onBack = onBack,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            SegmentedControl(
                options = listOf(
                    NotesViewModel.NoteKind.ALL to "全部",
                    NotesViewModel.NoteKind.BOOKMARK_ONLY to "书签",
                    NotesViewModel.NoteKind.HIGHLIGHT_ONLY to "划线",
                ),
                selected = kind,
                onSelect = { viewModel.setKindFilter(it) },
            )
        }

        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.EditNote,
                title = "还没有书签和笔记",
                description = "阅读时长按选中文字即可划线、写想法或添加书签。",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            // 全局模式按书分组：否则几百条笔记混在一起无法浏览
            val grouped = if (bookId == null) {
                entries.groupBy { it.bookId }
            } else {
                mapOf((bookId) to entries)
            }

            grouped.forEach { (id, list) ->
                if (bookId == null) {
                    item(key = "header-$id") {
                        Text(
                            text = bookTitles[id] ?: "已删除的书",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                        )
                    }
                }

                items(list, key = { entryKey(it) }) { entry ->
                    when (entry) {
                        is NoteEntry.BookmarkEntry -> BookmarkRow(
                            entry = entry,
                            onJump = { onJump(entry.bookId, entry.chapterIndex, entry.chapterOffset) },
                            onSaveNote = { viewModel.updateBookmarkNote(entry.bookmark.id, it) },
                            onDelete = { viewModel.deleteBookmark(entry.bookmark.id) },
                        )

                        is NoteEntry.HighlightEntry -> HighlightRow(
                            entry = entry,
                            onJump = { onJump(entry.bookId, entry.chapterIndex, entry.chapterOffset) },
                            onSaveNote = { viewModel.updateHighlightNote(entry.highlight.id, it) },
                            onDelete = { viewModel.deleteHighlight(entry.highlight.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun entryKey(entry: NoteEntry): String = when (entry) {
    is NoteEntry.BookmarkEntry -> entry.bookmark.id
    is NoteEntry.HighlightEntry -> entry.highlight.id
}

@Composable
private fun BookmarkRow(
    entry: NoteEntry.BookmarkEntry,
    onJump: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
) {
    NoteCard(
        chapterLabel = "第 ${entry.chapterIndex + 1} 章",
        text = entry.bookmark.excerpt.ifEmpty { "（书签）" },
        note = entry.bookmark.note,
        time = BookRepository.formatRelativeTime(entry.createdAt),
        onJump = onJump,
        onSaveNote = onSaveNote,
        onDelete = onDelete,
    )
}

@Composable
private fun HighlightRow(
    entry: NoteEntry.HighlightEntry,
    onJump: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
) {
    NoteCard(
        chapterLabel = "第 ${entry.chapterIndex + 1} 章",
        text = entry.highlight.text,
        note = entry.highlight.note,
        time = BookRepository.formatRelativeTime(entry.createdAt),
        colorHex = entry.highlight.color,
        onJump = onJump,
        onSaveNote = onSaveNote,
        onDelete = onDelete,
    )
}

@Composable
private fun NoteCard(
    chapterLabel: String,
    text: String,
    note: String,
    time: String,
    onJump: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDelete: () -> Unit,
    colorHex: String? = null,
) {
    val palette = moyuPalette()
    var editing by remember { mutableStateOf(false) }
    var draft by remember(note) { mutableStateOf(note) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (colorHex != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(palette.primary)),
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
                text = time,
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
                .padding(top = 7.dp)
                .clickable(onClick = onJump),
        )

        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("写点想法", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        } else if (note.isNotEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
            MoyuTextButton(text = "跳转", onClick = onJump)
            Spacer(Modifier.width(4.dp))
            MoyuTextButton(
                text = if (editing) "保存" else "备注",
                onClick = {
                    if (editing) {
                        onSaveNote(draft)
                        editing = false
                    } else {
                        editing = true
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            MoyuTextButton(
                text = if (confirmingDelete) "确认删除" else "删除",
                onClick = { if (confirmingDelete) onDelete() else confirmingDelete = true },
            )
        }
    }
}
