package com.moyu.reader.ui

import com.moyu.reader.data.AppContainer
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.Bookmark
import com.moyu.reader.data.model.Highlight
import com.moyu.reader.data.prefs.FontFamilyId
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ShelfLayout
import com.moyu.reader.data.prefs.ShelfSort
import com.moyu.reader.data.prefs.ThemeId
import com.moyu.reader.reader.ReadingStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// 搜索
// ============================================================

/** 一条搜索结果。 */
data class SearchHit(
    val bookId: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterOffset: Int,
    val globalOffset: Int,
    val excerpt: String,
    /** 关键词在摘录中的位置，用于高亮渲染 */
    val matchStart: Int,
    val matchLength: Int,
)

/** 搜索可选开关。 */
data class SearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** 单章最多返回多少条；null 表示不限 */
    val maxPerChapter: Int? = null,
)

/**
 * 搜索 ViewModel。
 *
 * 搜索是纯计算密集任务，全部在 IO 线程做，避免阻塞 UI。
 * 这里用简单的子串匹配而不是引入全文索引库：本地小说通常几十本、
 * 每本几百章，逐章扫描的总耗时在百毫秒级，索引的收益抵不上其复杂度与内存占用。
 */
class SearchViewModel(container: AppContainer) : MoyuViewModel(container) {

    private val bookRepo = container.bookRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _options = MutableStateFlow(SearchOptions())
    val options: StateFlow<SearchOptions> = _options.asStateFlow()

    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary.asStateFlow()

    private val _truncated = MutableStateFlow(false)
    val truncated: StateFlow<Boolean> = _truncated.asStateFlow()

    val books: StateFlow<List<Book>> = bookRepo.books
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCaseSensitive(value: Boolean) {
        _options.value = _options.value.copy(caseSensitive = value)
        if (_query.value.isNotBlank()) search(null)
    }

    fun setWholeWord(value: Boolean) {
        _options.value = _options.value.copy(wholeWord = value)
        if (_query.value.isNotBlank()) search(null)
    }

    fun setMaxPerChapter(value: Int?) {
        _options.value = _options.value.copy(maxPerChapter = value)
        if (_query.value.isNotBlank()) search(null)
    }

    /**
     * 执行搜索。
     *
     * @param bookId 指定书内搜索；null 表示在全部书里搜索
     */
    fun search(bookId: String?) {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) {
            _hits.value = emptyList()
            _summary.value = ""
            return
        }

