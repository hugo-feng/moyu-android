package com.moyu.reader.ui

import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.model.BookGroup
import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.data.model.ReadingStatus
import com.moyu.reader.data.model.ShelfItem
import com.moyu.reader.data.prefs.ShelfLayout
import com.moyu.reader.data.prefs.ShelfSort
import com.moyu.reader.storage.SampleBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/** 书架筛选条件。 */
sealed interface ShelfFilter {
    data object All : ShelfFilter
    data object Reading : ShelfFilter
    data object Unread : ShelfFilter
    data object Finished : ShelfFilter
    data class Group(val groupId: String) : ShelfFilter
}

/**
 * 书架 ViewModel。
 *
 * 负责：书库列表与筛选排序、导入（含进度）、示例书加载、分组管理、单本操作。
 *
 * 一个设计决定：**导入进度是本地状态**而不是持久化状态。
 * 导入是一次性的页面级流程，把它写进数据库只会留下需要清理的临时数据。
 */
class ShelfViewModel(container: com.moyu.reader.data.AppContainer) : MoyuViewModel(container) {

    private val bookRepo = container.bookRepository
    private val groupRepo = container.groupRepository
    private val settingsStore = container.settingsStore

    private val _filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)
    val filter: StateFlow<ShelfFilter> = _filter.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importResults = MutableStateFlow<List<ImportResult>>(emptyList())
    val importResults: StateFlow<List<ImportResult>> = _importResults.asStateFlow()

    /** 一闪而过的提示条文案。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val groups: StateFlow<List<BookGroup>> = groupRepo.groups
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = settingsStore.settings
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), com.moyu.reader.data.prefs.ReaderSettings())

    /**
     * 当前筛选/排序下的书架条目。
     *
     * 筛选与排序在这里完成而不是在 Composable 里：
     * 排序涉及 localeCompare 与进度比较，放在 UI 层会导致每次重组都重算一遍。
     */
    val items: StateFlow<List<ShelfItem>> =
        combine(bookRepo.shelfItems, _filter, settingsStore.settings) { items, filter, prefs ->
            val filtered = applyFilter(items, filter)
            applySort(filtered, prefs.shelfSort)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 顶部统计：书籍数、已读完、本周阅读时长。 */
    val stats: StateFlow<ShelfStats> =
        combine(bookRepo.shelfItems, container.statsRepository.sessions) { list, sessions ->
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val weekSeconds = sessions
                .filter { it.startedAt >= weekAgo }
                .sumOf { it.durationSec }
            ShelfStats(
                total = list.size,
                finished = list.count { it.finished },
                weekSeconds = weekSeconds,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ShelfStats(0, 0, 0))

    /** 「继续阅读」：最近在读且未读完的那本。 */
    val continueReading: StateFlow<ShelfItem?> =
        bookRepo.shelfItems.combine(settingsStore.settings) { list, _ -> list }
            .let { flow ->
                flow.combine(kotlinx.coroutines.flow.flowOf(Unit)) { list, _ ->
                    list.firstOrNull { !it.finished && !it.unread } ?: list.firstOrNull { !it.finished }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // ============================================================
    // 筛选与排序
    // ============================================================

    fun setFilter(filter: ShelfFilter) {
        _filter.value = filter
    }

    fun setSort(sort: ShelfSort) {
        scope.launch { settingsStore.setShelfSort(sort) }
    }

    fun setLayout(layout: ShelfLayout) {
        scope.launch { settingsStore.setShelfLayout(layout) }
    }

    private fun applyFilter(items: List<ShelfItem>, filter: ShelfFilter): List<ShelfItem> = when (filter) {
        ShelfFilter.All -> items
        ShelfFilter.Reading -> items.filter { !it.finished && !it.unread }
        ShelfFilter.Unread -> items.filter { it.unread }
        ShelfFilter.Finished -> items.filter { it.finished }
        is ShelfFilter.Group -> items.filter { it.book.groupId == filter.groupId }
    }

    private fun applySort(items: List<ShelfItem>, sort: ShelfSort): List<ShelfItem> = when (sort) {
        ShelfSort.RECENT -> items.sortedByDescending { it.book.lastReadAt }
        ShelfSort.ADDED -> items.sortedByDescending { it.book.addedAt }
        // 中文按拼音排序：Collator 需要显式指定 CHINA 区域，
        // 直接用 compareTo 会按 UTF-16 码位排，对中文毫无意义。
        ShelfSort.TITLE -> items.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.book.title })
        ShelfSort.AUTHOR -> items.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.book.author })
        ShelfSort.PROGRESS -> items.sortedByDescending { it.percent }
    }

    // ============================================================
    // 导入
    // ============================================================

    fun import(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            _importing.value = true
            _importResults.value = emptyList()
            val results = mutableListOf<ImportResult>()
            for (uri in uris) {
                val result = bookRepo.importFromUri(uri)
                results.add(result)
                _importResults.value = results.toList()
            }
            _importing.value = false
            _message.value = summarizeImport(results)
        }
    }

    /** 扫描授权目录下的所有书文件。 */
    fun importFolder(treeUri: android.net.Uri) {
        scope.launch {
            _importing.value = true
            _importResults.value = emptyList()
            val uris = container.documentStore.listBooksUnder(treeUri)
            if (uris.isEmpty()) {
                _importing.value = false
                _message.value = "该目录下没有找到 TXT / EPUB / PDF 文件"
                return@launch
            }
            val results = mutableListOf<ImportResult>()
            for (uri in uris) {
                results.add(bookRepo.importFromUri(uri))
                _importResults.value = results.toList()
            }
            _importing.value = false
            _message.value = summarizeImport(results)
        }
    }

    private fun summarizeImport(results: List<ImportResult>): String {
        val ok = results.count { it is ImportResult.Success }
        val dup = results.count { it is ImportResult.Duplicate }
        val fail = results.count { it is ImportResult.Failure }
        return buildString {
            if (ok > 0) append("已导入 $ok 本")
            if (dup > 0) append(if (isNotEmpty()) "，" else "").append("跳过 $dup 本重复")
            if (fail > 0) append(if (isNotEmpty()) "，" else "").append("$fail 本失败")
            if (isEmpty()) append("没有可导入的文件")
        }
    }

    /** 加载内置示例书，让用户一进来就能体验完整流程。 */
    fun loadSampleBook() {
        scope.launch {
            _importing.value = true
            val result = SampleBook.importInto(bookRepo)
            _importing.value = false
            _message.value = when (result) {
                is ImportResult.Success -> "示例书《${result.title}》已加入书架"
                is ImportResult.Duplicate -> "示例书已在书架中"
                is ImportResult.Failure -> "示例书加载失败：${result.message}"
            }
        }
    }

    fun clearImportResults() {
        _importResults.value = emptyList()
    }

    // ============================================================
    // 单本操作
    // ============================================================

    fun setFinished(book: Book, finished: Boolean) {
        scope.launch {
            bookRepo.setStatus(book.id, if (finished) ReadingStatus.FINISHED else ReadingStatus.READING)
            _message.value = if (finished) "已标记为读完" else "已标记为在读"
        }
    }

    fun moveToGroup(book: Book, groupId: String?) {
        scope.launch {
            bookRepo.moveToGroup(book.id, groupId)
            _message.value = if (groupId == null) "已移出分组" else "已移动到分组"
        }
    }

    fun delete(book: Book) {
        scope.launch {
            bookRepo.deleteBook(book.id)
            _message.value = "已移出书架：${book.title}"
        }
    }

    fun updateMetadata(bookId: String, title: String, author: String, intro: String, clearCover: Boolean) {
        scope.launch {
            bookRepo.updateMetadata(
                bookId = bookId,
                title = title.trim().ifEmpty { null },
                author = author.trim().ifEmpty { null },
                intro = intro,
                coverPath = if (clearCover) "" else null,
            )
            _message.value = "已保存"
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        scope.launch {
            // 分组色从固定调色板里按现有分组数循环取用，
            // 保证新建分组的颜色稳定可预期，不会出现两个分组撞色
            val palette = GROUP_COLORS
            val color = palette[groups.value.size % palette.size]
            groupRepo.create(name.trim(), color)
            _message.value = "已创建分组「${name.trim()}」"
        }
    }

    fun deleteGroup(groupId: String) {
        scope.launch {
            groupRepo.delete(groupId)
            if (_filter.value == ShelfFilter.Group(groupId)) _filter.value = ShelfFilter.All
            _message.value = "已删除分组"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 按当前时间给出问候语（书架顶部）。 */
    fun greeting(): String = when (LocalTime.now().hour) {
        in 0..4 -> "凌晨好"
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }

    companion object {
        /** 分组可选颜色（与纸感主题同族）。 */
        val GROUP_COLORS = listOf(
            "#8A6A46", "#7A8B6F", "#8B7A9E", "#A8825C", "#6E8B9E", "#9E7A7A",
        )

        /** 书架顶栏可选的排序项。 */
        val SORT_OPTIONS: List<Pair<ShelfSort, String>> = listOf(
            ShelfSort.RECENT to "最近",
            ShelfSort.ADDED to "加入",
            ShelfSort.TITLE to "书名",
            ShelfSort.AUTHOR to "作者",
            ShelfSort.PROGRESS to "进度",
        )

        fun formatOfName(name: String): BookFormat? = when {
            name.endsWith(".txt", true) -> BookFormat.TXT
            name.endsWith(".epub", true) -> BookFormat.EPUB
            name.endsWith(".pdf", true) -> BookFormat.PDF
            else -> null
        }
    }
}

data class ShelfStats(
    val total: Int,
    val finished: Int,
    val weekSeconds: Int,
)
