package com.moyu.reader.ui

import android.graphics.Typeface
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.data.AppContainer
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.Bookmark
import com.moyu.reader.data.model.Chapter
import com.moyu.reader.data.model.ChapterHeader
import com.moyu.reader.data.model.Highlight
import com.moyu.reader.data.model.ReadingPosition
import com.moyu.reader.data.model.ReadingStatus
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ReaderSettings
import com.moyu.reader.reader.DictionaryProvider
import com.moyu.reader.reader.PaginationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读器 ViewModel。
 *
 * 这是整个应用最复杂的状态机，集中处理四件事：
 *   1. **分页**：用 StaticLayout 精确排版（见 PaginationEngine），
 *      并在字号/行距/视口尺寸变化时重新分页；
 *   2. **位置**：维护「章号 + 章内偏移 + 页码」三者同步，
 *      翻页/跳章/拖进度条都要更新它们，并在节流后持久化；
 *   3. **笔记**：当前章的划线，供正文高亮渲染；
 *   4. **朗读与自动阅读**：两者都按「读完一页自动前进」推进。
 *
 * 为什么分页放在 ViewModel 而不是 Composable：
 * 分页是 CPU 密集且依赖排版参数的计算，放 Composable 里会在每次重组时重算，
 * 造成明显掉帧。放在 ViewModel 里可以精确控制「何时重算」。
 */
class ReaderViewModel(container: AppContainer) : MoyuViewModel(container) {

    private val bookRepo = container.bookRepository
    private val noteRepo = container.noteRepository
    private val statsRepo = container.statsRepository
    private val store = container.settingsStore
    val tts = container.ttsController
    private val dictionary = container.dictionaryProvider

    // —— 书籍与章节 ——
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _chapterHeaders = MutableStateFlow<List<ChapterHeader>>(emptyList())
    val chapterHeaders: StateFlow<List<ChapterHeader>> = _chapterHeaders.asStateFlow()

    private val _currentChapter = MutableStateFlow<Chapter?>(null)
    val currentChapter: StateFlow<Chapter?> = _currentChapter.asStateFlow()

    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    private val _chapterLoading = MutableStateFlow(false)
    val chapterLoading: StateFlow<Boolean> = _chapterLoading.asStateFlow()

    // —— 分页 ——
    private val _pages = MutableStateFlow<List<PaginationEngine.Page>>(emptyList())
    val pages: StateFlow<List<PaginationEngine.Page>> = _pages.asStateFlow()

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    /** 翻页动画方向：true 向前，false 向后。 */
    private val _forward = MutableStateFlow(true)
    val forward: StateFlow<Boolean> = _forward.asStateFlow()

    /** 每次翻页自增，Composable 用它作为 key 触发动画。 */
    private val _flipToken = MutableStateFlow(0)
    val flipToken: StateFlow<Int> = _flipToken.asStateFlow()

    /**
     * 翻页前的那一页，供动画使用。
     *
     * ## 为什么需要单独存一份
     *
     * 真实的翻页动画必须**两页同时在场地**：仿真要露出下面那页，
     * 平移要两页一起推，覆盖要旧页不动、新页盖上去。
     * 但 ViewModel 只保存「当前页」，翻页瞬间旧页的文本与页码就没了 ——
     * 这正是三种模式看起来一模一样的根因：
     * 旧页不存在，动画只能让新页自己淡入/位移，参数再怎么调都不像翻页。
     *
     * 存的是**渲染一页所需的全部信息**（文本切片、页码、是否章首页、章标题），
     * 而不是只存一个页码 —— 因为跨章翻页时新旧两页属于不同章节，
     * 只存页码会在新章节的分页表里查到错误的内容。
     */
    data class PageSnapshot(
        val text: String,
        val pageNumber: Int,
        val showTitle: Boolean,
        val chapterTitle: String,
        val chapterNumberLabel: String,
        val pageStart: Int,
    )

    private val _outgoingPage = MutableStateFlow<PageSnapshot?>(null)
    val outgoingPage: StateFlow<PageSnapshot?> = _outgoingPage.asStateFlow()