        scope.launch {
            _searching.value = true
            val opts = _options.value
            val results = mutableListOf<SearchHit>()
            var truncated = false

            val targetBooks = if (bookId != null) {
                listOfNotNull(bookRepo.getBook(bookId))
            } else {
                books.value
            }

            for (book in targetBooks) {
                val chapters = withContext(Dispatchers.IO) {
                    // 搜索需要正文，因此这里只能整本读。为控制内存，
                    // 逐章读取而不是一次性读全部章节。
                    (0 until book.chapterCount).mapNotNull { index ->
                        bookRepo.getChapter(book.id, index)
                    }
                }

                for (chapter in chapters) {
                    // 逐次定位关键词。注意 indexOf 的 fromIndex 语义：
                    // 返回的是**整串内**的绝对下标，因此不能拿它和 fromIndex 比较。
                    var searchFrom = 0
                    var countInChapter = 0
                    while (searchFrom <= chapter.content.length - keyword.length) {
                        val found = chapter.content.indexOf(
                            keyword,
                            startIndex = searchFrom,
                            ignoreCase = !opts.caseSensitive,
                        )
                        if (found < 0) break

                        // 整词匹配：仅在关键词首尾是 ASCII 词字符时才检查边界
                        if (opts.wholeWord && !isWholeWord(chapter.content, found, keyword.length)) {
                            searchFrom = found + 1
                            continue
                        }

                        results.add(
                            buildHit(book, chapter.index, chapter.title, chapter.content, found, keyword.length)
                        )
                        countInChapter++
                        searchFrom = found + keyword.length

                        if (results.size >= MAX_RESULTS) {
                            truncated = true
                            break
                        }
                        if (opts.maxPerChapter != null && countInChapter >= opts.maxPerChapter) break
                    }
                    if (truncated) break
                }
                if (truncated) break
            }

            _hits.value = results
            _truncated.value = truncated
            val chapterCount = results.map { "${it.bookId}:${it.chapterIndex}" }.distinct().size
            _summary.value = if (results.isEmpty()) {
                "没有找到「$keyword」"
            } else {
                buildString {
                    append("在 ").append(chapterCount).append(" 章中找到 ").append(results.size).append(" 处匹配")
                    if (truncated) append("（结果过多，仅显示前 $MAX_RESULTS 条）")
                }
            }
            _searching.value = false
        }
    }

    /** 摘录：关键词前后各取上下文并压平换行，保证列表单行美观。 */
    private fun buildHit(
        book: Book,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        matchStart: Int,
        matchLength: Int,
    ): SearchHit {
        val radius = 28
        var from = (matchStart - radius).coerceAtLeast(0)
        var to = (matchStart + matchLength + radius).coerceAtMost(content.length)

        // 向外扩展到标点或换行边界，避免从词中间截断
        while (from > 0 && !isBoundary(content[from - 1])) from--
        while (to < content.length && !isBoundary(content[to])) to++

        val raw = content.substring(from, to)
        val beforeRaw = content.substring(from, matchStart)
        val excerpt = raw.replace(Regex("\\s+"), " ").trim()

        // 压平空白后重新计算关键词位置（前导空白被 trim 掉了）
        val compressedRaw = raw.replace(Regex("\\s+"), " ")
        val leadingTrim = compressedRaw.length - compressedRaw.trimStart().length
        val compressedBefore = beforeRaw.replace(Regex("\\s+"), " ").length
        val matchOffset = (compressedBefore - leadingTrim).coerceAtLeast(0)

        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < content.length) "…" else ""

        return SearchHit(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            chapterOffset = matchStart,
            globalOffset = 0,
            excerpt = "$prefix$excerpt$suffix",
            matchStart = matchOffset + prefix.length,
            matchLength = matchLength,
        )
    }

    private fun isBoundary(ch: Char): Boolean =
        ch.isWhitespace() || ch in "，。！？；：、“”‘’（）《》〈〉【】,.!?;:()[]{}—…·"

    private fun isWholeWord(content: String, start: Int, length: Int): Boolean {
        val end = start + length
        val before = if (start > 0) content[start - 1] else ' '
        val after = if (end < content.length) content[end] else ' '
        val wordChar: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }
        return !wordChar(before) && !wordChar(after)
    }

    companion object {
        /** 结果上限：过多会拖慢渲染，且用户也不会翻到第 1000 条。 */
        const val MAX_RESULTS = 500
    }
}

// ============================================================
// 笔记
// ============================================================

/** 笔记页里合并展示的条目。 */
sealed interface NoteEntry {
    val bookId: String
    val chapterIndex: Int
    val chapterOffset: Int
    val createdAt: Long

    data class BookmarkEntry(val bookmark: Bookmark, override val bookId: String) : NoteEntry {
        override val chapterIndex get() = bookmark.chapterIndex
        override val chapterOffset get() = bookmark.chapterOffset
        override val createdAt get() = bookmark.createdAt
    }

    data class HighlightEntry(val highlight: Highlight, override val bookId: String) : NoteEntry {
        override val chapterIndex get() = highlight.chapterIndex
        override val chapterOffset get() = highlight.startOffset
        override val createdAt get() = highlight.createdAt
    }
}

/**
 * 笔记 ViewModel。
 *
 * 同时支撑两种使用场景：
 *   - 全局笔记页（所有书）；
 *   - 阅读器内的「本书笔记」面板（限定一本书）。
 * 用同一个 ViewModel 是为了让「按章分组」「删除确认」等交互逻辑只写一遍。
 */
class NotesViewModel(container: AppContainer) : MoyuViewModel(container) {

    private val noteRepo = container.noteRepository
    private val bookRepo = container.bookRepository

    private val _bookFilter = MutableStateFlow<String?>(null)
    val bookFilter: StateFlow<String?> = _bookFilter.asStateFlow()

