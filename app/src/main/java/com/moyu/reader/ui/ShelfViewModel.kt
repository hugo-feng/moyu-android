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
     * 当前页面是「书库」还是「书架」。
     *
     * 同一个 ViewModel 支撑两个页面：两者的列表形态、筛选、排序、
     * 分组、单本操作完全一样，只有**数据源**与「加入/移出书架」这一个动作不同。
     * 拆成两个 ViewModel 会把上面两百行逻辑抄一遍，之后必然漂移
     * （一边改了排序另一边没改）。
     *
     * 由页面在 LaunchedEffect 里设置一次。默认 false = 书库 ——
     * 那是用户进入应用后看到的第一个页面。
     */
    private val _libraryMode = MutableStateFlow(false)
    val libraryMode: StateFlow<Boolean> = _libraryMode.asStateFlow()

    fun setLibraryMode(library: Boolean) {
        if (_libraryMode.value == library) return
        _libraryMode.value = library
        // 切页面时回到「全部」，否则从书架的「已读完」切到书库会莫名其妙地空着
        _filter.value = ShelfFilter.All
    }

    /** 当前模式对应的数据源。 */
    private val sourceItems =
        combine(bookRepo.libraryItems, bookRepo.shelfItems, _libraryMode) { library, shelf, isLibrary ->
            if (isLibrary) library else shelf
        }

    /**
     * 当前筛选/排序下的条目。
     *
     * 筛选与排序在这里完成而不是在 Composable 里：
     * 排序涉及 Collator 与进度比较，放在 UI 层会导致每次重组都重算一遍。
     */
    val items: StateFlow<List<ShelfItem>> =
        combine(sourceItems, _filter, settingsStore.settings) { items, filter, prefs ->
            applySort(applyFilter(items, filter), prefs.shelfSort)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 顶部统计：书籍数、已读完、本周阅读时长。跨模式共用同一份统计。 */
    val stats: StateFlow<ShelfStats> =
        combine(bookRepo.libraryItems, container.statsRepository.sessions) { list, sessions ->
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

    /** 书架里的书数量（底部标签用它显示角标，也用于空状态文案）。 */
    val shelfCount: StateFlow<Int> =
        bookRepo.shelfCount.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 书库里的全部书（含未加入书架的）。详情页用它兜底查找。 */
    val libraryItems: StateFlow<List<ShelfItem>> =
        bookRepo.libraryItems.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 详情页要显示的章节目录（只有标题，不含正文）。
     *
     * 按需加载而不是让 shelfItems 带上：目录只有「打开某本书的详情页」
     * 才需要，而 shelfItems 是列表页每本书都要用的。
     * 混在一起会让列表页白白查一遍章节表。
     */
    private val _tocTree = MutableStateFlow<List<com.moyu.reader.data.model.ChapterHeader>>(emptyList())
    val tocTree: StateFlow<List<com.moyu.reader.data.model.ChapterHeader>> = _tocTree.asStateFlow()

    private var tocLoadedFor: String? = null

    suspend fun loadToc(bookId: String) {
        // 同一本书不重复加载：LaunchedEffect 在重组时可能再次触发
        if (tocLoadedFor == bookId) return
        tocLoadedFor = bookId
        /**
         * 先把「旧规则切出来的章节」重切一遍，再读目录。
         *
         * 这一步原先只在阅读器里做（`ReaderViewModel.open`），于是详情页
         * 显示的还是旧的十几个「第 N 节」—— 用户升级后打开详情页看到目录没变，
         * 自然认为修复没生效。同一个检查放在这里，两处入口才能一致。
         */
        runCatching { bookRepo.resplitIfOutdated(bookId) }
        _tocTree.value = runCatching { bookRepo.getChapterHeaders(bookId) }.getOrDefault(emptyList())
    }

    /** 「继续阅读」：最近在读且未读完的那本。 */
    val continueReading: StateFlow<ShelfItem?> =
        bookRepo.libraryItems
            .combine(kotlinx.coroutines.flow.flowOf(Unit)) { list, _ ->
                list.firstOrNull { !it.finished && !it.unread } ?: list.firstOrNull { !it.finished }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 加入书架 / 移出书架。
     *
     * 书库里对每本书显示「加入书架」，已加入的显示「已在书架」并可移出；
     * 书架里则显示「移出书架」。文案由界面按 [ShelfItem.book] 的 inShelf 决定。
     */
    fun toggleInShelf(book: Book) {
        scope.launch {
            val next = !book.inShelf
            bookRepo.setInShelf(book.id, next)
            _message.value = if (next) "已加入书架：${book.title}" else "已移出书架：${book.title}"
        }
    }

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
            // 「删除」是从书库彻底移除（连文件记录一起），
            // 与「移出书架」是两件事 —— 后者只把书放回书库。
            _message.value = "已删除：${book.title}"
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
            if (_openGroupId.value == groupId) _openGroupId.value = null
            _message.value = "已删除分组"
        }
    }

    /**
     * 重命名分组。
     *
     * 只改名字，不动颜色、不动归属 —— 用户长按分组卡片选「重命名」
     * 想要的只是换个名字，不该顺带改变任何别的东西。
     */
    fun renameGroup(groupId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val existing = groups.value.firstOrNull { it.id == groupId } ?: return
        if (existing.name == trimmed) return
        scope.launch {
            groupRepo.rename(groupId, trimmed)
            _message.value = "已重命名为「$trimmed」"
        }
    }

    // ============================================================
    // 分组卡片（向番茄小说学习的形态）
    // ============================================================

    /**
     * 当前「钻入」的分组；null 表示停在分组总览。
     *
     * 番茄小说的书架是**两级**的：先看到一组分组卡片（每张卡片里
     * 露出几本书的封面缩略 + 分组名 + 本数），点进去才看到这个分组的书。
     * 这样书架首页不会铺满几百本书的封面，而是先给出结构。
     */
    private val _openGroupId = MutableStateFlow<String?>(null)
    val openGroupId: StateFlow<String?> = _openGroupId.asStateFlow()

    fun openGroup(groupId: String) {
        _openGroupId.value = groupId
        _filter.value = if (groupId == ALL_GROUP_ID) ShelfFilter.All else ShelfFilter.Group(groupId)
    }

    fun closeGroup() {
        _openGroupId.value = null
        _filter.value = ShelfFilter.All
    }

    /**
     * 分组卡片列表。
     *
     * 第一张固定是「全部」，它让用户随时能看到所有书 ——
     * 番茄小说里对应的是「全部」入口。之后是用户自建的分组。
     *
     * 预览封面取每个分组的前 4 本。**不在这里做分页或懒加载**：
     * 分组数量在个位数量级，每张卡片只需要 4 个 Book 对象，
     * 一次性算完比在 Compose 里为每张卡片各自订阅一次数据流更省。
     */
    val groupCards: StateFlow<List<ShelfGroup>> =
        combine(sourceItems, groups, _libraryMode) { items, groupList, isLibrary ->
            // 只有书架做分组。书库是「全部导入的书」的平铺列表，
            // 它承担的是查找而不是整理，套一层分组反而多一次点击。
            if (isLibrary) return@combine emptyList()

            val all = ShelfGroup(
                id = ALL_GROUP_ID,
                name = "全部",
                color = null,
                count = items.size,
                previewBooks = items.take(4).map { it.book },
            )
            val grouped = groupList.map { g ->
                val inGroup = items.filter { it.book.groupId == g.id }
                ShelfGroup(
                    id = g.id,
                    name = g.name,
                    color = g.color,
                    count = inGroup.size,
                    previewBooks = inGroup.take(4).map { it.book },
                )
            }
            listOf(all) + grouped
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前分组里的书（用于钻入后的列表）。
     *
     * 与 [items] 的区别：这里**不做排序以外的事**，
     * 只是把已按筛选过滤过的 items 原样给出，让界面少一次判断。
     */
    val openedGroupName: StateFlow<String?> =
        combine(_openGroupId, groups) { id, list ->
            when {
                id == null -> null
                id == ALL_GROUP_ID -> "全部"
                else -> list.firstOrNull { it.id == id }?.name
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** 把书移动到某个分组；[groupId] 为 null 表示移出分组。 */
    fun assignToGroup(book: Book, groupId: String?) {
        scope.launch {
            bookRepo.moveToGroup(book.id, groupId)
            val name = groupId?.let { id -> groups.value.firstOrNull { it.id == id }?.name }
            _message.value = if (groupId == null) {
                "已移出分组：${book.title}"
            } else {
                "已把《${book.title}》移到「$name」"
            }
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
        /** 「全部」分组卡的固定 id。用不可能与真实分组 uuid 冲突的字面量。 */
        const val ALL_GROUP_ID = "__all__"

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

/**
 * 一张分组卡片。
 *
 * [previewBooks] 是卡片里那几本缩略封面，最多 4 本 ——
 * 只取前几本而不是全部：卡片是「看一眼就知道这个分组里大概有什么」，
 * 把几十本封面都塞进去既看不清也没必要。
 */
data class ShelfGroup(
    val id: String,
    val name: String,
    /** 分组色（十六进制字符串）；「全部」没有颜色，用主题色。 */
    val color: String?,
    val count: Int,
    val previewBooks: List<Book>,
)