    /** 把当前页快照进 [outgoingPage]，翻页前调用。 */
    private fun captureOutgoing() {
        val chapter = _currentChapter.value ?: return
        val page = _pages.value.getOrNull(_pageIndex.value) ?: return
        _outgoingPage.value = PageSnapshot(
            text = chapter.content.substring(
                page.start.coerceIn(0, chapter.content.length),
                page.end.coerceIn(0, chapter.content.length),
            ),
            pageNumber = _pageIndex.value + 1,
            showTitle = page.start == 0,
            chapterTitle = chapter.title,
            chapterNumberLabel = com.moyu.reader.reader.ChapterLabels
                .labelFor(chapter.title, _chapterIndex.value),
            pageStart = page.start,
        )
    }

    // —— 笔记 ——
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()

    /** 当前章内的划线区间（章内偏移），供正文渲染高亮。 */
    private val _chapterHighlights = MutableStateFlow<List<IntRange>>(emptyList())
    val chapterHighlights: StateFlow<List<IntRange>> = _chapterHighlights.asStateFlow()

    // —— 界面状态 ——
    private val _settings = MutableStateFlow(ReaderSettings())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val _dictResult = MutableStateFlow<DictionaryProvider.LookupResult?>(null)
    val dictResult: StateFlow<DictionaryProvider.LookupResult?> = _dictResult.asStateFlow()

    private val _dictLoading = MutableStateFlow(false)
    val dictLoading: StateFlow<Boolean> = _dictLoading.asStateFlow()

    private val _autoReading = MutableStateFlow(false)
    val autoReading: StateFlow<Boolean> = _autoReading.asStateFlow()

    val ttsState = tts.state

    /** 阅读位置持久化的节流任务。 */
    private var persistJob: Job? = null
    private var autoReadJob: Job? = null

    /** 本次阅读会话的起点，用于统计。 */
    private var sessionStartAt = 0L
    private var sessionStartOffset = 0

    /** 分页时的排版参数快照，用于判断是否需要重新分页。 */
    private var lastPageKey: String = ""

    init {
        // 持续订阅设置：排版参数变化时由 recomputePaginationIfNeeded 决定是否重新分页
        scope.launch {
            store.settings.collect { _settings.value = it }
        }
    }

    /**
     * 打开一本书。
     *
     * @param density 用于把 sp/dp 换算成 px —— 分页必须用真实像素宽度，
     *   否则在密度不同的设备上算出的每行字数会不同，分页必然错位。
     */
    fun open(bookId: String, density: Density) {
        if (_book.value?.id == bookId) return
        scope.launch {
            _chapterLoading.value = true
            val book = bookRepo.getBook(bookId)
            if (book == null) {
                _message.value = "这本书已不在书架中"
                _chapterLoading.value = false
                return@launch
            }
            _book.value = book

            val headers = bookRepo.getChapterHeaders(bookId)
            _chapterHeaders.value = headers

            val position = bookRepo.getPosition(bookId)
            val startChapter = position?.chapterIndex?.coerceIn(0, (headers.size - 1).coerceAtLeast(0)) ?: 0
            _chapterIndex.value = startChapter

            loadChapter(startChapter, position?.chapterOffset ?: 0, density)
            reloadNotes(bookId)
            startSession(position?.globalOffset ?: 0)
            _chapterLoading.value = false
        }
    }

    /** 重新读取当前书的书签与划线。 */
    private suspend fun reloadNotes(bookId: String) {
        _bookmarks.value = noteRepo.getBookmarks(bookId)
        _highlights.value = noteRepo.getHighlights(bookId)
        refreshChapterHighlights()
    }

    private fun refreshChapterHighlights() {
        val index = _chapterIndex.value
        _chapterHighlights.value = _highlights.value
            .filter { it.chapterIndex == index }
            .map { it.startOffset until it.endOffset }
    }

    /**
     * 加载指定章并分页。
     *
     * @param targetOffset 分页完成后要定位到的章内偏移
     */
    private fun loadChapter(index: Int, targetOffset: Int, density: Density) {
        scope.launch {
            val bookId = _book.value?.id ?: return@launch
            val chapter = withContext(Dispatchers.IO) { bookRepo.getChapter(bookId, index) }
            if (chapter == null) {
                _message.value = "章节读取失败"
                return@launch
            }
            _currentChapter.value = chapter
            _chapterIndex.value = index
            recomputePagination(density, force = true)

            // 分页完成后按偏移定位页码
            val page = PaginationEngine.pageIndexForOffset(_pages.value, targetOffset)
            _pageIndex.value = page
            refreshChapterHighlights()
        }
    }