    private val _kindFilter = MutableStateFlow(NoteKind.ALL)
    val kindFilter: StateFlow<NoteKind> = _kindFilter.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 合并后的条目，按创建时间倒序。 */
    val entries: StateFlow<List<NoteEntry>> = combineNotes()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun combineNotes(): kotlinx.coroutines.flow.Flow<List<NoteEntry>> =
        kotlinx.coroutines.flow.combine(_bookmarks, _highlights, _kindFilter) { marks, lights, kind ->
            buildList {
                if (kind != NoteKind.HIGHLIGHT_ONLY) {
                    marks.forEach { add(NoteEntry.BookmarkEntry(it, it.bookId)) }
                }
                if (kind != NoteKind.BOOKMARK_ONLY) {
                    lights.forEach { add(NoteEntry.HighlightEntry(it, it.bookId)) }
                }
            }.sortedByDescending { it.createdAt }
        }

    /** 当前范围内的书签/划线数量，供标签计数。 */
    val counts: StateFlow<Pair<Int, Int>> =
        kotlinx.coroutines.flow.combine(_bookmarks, _highlights) { marks, lights ->
            marks.size to lights.size
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0 to 0)

    val books: StateFlow<List<Book>> = bookRepo.books
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 载入笔记。bookId 为 null 表示全局。 */
    fun load(bookId: String?) {
        _bookFilter.value = bookId
        scope.launch {
            val marks = if (bookId == null) noteRepo.getAllBookmarks() else noteRepo.getBookmarks(bookId)
            val lights = if (bookId == null) noteRepo.getAllHighlights() else noteRepo.getHighlights(bookId)
            _bookmarks.value = marks
            _highlights.value = lights
        }
    }

    fun setKindFilter(kind: NoteKind) {
        _kindFilter.value = kind
    }

    fun updateBookmarkNote(id: String, note: String) {
        scope.launch {
            noteRepo.updateBookmarkNote(id, note)
            _bookmarks.value = _bookmarks.value.map { if (it.id == id) it.copy(note = note) else it }
            _message.value = "备注已保存"
        }
    }

    fun updateHighlightNote(id: String, note: String) {
        scope.launch {
            noteRepo.updateHighlightNote(id, note)
            _highlights.value = _highlights.value.map { if (it.id == id) it.copy(note = note) else it }
            _message.value = "备注已保存"
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch {
            noteRepo.removeBookmark(id)
            _bookmarks.value = _bookmarks.value.filterNot { it.id == id }
            _message.value = "已删除书签"
        }
    }

    fun deleteHighlight(id: String) {
        scope.launch {
            noteRepo.removeHighlight(id)
            _highlights.value = _highlights.value.filterNot { it.id == id }
            _message.value = "已删除划线"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 笔记页的分栏。 */
    enum class NoteKind { ALL, BOOKMARK_ONLY, HIGHLIGHT_ONLY }
}

// ============================================================
// 统计
// ============================================================

/**
 * 统计 ViewModel。
 *
 * 统计聚合（ReadingStatsCalculator）是纯函数，这里只负责取数、缓存与格式化。
 * 选定的日期详情也放在这里，避免 Composable 持有临时状态。
 */
class StatsViewModel(container: AppContainer) : MoyuViewModel(container) {

    private val statsRepo = container.statsRepository

    private val _stats = MutableStateFlow<ReadingStatsCalculator.ReadingStats?>(null)
    val stats: StateFlow<ReadingStatsCalculator.ReadingStats?> = _stats.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 用户点选的某一天（显示当日详情）。 */
    private val _selectedDate = MutableStateFlow<ReadingStatsCalculator.DailyStat?>(null)
    val selectedDate: StateFlow<ReadingStatsCalculator.DailyStat?> = _selectedDate.asStateFlow()

    fun load() {
        scope.launch {
            _loading.value = true
            _stats.value = statsRepo.computeStats()
            _loading.value = false
        }
    }

    fun selectDate(dateKey: String) {
        val daily = _stats.value?.daily ?: return
        _selectedDate.value = daily.firstOrNull { it.date == dateKey }
            ?: ReadingStatsCalculator.DailyStat(dateKey, 0, 0, 0, 0)
    }

    fun clearSelectedDate() {
        _selectedDate.value = null
    }

    fun formatSeconds(seconds: Int): String = ReadingStatsCalculator.formatSeconds(seconds)
}

// ============================================================
// 设置
// ============================================================

/**
 * 设置 ViewModel。
 *
 * 所有设置项的写入都经过 SettingsStore（DataStore），因此界面上的改动
 * 会立即通过 Flow 反映到阅读器等所有订阅方，不需要手动通知。
 */
class SettingsViewModel(container: AppContainer) : MoyuViewModel(container) {

    private val store = container.settingsStore
    private val dictionary = container.dictionaryProvider
    private val bookRepo = container.bookRepository

    val settings = store.settings
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), com.moyu.reader.data.prefs.ReaderSettings())

    private val _dictionaries = MutableStateFlow<List<Triple<String, String, Int>>>(emptyList())
    val dictionaries: StateFlow<List<Triple<String, String, Int>>> = _dictionaries.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val builtinDictionarySize: Int get() = dictionary.builtinSize

    fun loadDictionaries() {
        scope.launch {
            _dictionaries.value = dictionary.listDictionaries().map { (id, pair) ->
                Triple(id, pair.first, pair.second)
            }
        }
    }

    fun importDictionary(name: String, content: String) {
        scope.launch {
            val count = dictionary.importDictionary(name, content)
            if (count > 0) {
                _message.value = "已导入 $count 条词条"
                loadDictionaries()
            } else {
                _message.value = "没有解析出任何词条，请检查文件格式"
            }
        }
    }

    fun deleteDictionary(id: String) {
        scope.launch {
            dictionary.deleteDictionary(id)
            loadDictionaries()
            _message.value = "已删除词典"
        }
    }

    fun exportBackup(json: String, onReady: (String) -> Unit) {
        scope.launch {
            onReady(json)
            _message.value = "备份已导出"
        }
    }

    fun clearAllData() {
        scope.launch {
            // 清库用「逐本删除」而不是 drop table：外键 CASCADE 会一并清掉
            // 章节、书签、笔记、会话，避免留下孤儿数据。
            val books = bookRepo.getAllBooksOnce()
            books.forEach { bookRepo.deleteBook(it.id) }
            statsClear()
            store.resetToDefaults()
            _message.value = "已清除全部数据"
        }
    }

    private suspend fun statsClear() {
        container.statsRepository.clearAll()
    }

    fun consumeMessage() {
        _message.value = null
    }

    // —— 便捷 setter，让界面直接绑定 ——
    fun setTheme(v: ThemeId) = scope.launch { store.setTheme(v) }
    fun setFollowSystemDark(v: Boolean) = scope.launch { store.setFollowSystemDark(v) }
    fun setDynamicColor(v: Boolean) = scope.launch { store.setDynamicColor(v) }
    fun setEyeCare(v: Float) = scope.launch { store.setEyeCare(v) }
    // 亮度相关方法已移除：屏幕亮度交由系统设置管理，
    // 应用内再叠一层会与系统的自动亮度互相打架（见 ReaderSettings 的注释）。
    fun setFontFamily(v: FontFamilyId) = scope.launch { store.setFontFamily(v) }
    fun setFontSize(v: Int) = scope.launch { store.setFontSize(v) }
    fun setLineHeight(v: Float) = scope.launch { store.setLineHeight(v) }
    fun setParagraphSpacing(v: Float) = scope.launch { store.setParagraphSpacing(v) }
    fun setMargin(v: Int) = scope.launch { store.setMargin(v) }
    fun setIndent(v: Float) = scope.launch { store.setIndent(v) }
    fun setLetterSpacing(v: Float) = scope.launch { store.setLetterSpacing(v) }
    fun setJustify(v: Boolean) = scope.launch { store.setJustify(v) }
    fun setBold(v: Boolean) = scope.launch { store.setBold(v) }
    fun setPageMode(v: PageMode) = scope.launch { store.setPageMode(v) }
    fun setAutoReadSeconds(v: Int) = scope.launch { store.setAutoReadSeconds(v) }
    fun setKeepScreenOn(v: Boolean) = scope.launch { store.setKeepScreenOn(v) }
    fun setShowStatusBar(v: Boolean) = scope.launch { store.setShowStatusBar(v) }
    fun setShowPageNumber(v: Boolean) = scope.launch { store.setShowPageNumber(v) }
    fun setVolumeKeyPaging(v: Boolean) = scope.launch { store.setVolumeKeyPaging(v) }
    fun setTtsRate(v: Float) = scope.launch { store.setTtsRate(v) }
    fun setTtsPitch(v: Float) = scope.launch { store.setTtsPitch(v) }
    fun setShelfSort(v: ShelfSort) = scope.launch { store.setShelfSort(v) }
    fun setShelfLayout(v: ShelfLayout) = scope.launch { store.setShelfLayout(v) }
}