    /**
     * 重新分页。
     *
     * 用 lastPageKey 做缓存键：字号、行距、边距、字体、视口尺寸任一变化都会让键变化。
     * 这样滚动/翻页引起的重组不会触发无谓的重新排版（这是流畅度的关键）。
     */
    fun recomputePagination(density: Density, force: Boolean = false) {
        val chapter = _currentChapter.value ?: return
        val prefs = _settings.value
        val viewport = _viewport ?: return

        val key = buildString {
            append(prefs.fontSizeSp).append('|')
            append(prefs.lineHeightMultiplier).append('|')
            append(prefs.marginDp).append('|')
            append(prefs.fontFamily).append('|')
            append(prefs.bold).append('|')
            append(prefs.indentEm).append('|')
            append(prefs.letterSpacingEm).append('|')
            append(viewport.first).append('x').append(viewport.second).append('|')
            // 正文区实测的宽与高、以及章首页标题高度，都必须进缓存键：
            // 它们决定每页放几行、每行放几字，变了却不重排就会少字或被裁。
            append(contentBoxWidth).append('|').append(contentBoxHeight).append('|')
            append(firstPageHeaderHeight)
        }
        if (!force && key == lastPageKey) return
        lastPageKey = key

        val textSizePx = with(density) { prefs.fontSizeSp.sp.toPx() }
        val marginPx = with(density) { prefs.marginDp.dp.toPx() }
        val lineHeightPx = textSizePx * prefs.lineHeightMultiplier

        /**
         * 纵向可用高度**来自渲染侧的实测值**，而不是在这里推算。
         *
         * 早先这里写的是 `viewport.second - marginPx*0.9 - insetsTop - insetsBottom`：
         * 它假定「版心 = 整屏 − 页边距 − 系统栏」，但渲染时 Column 里还压着
         * 天头书眉与地脚页码，章首页还多一整块标题。于是分页按 28 行排、
         * 版心只装得下 26 行 —— **每页悄悄少 2~3 行字**（约 40~60 字），
         * 而且用户不会察觉。这类错误靠推算永远对不齐，只能实测。
         */
        val measuredHeight = contentBoxHeight
            ?: (viewport.second - marginPx * 0.9f - _insets.first - _insets.second).toInt()

        /**
         * 横向可用宽度同样必须用实测值。
         *
         * 这里曾经写的是 `viewport.first - marginPx * 2` —— 而 `viewport`
         * 是**根布局**的尺寸，页边距已经在根布局上以 padding 的形式扣掉了。
         * 再扣一次等于把页边距算了双份：小米 14 上（1080px 宽、margin 48dp、
         * density 3）实际版心约 948px，却被当成 748px —— 窄了 21%，
         * 每行少放 3~4 个字，而且断行位置与真实排版不一致。
         *
         * 教训与高度那处完全一样：**凡是能从渲染侧实测的，就不要推算**。
         */
        val measuredWidth = contentBoxWidth
            ?: (viewport.first - marginPx * 2).toInt()

        val metrics = PaginationEngine.Metrics(
            contentWidth = measuredWidth.coerceAtLeast(1),
            contentHeight = measuredHeight.coerceAtLeast(1),
            lineHeight = lineHeightPx.toInt().coerceAtLeast(1),
            firstPageHeaderHeight = firstPageHeaderHeight,
        )

        val paint = PaginationEngine.buildTextPaint(
            textSizePx = textSizePx,
            typeface = if (prefs.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            letterSpacingEm = prefs.letterSpacingEm,
        )

        val pages = PaginationEngine.paginate(chapter.content, metrics, paint)
        _pages.value = pages
        // 页码可能越界（字号变大后页数变多）
        _pageIndex.value = _pageIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    // —— 视口尺寸由 Composable 上报 ——
    private var _viewport: Pair<Int, Int>? = null

    /**
     * 系统栏占用的纵向空间（top, bottom），单位像素。
     *
     * 为什么分页必须知道它：`enableEdgeToEdge()` 让内容画到状态栏与手势条底下，
     * 正文因此需要额外让开这两块区域。若分页不扣除，排出来的页会比可视区高一截，
     * 末行就被推到屏幕外 —— 用户看到的就是「字铺满后底部被遮挡」。
     * 小米 14 的澎湃 OS 3（Android 16）强制边到边，这条路径必然走到。
     */
    private var _insets: Pair<Int, Int> = 0 to 0

    /**
     * 正文区的**实测**高度（像素，已扣掉页边距、系统栏与地脚页码）。
     * 由 PagedReader 的正文容器通过 onSizeChanged 上报。
     */
    private var contentBoxHeight: Int? = null

    /**
     * 正文区的**实测**宽度（像素）。
     *
     * 与高度同理：宽度也不能用「视口宽 − 页边距 × 2」推算 ——
     * 视口本身已经被根布局扣过页边距，再扣一次会让版心窄约 21%，
     * 每行少放 3~4 个字，且断行位置与真实渲染不一致。
     */
    private var contentBoxWidth: Int? = null

    /** 章首页标题块（章节序号 + 大标题）的实测高度，只有第一页要扣。 */
    private var firstPageHeaderHeight: Int = 0

    fun setViewport(widthPx: Int, heightPx: Int, density: Density) {
        val previous = _viewport
        _viewport = widthPx to heightPx
        // 尺寸变化才重算：旋转屏幕、进入分屏都会走这里
        if (previous == null || previous.first != widthPx || previous.second != heightPx) {
            recomputePagination(density)
        }
    }

    /**
     * 上报正文容器的实测尺寸。
     *
     * 这是分页纵向可用高度的**唯一来源**：容器已经把页边距、安全区与地脚页码
     * 全部扣掉了（它们都是布局约束），因此这个数字天然与渲染一致，
     * 不会再出现「分页按 28 行排、版心只装得下 26 行」这类少字问题。
     */
    fun setContentBoxSize(widthPx: Int, heightPx: Int, density: Density) {
        if (heightPx <= 0 || widthPx <= 0) return
        if (contentBoxHeight == heightPx && contentBoxWidth == widthPx) return
        contentBoxHeight = heightPx
        contentBoxWidth = widthPx
        recomputePagination(density)
    }

    /** 上报章首页标题块高度。仅在章首页渲染时量得到，切页后归零。 */
    fun setFirstPageHeaderHeight(heightPx: Int, density: Density) {
        val next = heightPx.coerceAtLeast(0)
        if (next == firstPageHeaderHeight) return
        firstPageHeaderHeight = next
        recomputePagination(density)
    }

    /**
     * 版心的实测高度（像素），供滚动模式的自动阅读取速。
     *
     * 滚动模式没有「页」，但「秒/页」这个设置对速度的预期与分页模式是同一个：
     * 每秒应当流过与翻一页相同的文字量。因此它必须用**同一个**版心高度，
     * 而不是自己按可视区高度另算一份 —— 那样两个模式设成同样的秒数会速度不同。
     *
     * 尚未测量到时返回 0，调用方自行回退。
     */
    fun lastContentBoxHeight(): Int = contentBoxHeight ?: 0

    /**
     * 上报系统栏高度。
     *
     * 值没变就直接返回：Composable 每次重组都会上报，
     * 不做这个短路就会每帧重新排版，翻页明显卡顿。
     */
    fun setInsets(topPx: Int, bottomPx: Int, density: Density) {
        val next = topPx.coerceAtLeast(0) to bottomPx.coerceAtLeast(0)
        if (next == _insets) return
        _insets = next
        recomputePagination(density)
    }

    // ============================================================
    // 翻页与跳转
    // ============================================================

    /** 翻页。跨章时自动衔接。 */
    fun flip(delta: Int, density: Density) {
        val pages = _pages.value
        val target = _pageIndex.value + delta
        _forward.value = delta > 0

        if (target in pages.indices) {
            // 先把当前页快照下来再改索引：翻页动画需要它作为「下面那页」
            captureOutgoing()
            _pageIndex.value = target
            _flipToken.value++
            schedulePersist()
            return
        }

        // 跨章
        val next = _chapterIndex.value + delta
        val headers = _chapterHeaders.value
        if (next !in headers.indices) {
            _message.value = if (delta > 0) "已经是最后一章了" else "已经是第一章了"
            return
        }
        captureOutgoing()
        _flipToken.value++
        scope.launch {
            val bookId = _book.value?.id ?: return@launch
            val chapter = withContext(Dispatchers.IO) { bookRepo.getChapter(bookId, next) } ?: return@launch
            _currentChapter.value = chapter
            _chapterIndex.value = next
            recomputePagination(density, force = true)
            // 向前翻到新章的开头；向后翻到新章的末尾
            _pageIndex.value = if (delta > 0) 0 else (_pages.value.size - 1).coerceAtLeast(0)
            refreshChapterHighlights()
            schedulePersist()
        }
    }

    /** 跳转到指定章（目录点击）。 */
    fun jumpToChapter(index: Int, density: Density, offset: Int = 0) {
        val headers = _chapterHeaders.value
        if (index !in headers.indices) return
        _forward.value = index > _chapterIndex.value
        captureOutgoing()
        _flipToken.value++
        loadChapter(index, offset, density)
        schedulePersist()
    }

    /** 按进度百分比跳转（底部进度条拖动）。 */
    fun jumpToPercent(percent: Float, density: Density) {
        val bookId = _book.value?.id ?: return
        scope.launch {
            val headers = _chapterHeaders.value
            if (headers.isEmpty()) return@launch
            val total = headers.sumOf { it.length }.coerceAtLeast(1)
            val target = (percent.coerceIn(0f, 1f) * total).toInt()

            // 找到目标全局偏移落在哪一章
            var acc = 0
            var targetChapter = headers.lastIndex
            for ((i, header) in headers.withIndex()) {
                if (target < acc + header.length) {
                    targetChapter = i
                    break
                }
                acc += header.length
            }
            val offsetInChapter = (target - acc).coerceAtLeast(0)
            _forward.value = targetChapter >= _chapterIndex.value
            _flipToken.value++
            loadChapter(targetChapter, offsetInChapter, density)
            schedulePersist()
        }
    }

    // ============================================================
    // 位置持久化（节流）
    // ============================================================

    /**
     * 安排一次位置持久化。
     *
     * 节流 800ms：快速连翻十几页时只写最后一次，
     * 否则每页一次数据库写入会造成明显卡顿（WAL 也扛不住这个频率）。
     */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DELAY_MS)
            persistNow()
        }
    }

    /** 立刻持久化（退出阅读器、切后台时调用）。 */
    fun persistNow() {
        val bookId = _book.value?.id ?: return
        val chapter = _currentChapter.value ?: return
        val page = _pages.value.getOrNull(_pageIndex.value) ?: return
        val headers = _chapterHeaders.value
        val total = headers.sumOf { it.length }.coerceAtLeast(1)
        val globalOffset = chapter.start + page.start

        val position = ReadingPosition(
            chapterIndex = _chapterIndex.value,
            chapterOffset = page.start,
            globalOffset = globalOffset,
            pageIndex = _pageIndex.value,
            percent = (globalOffset.toFloat() / total).coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
        )
        // 走应用级作用域：本函数也会在 onCleared 里被调用，那时 viewModelScope 已被取消
        container.applicationScope.launch { bookRepo.savePosition(bookId, position) }
    }

    // ============================================================
    // 阅读会话统计
    // ============================================================

    private fun startSession(globalOffset: Int) {
        sessionStartAt = System.currentTimeMillis()
        sessionStartOffset = globalOffset
    }

    /** 结束会话并记录统计。少于 5 秒不记录（避免误触污染阅读天数）。 */
    fun endSession() {
        val bookId = _book.value?.id ?: return
        if (sessionStartAt == 0L) return
        val durationSec = ((System.currentTimeMillis() - sessionStartAt) / 1000).toInt()
        sessionStartAt = 0L

        val chapter = _currentChapter.value
        val page = _pages.value.getOrNull(_pageIndex.value)
        val nowOffset = if (chapter != null && page != null) chapter.start + page.start else sessionStartOffset
        val charsRead = (nowOffset - sessionStartOffset).coerceAtLeast(0)

        // 同样走应用级作用域：切后台与退出时的结算都在 onCleared 路径上
        container.applicationScope.launch { statsRepo.recordSession(bookId, durationSec, charsRead) }
    }

    /** 重新开始一段会话（从后台返回时调用）。 */
    fun resumeSession() {
        if (sessionStartAt != 0L) return
        val chapter = _currentChapter.value
        val page = _pages.value.getOrNull(_pageIndex.value)
        sessionStartAt = System.currentTimeMillis()
        sessionStartOffset = if (chapter != null && page != null) chapter.start + page.start else 0
    }

    // ============================================================
    // 笔记
    // ============================================================

    fun setSelection(start: Int, end: Int, text: String) {
        _selection.value = if (end > start && text.isNotBlank()) TextSelection(start, end, text) else null
    }

    /**
     * 长按取词：把「章内字符下标」扩成一个合理的选区。
     *
     * 为什么需要它：Compose 的 [androidx.compose.foundation.text.selection.SelectionContainer]
     * 只提供**拖拽手柄**式的选择，**没有长按自动选中一个词**的行为。
     * 用户长按一个词却什么都没有发生 —— 真机上反馈的就是这个。
     *
     * 取词规则按中英文分开处理，因为两者的「词」概念完全不同：
     *   - 英文/数字：连续的字母数字算一个词（`ReaderViewModel` 的 isWordChar）
     *   - 中文/日文：**单个汉字就是一个词**。中文没有词间空格，
     *     想按「词」切分需要分词词典；而标注场景下选中一两个字通常就够用了，
     *     为此引入一个词典得不偿失。所以长按选中一个汉字，用户可以再拖手柄扩展。
     *
     * @param chapterOffset 章内字符下标（不是页内下标 —— 书签与笔记存的是章内偏移）
     */
    fun selectWordAt(chapterOffset: Int) {
        val chapter = _currentChapter.value ?: return
        val content = chapter.content
        if (chapterOffset !in content.indices) return

        val anchor = content[chapterOffset]
        var start = chapterOffset
        var end = chapterOffset + 1

        if (isWordChar(anchor)) {
            // 西文：向两侧扩到词边界
            while (start > 0 && isWordChar(content[start - 1])) start--
            while (end < content.length && isWordChar(content[end])) end++
        }
        // 汉字与标点：只选中按下那一个字符，由用户拖手柄扩展

        val text = content.substring(start, end)
        if (text.isBlank()) return
        _selection.value = TextSelection(start, end, text)
    }

    /** 西文「词」的构成字符：字母、数字、下划线，以及词内的连字符与撇号。 */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() && c.code < 0x2E80 || c == '_' || c == '\'' || c == '-'

    fun clearSelection() {
        _selection.value = null
    }

    fun addBookmarkAtSelection() {
        val bookId = _book.value?.id ?: return
        val selection = _selection.value ?: return
        val chapter = _currentChapter.value ?: return
        scope.launch {
            noteRepo.addBookmark(
                bookId = bookId,
                chapterIndex = _chapterIndex.value,
                chapterOffset = selection.start,
                globalOffset = chapter.start + selection.start,
                excerpt = selection.text,
            )
            reloadNotes(bookId)
            _selection.value = null
            _message.value = "已添加书签"
        }
    }

    fun addBookmarkAtPageStart() {
        val bookId = _book.value?.id ?: return
        val chapter = _currentChapter.value ?: return
        val page = _pages.value.getOrNull(_pageIndex.value) ?: return
        val excerpt = chapter.content.substring(
            page.start,
            (page.start + 60).coerceAtMost(page.end),
        )
        scope.launch {
            noteRepo.addBookmark(
                bookId = bookId,
                chapterIndex = _chapterIndex.value,
                chapterOffset = page.start,
                globalOffset = chapter.start + page.start,
                excerpt = excerpt,
            )
            reloadNotes(bookId)
            _message.value = "已在本页添加书签"
        }
    }

    fun addHighlightFromSelection(note: String, color: String) {
        val bookId = _book.value?.id ?: return
        val selection = _selection.value ?: return
        scope.launch {
            noteRepo.addHighlight(
                bookId = bookId,
                chapterIndex = _chapterIndex.value,
                startOffset = selection.start,
                endOffset = selection.end,
                text = selection.text,
                color = color,
                note = note,
            )
            reloadNotes(bookId)
            _selection.value = null
            _message.value = "已划线"
        }
    }

    fun deleteBookmark(id: String) {
        val bookId = _book.value?.id ?: return
        scope.launch {
            noteRepo.removeBookmark(id)
            reloadNotes(bookId)
            _message.value = "已删除书签"
        }
    }

    fun deleteHighlight(id: String) {
        val bookId = _book.value?.id ?: return
        scope.launch {
            noteRepo.removeHighlight(id)
            reloadNotes(bookId)
            _message.value = "已删除划线"
        }
    }

    // ============================================================
    // 查词
    // ============================================================

    fun lookupSelection() {
        val selection = _selection.value ?: return
        lookup(selection.text)
    }

    fun lookup(text: String) {
        scope.launch {
            _dictLoading.value = true
            _dictResult.value = null
            val result = dictionary.lookup(text)
            _dictResult.value = result
            _dictLoading.value = false
        }
    }

    fun clearDictionary() {
        _dictResult.value = null
    }

    // ============================================================
    // 自动阅读
    // ============================================================

    /**
     * 切换自动阅读。
     *
     * 节奏是**固定的每页秒数**（用户在设置里直接设定），不随页面长短浮动。
     *
     * 早先按「本页字符数 ÷ 每秒字数」算，长页停得久、短页停得短。
     * 那个做法的出发点是「观感自然」，但实际用起来是反的：
     * 用户想的是「设个节奏让它自己翻」，结果每一页要等多久完全无法预期，
     * 短页一闪而过、长页又像卡住了。所以改回确定的秒数。
     */
    fun toggleAutoRead(density: Density) {
        if (_autoReading.value) {
            stopAutoRead()
            return
        }
        _autoReading.value = true
        autoReadJob = scope.launch {
            while (_autoReading.value) {
                val seconds = _settings.value.autoReadSecondsPerPage.coerceIn(2, 120)
                delay(seconds * 1000L)

                if (!_autoReading.value) break

                val isLastPage = _pageIndex.value >= _pages.value.size - 1
                val isLastChapter = _chapterIndex.value >= _chapterHeaders.value.size - 1
                if (isLastPage && isLastChapter) {
                    _autoReading.value = false
                    _message.value = "全书已读完"
                    break
                }
                flip(1, density)
            }
        }
    }

    fun stopAutoRead() {
        _autoReading.value = false
        autoReadJob?.cancel()
        autoReadJob = null
    }

    // ============================================================
    // 朗读
    // ============================================================

    fun toggleSpeech(density: Density) {
        if (tts.state.value == com.moyu.reader.reader.TtsController.State.SPEAKING) {
            tts.stop()
            return
        }
        speakCurrentPage(density)
    }

    private fun speakCurrentPage(density: Density) {
        val chapter = _currentChapter.value ?: return
        val page = _pages.value.getOrNull(_pageIndex.value) ?: return
        val text = chapter.content.substring(page.start, page.end)
            .replace('\n', '。')
            .take(MAX_SPEAK_CHARS)
        if (text.isBlank()) return

        tts.speak(
            text = text,
            rate = _settings.value.ttsRate,
            pitch = _settings.value.ttsPitch,
            onFinished = {
                // 读完当前页自动翻页继续；到全书末尾则停止
                val isLastPage = _pageIndex.value >= _pages.value.size - 1
                val isLastChapter = _chapterIndex.value >= _chapterHeaders.value.size - 1
                if (isLastPage && isLastChapter) {
                    _message.value = "全书已朗读完毕"
                } else {
                    scope.launch { flip(1, density) }
                }
            },
        )
    }

    // ============================================================
    // 设置
    // ============================================================

    fun updateSettings(block: suspend (com.moyu.reader.data.prefs.SettingsStore) -> Unit) {
        scope.launch { block(store) }
    }

    fun quickSetTheme(theme: com.moyu.reader.data.prefs.ThemeId) {
        scope.launch { store.setTheme(theme) }
    }

    fun quickToggleNight() {
        val current = _settings.value.theme
        val next = if (current == com.moyu.reader.data.prefs.ThemeId.NIGHT) {
            com.moyu.reader.data.prefs.ThemeId.PAPER
        } else {
            com.moyu.reader.data.prefs.ThemeId.NIGHT
        }
        scope.launch { store.setTheme(next) }
    }

    fun setPageMode(mode: PageMode) {
        scope.launch { store.setPageMode(mode) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun markFinished() {
        val bookId = _book.value?.id ?: return
        scope.launch {
            bookRepo.setStatus(bookId, ReadingStatus.FINISHED)
            _message.value = "已标记为读完"
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRead()
        tts.stop()
        // 退出前把位置与会话结算掉，否则最后的阅读进度与时长会丢失
        persistNow()
        endSession()
    }

    companion object {
        /** 位置持久化节流间隔。 */
        const val PERSIST_DELAY_MS = 800L

        /** 单次交给 TTS 的最大字符数：太长会让引擎排队很久，用户点停止也没反应。 */
        const val MAX_SPEAK_CHARS = 1200
    }
}

/** 用户在正文里选中的一段文字。 */
data class TextSelection(
    val start: Int,
    val end: Int,
    val text: String,
)
