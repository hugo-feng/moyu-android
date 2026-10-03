package com.moyu.reader.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ReaderViewModel
import com.moyu.reader.ui.navigationBarHeightPx
import com.moyu.reader.ui.statusBarHeightPx
import com.moyu.reader.ui.theme.fontFamilyFor
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 阅读器界面。
 *
 * 结构分四层（自下而上）：
 *   1. **护眼色温遮罩**：纯视觉叠加，不拦截任何触摸；
 *   2. **正文层**：当前页文本，支持长按选中；
 *   3. **手势层**：左/中/右三热区 + 水平拖拽翻页；
 *   4. **浮层**：顶部栏、底部工具栏、各种面板（目录/排版/笔记/查词）。
 *
 * 三热区的交互与主流小说 App 保持一致：左侧上一页、右侧下一页、中间呼出工具栏。
 * 这是用户已经形成肌肉记忆的操作方式，不应该为了「设计感」去改。
 */
@Composable
fun ReaderScreen(
    factory: MoyuViewModelFactory,
    bookId: String,
    onExit: () -> Unit,
    onOpenSearch: (String) -> Unit,
    /** 打开后要跳到的章；null 表示沿用数据库里记录的上次位置 */
    jumpToChapter: Int? = null,
    /** 章内字符偏移，配合 jumpToChapter 使用 */
    jumpToOffset: Int = 0,
) {
    val viewModel: ReaderViewModel = viewModel(factory = factory)
    val density = LocalDensity.current
    val context = LocalContext.current
    // 生命周期观察与系统栏明暗都要用到宿主 Activity
    val activity = context as? android.app.Activity
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = LocalView.current

    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    val chapterHeaders by viewModel.chapterHeaders.collectAsStateWithLifecycle()
    val chapterIndex by viewModel.chapterIndex.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val pageIndex by viewModel.pageIndex.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val dictResult by viewModel.dictResult.collectAsStateWithLifecycle()
    val dictLoading by viewModel.dictLoading.collectAsStateWithLifecycle()
    val autoReading by viewModel.autoReading.collectAsStateWithLifecycle()
    val ttsState by viewModel.tts.state.collectAsStateWithLifecycle()
    val highlights by viewModel.chapterHighlights.collectAsStateWithLifecycle()
    // 翻页动画的触发令牌（每次翻页自增）。不订阅它就等于关掉了所有翻页动效。
    val flipToken by viewModel.flipToken.collectAsStateWithLifecycle()
    // 当前页的书签：决定工具栏上那个按钮是镂空还是填充
    val currentPageBookmark by viewModel.currentPageBookmark.collectAsStateWithLifecycle()

    var chromeVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }

    /**
     * 返回层级：先关面板 → 再关工具栏 → 最后才退出阅读页。
     *
     * 不做这件事的话，返回手势/返回键会**直接 pop 掉整个阅读页**，
     * 哪怕用户只是想关掉刚打开的目录面板。这对「从左边滑一下」的
     * 边缘手势尤其明显：手还在屏幕上，人已经回到书架了。
     *
     * 用 `enabled` 控制拦截时机而不是在回调里判断要不要拦截：
     * 只有在「确实有东西可关」时才拦截，否则放行给系统，
     * 这样没有面板时返回手势的手感与系统完全一致。
     */
    val hasSomethingToClose = sheet != ReaderSheet.NONE || chromeVisible
    androidx.activity.compose.BackHandler(enabled = hasSomethingToClose) {
        when {
            sheet != ReaderSheet.NONE -> sheet = ReaderSheet.NONE
            chromeVisible -> chromeVisible = false
        }
    }

    val palette = moyuPalette()
    val chapterContent = chapter?.content.orEmpty()
    val currentPage = pages.getOrNull(pageIndex)

    // 打开书籍：视口尺寸要等布局完成才知道，因此这里先触发一次 open
    LaunchedEffect(bookId, jumpToChapter, jumpToOffset) {
        viewModel.open(bookId, density)
        viewModel.resumeSession()
        // 来自搜索/笔记的跳转目标：open 完成后按章与章内偏移定位。
        // 不处理它的话，点搜索结果只会停在「上次读到的地方」。
        if (jumpToChapter != null) {
            viewModel.jumpToChapter(jumpToChapter, density, jumpToOffset)
        }
    }

    // 常亮设置。
    //
    // 注意：FLAG_KEEP_SCREEN_ON 必须加在 **Activity 的 window** 上，
    // 而不是 Compose 的 LocalView —— 那只是一个子 View，加在上面不生效。
    DisposableEffect(settings.keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            if (settings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose { }
    }

    // 离开阅读器时结算：位置持久化 + 会话统计
    DisposableEffect(Unit) {
        onDispose {
            viewModel.persistNow()
            viewModel.endSession()
            viewModel.stopAutoRead()
        }
    }

    /**
     * 音量键翻页。
     *
     * 设置里那个开关从 v1.0 起就存在，但一直没有按键处理 ——
     * 打开它按音量键只会调音量（审计里被明确标为「死设置」）。
     * 这里接到 [com.moyu.reader.VolumeKeyRouter]：阅读页在的时候
     * 音量键就是翻页键，离开阅读页立刻还原成音量键。
     *
     * 音量减=下一页、音量加=上一页，与主流阅读器一致（握持时拇指够得着）。
     * 面板打开或工具栏可见时不翻页：那会儿用户在操作界面，误翻页很烦。
     */
    DisposableEffect(settings.volumeKeyPaging, sheet, chromeVisible) {
        if (settings.volumeKeyPaging) {
            com.moyu.reader.VolumeKeyRouter.handler = { volumeDown ->
                if (sheet != ReaderSheet.NONE || chromeVisible) {
                    false
                } else {
                    viewModel.flip(if (volumeDown) 1 else -1, density)
                    true
                }
            }
        } else {
            com.moyu.reader.VolumeKeyRouter.handler = null
        }
        onDispose { com.moyu.reader.VolumeKeyRouter.handler = null }
    }

    /**
     * 切后台时结算阅读时长。
     *
     * 这段早先是个**空实现**（注释写着「切后台时也要结算」但 onDispose 里什么都没有），
     * 后果是：打开书 → 按 Home → 三小时后回来 → 退出，这三小时会被算成阅读时长，
     * 直接污染每日时长、周报与热力图。两个方法本来就存在，只差接线。
     */
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.endSession()
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.resumeSession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * 系统栏图标的明暗跟随应用内主题。
     *
     * `enableEdgeToEdge()` 只在 Activity 创建时按**系统** uiMode 判定一次；
     * 用户在阅读页点「夜间」或设置里选夜幕主题时系统 uiMode 不变，
     * 状态栏图标仍是深色，落在深色背景上几乎看不见。
     */
    DisposableEffect(settings.theme, settings.followSystemDark) {
        val window = activity?.window
        val view = activity?.findViewById<android.view.View>(android.R.id.content)
        val isDark = palette.dark
        if (window != null && view != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // 护眼色温：用极暖的橙色叠加并降低不透明度，
        // 比直接调低色温（改背景色）更能保留主题本身的观感。
        if (settings.eyeCareWarmth > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF9B2F).copy(alpha = settings.eyeCareWarmth * 0.12f)),
            )
        }

        // —— 正文区 ——
        // 安全区要先上报给 ViewModel：分页必须扣除系统栏高度，
        // 否则排出来的页会比版心高一截，末行溢出到屏幕外。
        val safeTopPx = statusBarHeightPx()
        val safeBottomPx = navigationBarHeightPx()
        LaunchedEffect(safeTopPx, safeBottomPx) {
            viewModel.setInsets(safeTopPx, safeBottomPx, density)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewModel.setViewport(size.width, size.height, density)
                },
        ) {
            if (chapter == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "正在载入…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                }
            } else if (settings.pageMode == PageMode.SCROLL) {
                ScrollReader(
                    content = chapterContent,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterNumberLabel = chapterNumberLabel(chapter?.title.orEmpty(), chapterIndex),
                    highlights = highlights,
                    settings = settings,
                    viewModel = viewModel,
                    autoReading = autoReading,
                    // 滚到本章末尾时自动进入下一章，这样自动阅读能一直读下去
                    onReachEnd = { viewModel.flip(1, density) },
                )
            } else if (currentPage != null) {
                PagedReader(
                    content = chapterContent,
                    pageStart = currentPage.start,
                    pageEnd = currentPage.end,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterNumberLabel = chapterNumberLabel(chapter?.title.orEmpty(), chapterIndex),
                    /**
                     * 章首页 = 第 0 页。
                     *
                     * 不能用 `currentPage.start == 0` 判断：章首那行标题已经不在
                     * 正文区间里了（见 ChapterSplitter.headingSkipLength），
                     * 首页的起始偏移是标题之后的位置，不再是 0。
                     */
                    showTitle = pageIndex == 0,
                    settings = settings,
                    pageNumber = pageIndex + 1,
                    percent = viewModelPercent(pages, chapterHeaders, chapterIndex, pageIndex),
                    highlights = highlights,
                    // 翻页动画的触发令牌：必须来自 ViewModel。
                    // 早先这里硬编码 0，而 PagedReader 用 remember(flipToken) 触发动效，
                    // 于是仿真/平移/覆盖三种翻页动画**从未播放过** —— 令牌永远是同一个值。
                    flipToken = flipToken,
                    viewModel = viewModel,
                    uiDensity = density,
                )
            }
        }


        /**
         * 手势层：**整个阅读页只有这一层**，而且全程不消费事件。
         *
         * ## 为什么必须只有一层（用户报的「要点两下才能翻页」）
         *
         * 之前这里是两层叠加：一层是 `detectTapGestures` +
         * `detectHorizontalDragGestures`（只在分页模式注册），另一层是自己写的
         * 指针跟踪。两层都实现了同一套三热区逻辑，于是**同一次点击被处理两遍**：
         *   - 中间热区被切换两次 → 工具栏看起来「点了没反应」；
         *   - 左右热区连翻两页 → 用户只好再点一次「补回来」。
         * 再加上旧实现结尾那个「等手指全部抬起」的循环：它在手指已经抬起之后
         * 才开始等，于是**下一次点击被整个吃掉** —— 这才是
         * 「覆盖模式要点两下才能切换下一页」的真正来源。
         *
         * ## 为什么不能用 detectTapGestures（用户报的「滚动不了」）
         *
         * 它一旦挂上就消费整条手势流：滚动模式的 `verticalScroll`
         * 再也收不到拖动。而且它判定点击只看按下与抬起的时间差，
         * **完全不关心中间移动了多远** —— 快速滑动也会被判成点击。
         *
         * ## 现在这一层的职责
         *
         * 只跟踪指针、只做判定，**一个事件都不消费**：
         *   - 位移没超过 `touchSlop` → 点击：左/中/右三热区；
         *   - 分页模式下横向位移超过阈值 → 翻页（判断累计位移而不是单帧增量，
         *     否则一次滑动会产生很多个超过阈值的 move，一划连翻好几页）；
         *   - 滚动模式下的纵向拖动 → 不消费，原样交给 `verticalScroll`。
         *
         * 正文自身不带任何手势：点击翻页、横向滑动翻页、纵向拖动滚动，
         * 全部由这一层处理（正文一挂手势就会把滚动吃掉）。
         *
         * 「等手指抬起」那个循环已经删掉：内层循环本来就在抬手时退出，
         * 外层的 `awaitFirstDown` 天然只认**新的按下**，不需要额外排空。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings.pageMode) {
                    val slop = viewConfiguration.touchSlop
                    val flipThreshold = 56.dp.toPx()
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var totalY = 0f
                            var moved = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                // 必须用 IgnoreConsumed：滚动层消费过的位移
                                // 用 positionChange() 读出来是 0，位移就永远累计不起来
                                val delta = change.positionChangeIgnoreConsumed()
                                totalX += delta.x
                                totalY += delta.y
                                if (!moved && kotlin.math.hypot(totalX, totalY) > slop) {
                                    moved = true
                                }
                            }

                            if (!moved) {
                                val third = size.width / 3f
                                val x = down.position.x
                                when {
                                    settings.pageMode == PageMode.SCROLL ||
                                        (x >= third && x <= third * 2) -> {
                                        chromeVisible = !chromeVisible
                                        if (!chromeVisible) sheet = ReaderSheet.NONE
                                    }

                                    x < third -> if (!chromeVisible) viewModel.flip(-1, density)

                                    else -> if (!chromeVisible) viewModel.flip(1, density)
                                }
                            } else if (moved &&
                                settings.pageMode != PageMode.SCROLL &&
                                kotlin.math.abs(totalX) > flipThreshold &&
                                kotlin.math.abs(totalX) > kotlin.math.abs(totalY)
                            ) {
                                viewModel.flip(if (totalX < 0f) 1 else -1, density)
                            }
                        }
                    }
                },
        )

        // —— 顶部栏 ——
        if (chromeVisible) {
            ReaderTopBar(
                bookTitle = book?.title.orEmpty(),
                chapterTitle = chapter?.title.orEmpty(),
                speaking = ttsState == com.moyu.reader.reader.TtsController.State.SPEAKING,
                bookmarked = currentPageBookmark != null,
                onBack = {
                    viewModel.persistNow()
                    onExit()
                },
                onSpeak = { viewModel.toggleSpeech(density) },
                onSearch = { onOpenSearch(bookId) },
                onToggleBookmark = { viewModel.toggleBookmarkAtPage() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // —— 底部工具栏 ——
        if (chromeVisible) {
            ReaderBottomBar(
                chapterIndex = chapterIndex,
                chapterCount = chapterHeaders.size,
                onChapterSeek = { index -> viewModel.jumpToChapter(index, density) },
                onToc = { sheet = if (sheet == ReaderSheet.TOC) ReaderSheet.NONE else ReaderSheet.TOC },
                onBookmarks = {
                    sheet = if (sheet == ReaderSheet.BOOKMARKS) ReaderSheet.NONE else ReaderSheet.BOOKMARKS
                },
                onSettings = {
                    sheet = if (sheet == ReaderSheet.TYPOGRAPHY) ReaderSheet.NONE else ReaderSheet.TYPOGRAPHY
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // —— 查词结果 ——
        if (dictResult != null || dictLoading) {
            DictionaryPopup(
                loading = dictLoading,
                result = dictResult,
                onDismiss = { viewModel.clearDictionary() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // —— 面板：目录 / 笔记 / 排版 ——
        when (sheet) {
            ReaderSheet.TOC -> TocSheet(
                headers = chapterHeaders.map { it.title },
                currentIndex = chapterIndex,
                onSelect = { index ->
                    viewModel.jumpToChapter(index, density)
                    sheet = ReaderSheet.NONE
                },
                onDismiss = { sheet = ReaderSheet.NONE },
            )

            ReaderSheet.BOOKMARKS -> ReaderBookmarksSheet(
                viewModel = viewModel,
                onJump = { chapterIdx, offset ->
                    viewModel.jumpToChapter(chapterIdx, density, offset)
                    sheet = ReaderSheet.NONE
                },
                onDismiss = { sheet = ReaderSheet.NONE },
            )

            ReaderSheet.TYPOGRAPHY -> TypographySheet(
                viewModel = viewModel,
                onDismiss = { sheet = ReaderSheet.NONE },
            )

            ReaderSheet.NONE -> Unit
        }

        // —— 提示条 ——
        if (message != null) {
            ToastMessage(
                text = message!!,
                onDismiss = { viewModel.consumeMessage() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }

        // 自动阅读指示
        if (autoReading) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp)
                    .background(palette.card.copy(alpha = 0.94f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "自动阅读中 · ${settings.autoReadSecondsPerPage} 秒/页",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

/** 阅读器内可打开的面板。 */
private enum class ReaderSheet { NONE, TOC, BOOKMARKS, TYPOGRAPHY }

/**
 * 分页阅读视图。
 *
 * 翻页动画按设置执行：仿真（3D 旋转）、平移、覆盖、无动画。
 * 动画用 graphicsLayer 的 rotationY/translationX 实现，
 * 因为它们不触发重新布局，性能远好于用 AnimatedContent 切换整个内容树。
 */
@Composable
private fun PagedReader(
    content: String,
    pageStart: Int,
    pageEnd: Int,
    chapterTitle: String,
    chapterNumberLabel: String,
    showTitle: Boolean,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    pageNumber: Int,
    percent: Float,
    highlights: List<IntRange>,
    flipToken: Int,
    /** 用于上报实测的正文区高度与标题高度 —— 分页的纵向依据 */
    viewModel: ReaderViewModel,
    /**
     * 密度。刻意不叫 `density`：`graphicsLayer {}` 的接收者本身就叫 density，
     * 同名参数会把那个接收者遮蔽掉，里面的 cameraDistance 就解析不到了。
     */
    uiDensity: androidx.compose.ui.unit.Density,
) {
    val palette = moyuPalette()
    val pageText = content.substring(
        pageStart.coerceIn(0, content.length),
        pageEnd.coerceIn(0, content.length),
    )

    /**
     * 地脚高度固定。
     *
     * 正文区是 `weight(1f)`，分到的是「本 Column 扣掉其他子项之后」的空间。
     * 地脚若随内容变化（电量从 100% 变 99%、时间从 9:59 变 10:00），
     * 正文区高度就会跟着变，触发重新分页 —— 用户会看到读到一半突然重排。
     * 固定高度之后测量值稳定，分页也稳定。
     */
    val footerHeight = 26.dp

    // —— 地脚的时钟：每分钟更新一次即可，不必每秒 ——
    //
    // 用 remember + LaunchedEffect(delay 到下一分钟) 而不是每秒轮询：
    // 秒级刷新会让整个阅读页每秒重组一次，白白耗电。
    var clockText by remember { mutableStateOf(currentClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            // 对齐到下一个整分钟再刷新，避免 59 秒后才更新的迟滞感
            val now = java.util.Calendar.getInstance()
            val msToNextMinute = (60 - now.get(java.util.Calendar.SECOND)) * 1000L -
                now.get(java.util.Calendar.MILLISECOND)
            kotlinx.coroutines.delay(msToNextMinute.coerceAtLeast(1000L))
            clockText = currentClockText()
        }
    }

    // —— 电量：ACTION_BATTERY_CHANGED 是 sticky intent，注册即回调 ——
    //
    // 用它而不是 BatteryManager 的 getIntProperty：后者同样无需权限，
    // 但拿不到「电量变化」的推送，只能轮询。sticky 广播既不轮询也不需要权限。
    val context = LocalContext.current
    var batteryPercent by remember { mutableIntStateOf(-1) }
    DisposableEffect(context, settings.showStatusBar) {
        if (!settings.showStatusBar) {
            onDispose { }
        } else {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    batteryPercent =
                        if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                }
            }
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            // Android 14+ 要求显式声明是否导出，否则注册会抛异常
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    // 翻页动画：以 flipToken 为 key 重新触发一次入场动画
    var entered by remember(flipToken) { mutableStateOf(false) }
    LaunchedEffect(flipToken) {
        entered = false
        androidx.compose.runtime.withFrameNanos { }
        entered = true
    }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        // 覆盖用 240ms；无动画模式不发任何过渡。
        animationSpec = tween(durationMillis = if (settings.pageMode == PageMode.NONE) 0 else 240),
        label = "pageFlip",
    )

    val outgoing = viewModel.outgoingPage.collectAsStateWithLifecycle().value
    val forward by viewModel.forward.collectAsStateWithLifecycle()

    // 按屏宽算位移量：三种模式的位移都应当以「一屏」为单位，
    // 而不是写死 80/240 这类像素值 —— 那在小屏上会显得没动、大屏上又太夸张。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val pageWidthPx = with(uiDensity) { screenWidthDp.dp.toPx() }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBatteryPercent provides batteryPercent,
    ) {
        /**
         * 两段式：上「正文层」、下「独立地脚」。
         *
         * 这里用 Column 而不是把地脚 `align(BottomCenter)` 叠在 Box 上：
         * 叠放时地脚会盖在正文层之上，正文层的实测高度仍包含地脚占的那一段；
         * 而 Column 让地脚**先占掉自己的高度**，正文层拿到的 `weight(1f)`
         * 是真正可用的空间 —— 分页测量的高度与视觉可用高度天然一致，
         * 不需要再手工扣减，也就不会再出现「多排一行、末行被裁」。
         */
        Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            /**
             * 这里**刻意不画「即将翻走的那一页」**。
             *
             * 用户反馈「前一页的文字和后一页的文字重叠」。根因就是两层页面
             * 同时绘制：新页从右侧滑入时，屏幕左边是旧页的字、右边是新页的字，
             * 同一屏出现两套正文 —— 看起来就是文字重叠。
             * 仿真模式（两页各自旋转）最严重，覆盖模式（新页盖上来）同样存在。
             *
             * 用户的要求是删掉仿真、去掉平移动画。现在彻底只画一页：
             * 新页做一次整页滑入，任何一帧都只有一套文字，不可能重叠。
             *
             * [viewModel.outgoingPage] 的快照仍保留 —— 分页测量与
             * 「无动画」模式还会用到它，删掉会让将来想恢复单页过渡时无从下手。
             */

            // 即将翻到的那一页，画在上层
            /**
             * 「无动画」必须是**真的没有过渡**。
             *
             * 之前这里给的是 `if (entered) progress else 0f`，而 NONE 模式的
             * `tween(0)` 仍然会让 `animateFloatAsState` 走一帧插值，
             * 新页从「完全透明」淡进来 —— 与设置里写的「直接切换，没有过渡」
             * 不是一回事（审计里就是这么记的）。
             * 这里直接把进度钉成 1：NONE 模式下页面永远处于「已就位」。
             */
            val frameProgress = when {
                settings.pageMode == PageMode.NONE -> 1f
                entered -> progress
                else -> 0f
            }
            val incomingGeometry = pageGeometry(
                mode = settings.pageMode,
                progress = frameProgress,
                incoming = true,
                forward = forward,
                widthPx = pageWidthPx,
            )
            ReaderPage(
                text = pageText,
                pageNumber = pageNumber,
                showTitle = showTitle,
                chapterTitle = chapterTitle,
                chapterNumberLabel = chapterNumberLabel,
                settings = settings,
                highlights = highlights,
                pageStart = pageStart,
                startsMidParagraph = pageStart > 0 && content.getOrNull(pageStart - 1) != '\n',
                percent = percent,
                viewModel = viewModel,
                uiDensity = uiDensity,
                onContentSize = { size ->
                    viewModel.setContentBoxSize(size.width, size.height, uiDensity)
                },
                modifier = Modifier.graphicsLayer {
                    translationX = incomingGeometry.translationX
                    rotationY = incomingGeometry.rotationY
                    scaleX = incomingGeometry.scaleX
                    alpha = incomingGeometry.alpha
                    cameraDistance = 20f * uiDensity.density
                },
            )

            // —— 全书进度：一条 1dp 细线，贴页的下缘 ——
            //
            // 画在最上层且不参与翻页变换：它是「整本书读到哪儿」的指示，
            // 跟着页面一起位移会让人以为进度条也在翻页。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.textSecondary.copy(alpha = 0.16f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(palette.textSecondary.copy(alpha = 0.5f)),
                )
            }

            /**
             * 全书进度细线贴的是**正文层的下缘**，所以它必须留在上面这个 Box 里。
             */
        }

        /**
         * 地脚是**独立的底部条**，是 Column 的第二个子项，不在正文层里。
         *
         * ## 上一版错在哪（用户报的「电池还在原位顶部」）
         *
         * 注释写着「它是 Column 的第二个子项」，代码却把它写在了正文那个
         * `Box` **里面**。Box 的默认对齐是 TopStart —— 于是页码、时间、
         * 电量被画到了屏幕**最顶端**，与系统状态栏的电池图标并排；
         * 又因为 `.height(26.dp)` 写在 `.padding(bottom = safeBottom)` 之前，
         * 手势条的安全区把那 26dp 整个吃掉，文字被挤出行外、只露出半截。
         *
         * 现在位置由结构保证：
         *   - `weight(1f)` 的正文层先吃掉剩余空间，地脚自然落在底部；
         *   - 它不在翻页动画层里，翻页时页码与电量稳定不动；
         *   - 正文层实测到的高度**天然不含地脚**，分页口径与视觉一致。
         */
        PageFooterBar(
            pageNumber = pageNumber,
            settings = settings,
        )
        }
    }
}

/**
 * 阅读区底部的信息条：左页码，右时间与电量。
 *
 * ## 为什么独立成一个组件
 *
 * 用户反馈「底部的那几项应该是独立的，而不是包含在文章内」，
 * 以及截图里 **app 的电池图标与系统电池图标并排显示在状态栏上** ——
 * 说明地脚曾经被渲染到了屏幕顶部。
 *
 * 现在它是 PagedReader 里 Column 的第二个子项：正文层 `weight(1f)` 先吃掉
 * 剩余空间，它自然落在最下面。位置由**结构**保证，不靠任何高度推算。
 */
@Composable
private fun PageFooterBar(
    pageNumber: Int,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    val style = furnitureStyle(settings)
    val safeBottom = com.moyu.reader.ui.safeBottom

    Row(
        modifier = modifier
            .fillMaxWidth()
            /**
             * 修饰符顺序在这里是**功能性**的，不能随手调换。
             *
             * `padding` 必须写在 `height` **之前**（也就是更外层）：
             *   - 先 padding：手势条的安全区加在 26dp 之外，整条地脚高
             *     `safeBottom + 26dp`，文字稳稳落在安全区之上；
             *   - 先 height：26dp 被当成「含 padding 的总高」，安全区一扣，
             *     留给文字的高度就接近 0 —— 文字被挤出容器、只露半截，
             *     正是用户看到的现象。
             */
            .padding(
                start = settings.marginDp.dp,
                end = settings.marginDp.dp,
                bottom = maxOf(6.dp, safeBottom),
            )
            // 高度固定，不随内容变化：电量从 100% 变 99% 也不该让正文重排
            .height(FOOTER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (settings.showPageNumber) {
            Text(
                text = pageNumber.toString(),
                style = style,
                color = palette.textSecondary,
                modifier = Modifier.alpha(0.85f),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = currentClockText(),
            style = style,
            color = palette.textSecondary,
            modifier = Modifier.alpha(0.85f),
        )
        if (settings.showStatusBar) {
            Spacer(Modifier.width(7.dp))
            BatteryGlyph(
                percent = LocalBatteryPercent.current,
                tint = palette.textSecondary,
                modifier = Modifier.alpha(0.85f),
            )
        }
    }
}
/**
 * 翻页动画的几何量。
 *
 * 三种模式必须有**本质不同**的运动方式，而不是同一个运动换几个数值。
 * 早先的实现只有「新页位移/旋转」这一个自由度，平移与覆盖只差位移量
 * （80 vs 240），仿真只是转 22° —— 三者看上去一模一样，用户直接报了这个 bug。
 *
 * 真正的区别在于「旧页怎么动」和「新页从哪里来」：
 *
 * | 模式 | 旧页 | 新页 |
 * |---|---|---|
 * | 平移 | 整体左移一屏 | 从右侧滑入 |
 * | 覆盖 | **不动** | 从右侧盖上来（不透明度到 1 才完全遮住） |
 * | 仿真 | 反向小幅旋转（像被掀起的下面一页） | 绕左缘旋转 + 透视 |
 *
 * 所以这一版把「旧页」也算进来。旧页上一版根本不存在 ——
 * 那是三种模式看起来相同的根因：只有一页在场时，无论怎么调参数都不像翻页。
 */
private data class PageGeometry(
    val translationX: Float,
    val rotationY: Float,
    val scaleX: Float,
    val alpha: Float,
)

/**
 * 计算某一帧的页面几何。
 *
 * @param progress 0 → 动画开始（完全是旧页），1 → 动画结束（完全是新页）
 * @param incoming true 表示算的是新页，false 是旧页
 * @param forward  true 表示向后翻（看下一页），false 表示向前翻
 * @param widthPx  页面宽度，用于按屏宽计算位移量
 */
private fun pageGeometry(
    mode: com.moyu.reader.data.prefs.PageMode,
    progress: Float,
    incoming: Boolean,
    forward: Boolean,
    widthPx: Float,
): PageGeometry {
    val p = progress.coerceIn(0f, 1f)
    val remaining = 1f - p
    val dir = if (forward) 1f else -1f

    return when (mode) {
        /**
         * 覆盖：旧页**完全不动**，新页从右侧盖上来。
         *
         * 这是唯一保留的带动画翻页方式。
         *
         * 原先还有「平移」与「仿真」两种，都已删除 —— 它们在真机上
         * 会让**前一页与后一页的文字重叠**：两者同时绘制、各自做位移或旋转，
         * 正文笔画在中间帧互相穿插。覆盖没有这个问题，因为旧页始终静止，
         * 新页是完整地盖上去的（不透明，不会透出下面的字）。
         */
        com.moyu.reader.data.prefs.PageMode.COVER -> {
            if (incoming) PageGeometry(remaining * widthPx * dir, 0f, 1f, 1f)
            else PageGeometry(0f, 0f, 1f, 1f)
        }

        // 无动画：直接切换。alpha 只用来避免切换瞬间闪一下白。
        com.moyu.reader.data.prefs.PageMode.NONE -> PageGeometry(0f, 0f, 1f, if (incoming) p else 1f - p)

        // 滚动模式不走翻页动画（由 ScrollReader 单独处理）
        com.moyu.reader.data.prefs.PageMode.SCROLL -> PageGeometry(0f, 0f, 1f, 1f)
    }
}

/**
 * 渲染一整页（书眉 + 标题 + 正文 + 地脚）。
 *
 * 之所以从 PagedReader 里提取出来：翻页动画需要**同时画两页**
 * （即将翻走的那页 + 即将翻到的那页），两页的版式完全一致，
 * 只有内容与几何不同。不提取的话就得把整段版式代码复制两份，
 * 以后改版式必然漏改一处。
 *
 * @param onContentSize 只有**当前页**才传上报回调 —— 旧页也上报的话，
 *   两页尺寸不一致时会来回覆盖测量值，导致分页反复重算。
 */
@Composable
private fun ReaderPage(
    text: String,
    pageNumber: Int,
    showTitle: Boolean,
    chapterTitle: String,
    chapterNumberLabel: String,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    highlights: List<IntRange>,
    pageStart: Int,
    /**
     * 这一页是否从某一段的中间开始。
     *
     * 由 PagedReader 用「整章正文」判断（要看页边界前一个字符是不是换行），
     * ReaderPage 本身只拿到切片，判断不了。
     */
    startsMidParagraph: Boolean,
    percent: Float,
    viewModel: ReaderViewModel,
    uiDensity: androidx.compose.ui.unit.Density,
    onContentSize: ((androidx.compose.ui.unit.IntSize) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    val furnitureStyle = furnitureStyle(settings)
    val safeTop = com.moyu.reader.ui.safeTop
    val safeBottom = com.moyu.reader.ui.safeBottom

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = settings.marginDp.dp)
            // 纵向留白取「页边距」与「系统栏」的较大值：
            // 页边距调大时仍然生效，高状态栏机型上也不会被压住。
            // 这个值与 ReaderViewModel 里 contentHeight 的扣减必须一致，否则分页会漂移。
            .padding(
                top = maxOf(settings.marginDp.dp * 0.9f, safeTop),
                bottom = maxOf(settings.marginDp.dp * 0.9f, safeBottom),
            ),
    ) {
        // —— 天头：书眉 ——
        //
        // 结构上把「天头 / 正文 / 地脚」写成三行：
        // 天头固定高度在最上、正文 weight(1f) 吃掉中间、地脚固定高度在最下。
        // 位置由结构保证，不依赖任何隐式的高度推算 ——
        // 曾经的写法只有「正文 weight(1f) + 地脚」两段，
        // 真机上出现过**地脚被渲染到屏幕顶部**（用户的截图里 app 的电池图标
        // 与系统电池图标并排显示在状态栏上）。多一个显式的天头占位，
        // 正文区就不可能被压成 0 高而把地脚顶上去。
        //
        // 书眉按真实书籍体例只出现在次页起：章首页的标题本身就在版心内，
        // 顶上再压一条书眉就是同一句话印两遍。不用书眉时占位块高度为 0，
        // 与分页引擎的 firstPageHeaderHeight 口径一致。
        if (!showTitle && chapterTitle.isNotBlank()) {
            Text(
                text = chapterTitle,
                style = furnitureStyle,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .alpha(0.7f),
            )
        }

        // —— 版心：正文 ——
        //
        // clipToBounds 是必须的：Column 的测量高度不受 Box 约束，
        // 一旦分页多排了一行，文字会**画到地脚页码上面**甚至越出页面。
        // 裁掉之后溢出表现为「少一行」而不是「糊成一团」，问题更容易被发现。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .then(
                    if (onContentSize != null) {
                        Modifier.onSizeChanged(onContentSize)
                    } else {
                        Modifier
                    },
                ),
        ) {
            /**
             * 正文层：普通 `Text` + 自研选区手势。
             *
             * ## 为什么正文被换成了普通 `Text`
             *
             * 用户明确要求删掉长按选词。删掉之后正文不需要任何手势，
             * 于是它也不该再挂手势处理器 —— 这里曾经挂过 `SelectionContainer`
             * 与 `BasicTextField`，两者都会抢走整条手势流，和「正文要能滚动」
             * 是互斥的：谁先消费事件，另一个就再也收不到。
             */
            Column(modifier = Modifier.fillMaxSize()) {
                if (showTitle) {
                    // 标题块单独测量：首页要按它扣掉几行，其余页不扣。
                    // 间距用 Spacer 而不是 padding，这样测到的高度
                    // 就是实际占用的高度（padding 会让测量值与占位不一致）。
                    Column(
                        modifier = Modifier.onSizeChanged { size ->
                            viewModel.setFirstPageHeaderHeight(size.height, uiDensity)
                        },
                    ) {
                        Text(
                            text = chapterNumberLabel,
                            style = furnitureStyle,
                            color = palette.textSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = fontFamilyFor(settings.fontFamily),
                            ),
                            color = palette.text,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
                /**
                 * 正文。
                 *
                 * ## 这里为什么只是一个普通 `Text`
                 *
                 * 用户明确要求**删掉长按选词**。删掉之后正文不需要任何手势，
                 * 于是它也不该再挂任何手势处理器 —— 这一层曾经挂过
                 * `SelectionContainer` 与 `BasicTextField`，两者都会抢走整条
                 * 手势流，和「正文要能滚动」是互斥的：谁先消费事件，
                 * 另一个就再也收不到。这正是「滚动模式完全滚不动」的来源。
                 *
                 * 现在正文自身不带手势：点击翻页、横向滑动翻页、
                 * 纵向拖动滚动，全部由上层那张干净的手势网处理。
                 *
                 * 还需要选中文字的话，回到阅读器工具栏里的复制入口即可。
                 */
                Text(
                    text = buildPageText(
                        pageText = text,
                        settings = settings,
                        highlights = highlights,
                        pageStart = pageStart,
                        startsMidParagraph = startsMidParagraph,
                    ),
                    style = bodyTextStyle(settings).copy(color = palette.text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 地脚高度。两页必须一致，否则翻页时地脚会跳。 */
private val FOOTER_HEIGHT = 26.dp

/** 天头书眉 / 地脚页码用的字号：正文的 0.62 倍，与 Web 端保持同一比例。 */
private fun furnitureStyle(
    settings: com.moyu.reader.data.prefs.ReaderSettings,
): TextStyle = TextStyle(
    fontFamily = fontFamilyFor(settings.fontFamily),
    fontSize = (settings.fontSizeSp * 0.62f).sp,
    lineHeight = (settings.fontSizeSp * 0.62f * 1.4f).sp,
    letterSpacing = settings.letterSpacingEm.sp,
)

/**
 * 当前电量，由阅读页测一次后经 CompositionLocal 下发给两页的地脚。
 *
 * 用 CompositionLocal 而不是给 ReaderPage 加参数：地脚要画它，
 * 但它是「环境状态」而不是「这一页的内容」。做成参数的话，
 * 每次电量变化都会让两页的 `ReaderPage` 调用点重建，
 * 更容易把翻页动画打断。
 */
private val LocalBatteryPercent = androidx.compose.runtime.compositionLocalOf { -1 }

/**
 * 组装当前页的文本。逐段缩进 + 段落间距 + 划线高亮。
 *
 *
 * 1. **空行不能一律折叠成一个换行**。早先写的是「非首个元素前插一个 \n」，
 *    而 `split('\n')` 会把空行变成空字符串，于是「段落之间的空行」与
 *    「普通换行」被压成同一种东西 —— 用户调「段间距」完全没反应，
 *    因为间距在渲染前就被丢掉了。现在按 paragraphSpacingMultiplier 决定插几个空行。
 *
 * 2. **高亮区间的偏移换算必须用源文本游标，不能用 AnnotatedString 的下标**。
 *    缩进与空行只存在于渲染结果里，源文本里没有它们；
 *    用 `builder.length` 去换算 `range`（它是相对源 Chapter 的偏移）
 *    会随插入量累积漂移，划线越往后越偏。
 */
private fun buildPageText(
    pageText: String,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    highlights: List<IntRange>,
    pageStart: Int,
    /** 这一页是否从某段中间开始（是则首行不加缩进，见 PageTextComposer.layoutOf） */
    startsMidParagraph: Boolean = false,
): androidx.compose.ui.text.AnnotatedString {
    /**
     * 排版布局来自 [PageTextComposer.layoutOf]。
     *
     * 这一点很关键：早先这里自己写了一遍拼接（插缩进、补空行），
     * 而别处又按「页起始 + 渲染下标」估算源位置 —— 两套规则必然漂移。
     * 现在拼接与换算只有一处规则。
     */
    val layout = com.moyu.reader.reader.PageTextComposer.layoutOf(
        pageText = pageText,
        pageStart = pageStart,
        indentEm = settings.indentEm,
        paragraphSpacingMultiplier = settings.paragraphSpacingMultiplier,
        startsMidParagraph = startsMidParagraph,
    )

    val indent = if (layout.indentLength > 0) "\u3000".repeat(layout.indentLength) else ""
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()

    layout.lines.forEachIndexed { index, span ->
        if (index > 0) repeat(layout.paragraphBreaks) { builder.append("\n") }
        // 与 PageTextComposer.render 保持同一条规则：首段若从段中间开始，
        // renderStart 就是 0，不加缩进。两处一旦不一致，渲染就比量出来的长。
        if (indent.isNotEmpty() && span.renderStart > 0) builder.append(indent)

        val contentStart = builder.length
        builder.append(span.text)

        // 划线高亮：把章内绝对区间换算成当前页内相对位置。
        // 基准是 span.sourceStart（**源文本**偏移），不能用 builder.length ——
        // 缩进与空行只存在于渲染结果里，用渲染下标换算会累积漂移。
        highlights.forEach { range ->
            val from = (range.first - span.sourceStart).coerceIn(0, span.length)
            val to = (range.last + 1 - span.sourceStart).coerceIn(0, span.length)
            if (to > from) {
                builder.addStyle(
                    SpanStyle(background = Color(0x338A6A46)),
                    contentStart + from,
                    contentStart + to,
                )
            }
        }
    }
    return builder.toAnnotatedString()
}


/**
 * 正文文本样式。分页计算与实际渲染必须用同一套参数。
 *
 * `includeFontPadding = false` 与 `PaginationEngine.buildLayout` 里的
 * `setIncludePad(false)` 是**成对**的，缺一边就会出错：
 *
 * Compose 的 `Text` 默认包含字体 padding（字形上下留白），而 StaticLayout
 * 那边不含。两侧不一致时，分页量出的高度比实际渲染的小，
 * 真机上表现为「最后一行被裁掉、只剩字顶一点」。
 *
 * 更隐蔽的是它**只在某些字体度量下才暴露**：正文行高是
 * `fontSize × 行距倍数`（默认约 1.7 倍字号），比字体自然行高大不少，
 * 于是差异经常被掩盖过去。一旦用户把行距调到 1.0 附近，
 * 被掩盖的量就不够了 —— 报障现象正是这样出现的。
 *
 * 两侧都关掉字体 padding 后，行盒就等于「行高」，不再依赖字体度量的巧合。
 */
private fun bodyTextStyle(settings: com.moyu.reader.data.prefs.ReaderSettings): TextStyle = TextStyle(
    fontFamily = fontFamilyFor(settings.fontFamily),
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
    letterSpacing = settings.letterSpacingEm.sp,
    /**
     * 字重。
     *
     * 与分页引擎的 `typefaceStyleFor` 是同一条规则的两个表达：
     * 从同一个 FontFamilyId 推导，两侧必须一致，否则换行位置不同、
     * 分页与显示错位。`settings.bold`（用户单独勾选的加粗）与
     * 字体本身自带的字重取「更粗的那个」。
     */
    fontWeight = if (settings.bold) {
        FontWeight.Medium
    } else {
        com.moyu.reader.ui.theme.fontWeightFor(settings.fontFamily)
    },
    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
    /**
     * 断行策略。
     *
     * 默认的 `LineBreak.Simple` 允许在**任意字符之间**断行，
     * 于是一句完整的句子会被从中间切开 —— 用户反馈的
     * 「为什么书籍分页会把一句完整的句子从中间切开分到两页」正是这个原因。
     *
     * `LineBreak.Paragraph` 会遵守基本排版规则（标点不落行首、优先在标点与
     * 词边界处断），中文阅读体验接近纸质书。
     * 分页引擎用的是 `BREAK_STRATEGY_HIGH_QUALITY`，两者同为「质量优先」，
     * 因此分页算出的行尾与真正画出来的行尾一致。
     */
    lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    /**
     * 行高必须**显式等于**分页引擎使用的那一个值。
     *
     * ## 为什么不能让 Compose 自己按字体度量算
     *
     * 分页引擎那边用 `setLineSpacing(0f, 1f)`，行距由**字体的自然行高**决定
     * （CJK 字体通常是字号的 1.15~1.2 倍）；而这里给的是
     * `字号 × lineHeightMultiplier`（默认约 1.6，明显更大）。
     *
     * 两者一旦不同，同一个 `contentHeight` 里「引擎以为能放 10 行、
     * 实际画出来 10 行占的高度远超容器」——分页的回退循环于是不断减行，
     * 最终每页实际用掉的高度远小于容器，**底部空出一大块**，
     * 而句子又因为断点跟着回退而落在奇怪的位置。
     *
     * 这一条与分页引擎里的 `lineHeightPx = textSizePx * lineHeightMultiplier`
     * 是同一个公式，改一处必须改另一处。
     */
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
    ),
)

/** 滚动阅读模式。 */
@Composable
private fun ScrollReader(
    content: String,
    chapterTitle: String,
    chapterNumberLabel: String,
    highlights: List<IntRange>,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    /** 取版心实测高度用 —— 自动滚动的速度必须与分页模式同口径。 */
    viewModel: ReaderViewModel,
    /** 自动阅读是否开启。开启时按「秒/页」匀速向下滚动。 */
    autoReading: Boolean = false,
    /** 滚到本章末尾时回调，交给调用方决定是否进入下一章。 */
    onReachEnd: () -> Unit = {},
) {
    val palette = moyuPalette()
    val scrollState = rememberScrollState()
    val safeTop = com.moyu.reader.ui.safeTop
    val safeBottom = com.moyu.reader.ui.safeBottom

    /**
     * 恢复上次读到的位置。
     *
     * 滚动模式没有「页」，位置只能按比例恢复：上次读到的章内偏移 / 本章长度。
     * 不做这件事的话，用户滚着读了半章，退出再进来会回到章首 ——
     * 而页码却显示着他已经读到的地方，两边对不上。
     */
    LaunchedEffect(content, scrollState.maxValue) {
        if (scrollState.maxValue <= 0) return@LaunchedEffect
        val target = (viewModel.scrollStartFraction() * scrollState.maxValue).toInt()
        if (target > 0) scrollState.scrollTo(target)
    }

    /**
     * 把滚动位置折算成章内偏移上报给 ViewModel。
     *
     * 只在「滚过约两行」时才上报：逐帧上报会让 ViewModel 每帧改一次
     * StateFlow，阅读页跟着重组，滚动反而变卡。落后两行的进度
     * 对持久化来说完全够用。
     */
    LaunchedEffect(content) {
        var lastReported = -1000
        androidx.compose.runtime.snapshotFlow { scrollState.value }.collect { value ->
            if (scrollState.maxValue <= 0) return@collect
            if (kotlin.math.abs(value - lastReported) < 160) return@collect
            lastReported = value
            val offset = (value.toFloat() / scrollState.maxValue * content.length).toInt()
            viewModel.setScrollChapterOffset(offset.coerceIn(0, (content.length - 1).coerceAtLeast(0)))
        }
    }

    // 滚动容器的可视高度。自动阅读每次推进「一屏」，因此需要它。
    // 用 BoxWithConstraints 而不是 onSizeChanged：后者要么多一个 state、
    // 要么在首帧拿不到值，而自动阅读恰恰在开启的第一秒就要用。
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = settings.marginDp.dp),
    ) {
        /**
         * 自动滚动的速度基准：**一屏文字的高度**，而不是整个可视区高度。
         *
         * ## 为什么必须与分页模式口径一致
         *
         * 「秒/页」这个设置的含义是「读完一页需要多少秒」。
         * 分页模式下「一页」= 版心能容纳的文字高度；滚动模式并没有「页」，
         * 但用户对速度的预期是同一个 —— 同样设 9 秒，
         * 滚动模式每秒应当流过**与翻一页相同的文字量**。
         *
         * 第一版这里用的是整个可视高度（`maxHeight - 安全区`），
         * 而可视区还包含天头、地脚与安全区留白，比版心高出一截 ——
         * 于是滚动模式实际读得更快，两个模式的「9 秒」速度对不上。
         *
         * 版心高度由分页引擎实测上报（与分页模式共用同一个值），
         * 已扣掉页边距、系统栏与地脚，因此两边口径天然一致。
         */
        val pageTextHeightPx = viewModel.lastContentBoxHeight().takeIf { it > 0 }
            ?: with(androidx.compose.ui.platform.LocalDensity.current) {
                (maxHeight - safeTop - safeBottom).toPx().toInt()
            }

        /**
         * 自动阅读：**匀速连续滚动**，像提词器一样。
         *
         * ## 为什么不是「每秒推一屏」
         *
         * 第一版写的是 `delay(秒数) → animateScrollTo(当前位置 + 一屏)`。
         * 那是「跳」而不是「滚」：每过 N 秒画面突然滑一整屏，
         * 眼睛要重新定位，读起来很累 —— 用户的反馈正是这一点。
         *
         * 正确做法是把「一屏」拆到整个时间段里：每帧推进
         * `一屏 / (秒数 × 帧率)`。这样文字是**持续缓慢流过**的，
         * 视线可以一直跟着走，这才是滚动模式该有的自动阅读。
         *
         * 用 `withFrameNanos` 而不是固定 delay：帧率由系统决定（60/90/120Hz），
         * 按固定毫秒推进会让高刷屏上速度快一倍。
         */
        LaunchedEffect(autoReading, settings.autoReadSecondsPerPage, pageTextHeightPx) {
            if (!autoReading || pageTextHeightPx <= 0) return@LaunchedEffect

            val seconds = settings.autoReadSecondsPerPage.coerceIn(2, 120).toFloat()
            // 每秒滚过的像素 = 一屏文字高度 / 秒数。
            // 与分页模式「每 N 秒翻一页」的读速严格等价。
            val pxPerSecond = pageTextHeightPx / seconds
            // 用累加的小数位置避免整数截断导致「高刷屏几乎不动」
            var carried = 0f
            var lastNanos = 0L

            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (lastNanos != 0L) {
                        val dt = (now - lastNanos) / 1_000_000_000f
                        // 单帧最多推进 0.1 秒的量：从后台切回来时 dt 可能是几秒，
                        // 不限制的话会瞬间跳过大段文字
                        carried += pxPerSecond * dt.coerceAtMost(0.1f)
                    }
                    lastNanos = now
                }
                if (carried >= 1f) {
                    val step = carried.toInt()
                    carried -= step
                    val next = (scrollState.value + step).coerceAtMost(scrollState.maxValue)
                    scrollState.scrollTo(next)
                }
                // 到达本章末尾：交给调用方决定是否进入下一章，本次自动阅读到此为止
                if (scrollState.value >= scrollState.maxValue && scrollState.maxValue > 0) {
                    kotlinx.coroutines.delay(600)
                    onReachEnd()
                    break
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                /**
                 * 纵向留白取「页边距」与「安全区」的较大值。
                 *
                 * 注意这两条 padding 写在 `verticalScroll` **之后**，
                 * 也就是它们属于**滚动内容**的一部分：
                 *   - 顶部留白会随内容一起滚走，不会永远占着一条空白；
                 *   - 底部留白在滚到底时把最后一行托在手势条**之上** ——
                 *     这正是用户报的「滚动底部手势条的保护没了」那一条。
                 *
                 * 若把 padding 写在 `verticalScroll` 之前，它就成了容器的内边距：
                 * 内容会从留白下面穿过去，最后一行永远压在系统手势条上。
                 *
                 * 横向页边距由外层 BoxWithConstraints 统一加（只加一次，
                 * 两处都加会让版心窄掉一半）。
                 */
                .padding(
                    top = maxOf(settings.marginDp.dp * 0.9f, safeTop),
                    bottom = maxOf(settings.marginDp.dp * 0.9f, safeBottom),
                ),
        ) {
            /**
             * 章首那一行标题。
             *
             * 分章时标题行被保留在正文里（重切要能无损拼回全文），
             * 所以滚动模式下要么把它当正文首段（缩进两格、和普通段落一样），
             * 要么当标题排版 —— 后者才是书的体例。这里按标题排，
             * 同时把它从正文里去掉，避免同一句话出现两次。
             */
            val headingSkip = com.moyu.reader.reader.ChapterSplitter
                .headingSkipLength(content, chapterTitle)
            val body = if (headingSkip > 0) content.substring(headingSkip) else content

            if (headingSkip > 0 && chapterTitle.isNotBlank()) {
                Text(
                    text = chapterNumberLabel,
                    style = furnitureStyle(settings),
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = fontFamilyFor(settings.fontFamily),
                    ),
                    color = palette.text,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            Text(
                text = buildPageText(body, settings, highlights, headingSkip),
                style = bodyTextStyle(settings).copy(color = palette.text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 电量图标：一个电池轮廓 + 按容量填充的内部条。
 *
 * 为什么自己画而不是用矢量资源：这只是几何图形，
 * Canvas 画出来不到二十行，比新增 drawable 资源 + 若干密度变体更轻，
 * 而且填充比例能直接跟着真实电量走（用静态图标就只能按档位切换）。
 *
 * `percent < 0` 表示尚未拿到电量（广播还没回来），此时只画空壳不画填充，
 * 不会显示成 0% 让人误以为没电了。
 */
@Composable
private fun BatteryGlyph(
    percent: Int,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(
        modifier = modifier.then(Modifier.size(20.dp, 11.dp)),
    ) {
        val stroke = 1.dp.toPx()
        val capWidth = 2.dp.toPx()
        val bodyWidth = this.size.width - capWidth

        // 电池外壳
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(bodyWidth - stroke, this.size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        // 正极凸起
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(bodyWidth, this.size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(capWidth, this.size.height * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
        )

        if (percent < 0) return@Canvas

        // 内部填充：留 2dp 内边距，比例跟着电量走
        val inset = 2.dp.toPx()
        val innerMax = bodyWidth - inset * 2
        val filled = (innerMax * (percent.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
        if (filled <= 0f) return@Canvas

        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(filled, this.size.height - inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
        )
    }
}

/**
 * 地脚显示的当前时间，形如 `14:05`。
 *
 * 用 24 小时制而不是跟随系统设置：地脚宽度固定，`下午 2:05` 这种带前缀的
 * 格式会明显更宽，在窄屏上容易与左侧页码挤在一起。
 */
private fun currentClockText(): String {
    val now = java.util.Calendar.getInstance()
    val h = now.get(java.util.Calendar.HOUR_OF_DAY)
    val m = now.get(java.util.Calendar.MINUTE)
    return String.format("%02d:%02d", h, m)
}

/** 章节序号标签。规则见 ChapterLabels —— 与 ViewModel 的页快照共用同一实现。 */
private fun chapterNumberLabel(title: String, index: Int): String =
    com.moyu.reader.reader.ChapterLabels.labelFor(title, index)

/** 估算全书进度（用于页脚百分比）。 */
private fun viewModelPercent(
    pages: List<com.moyu.reader.reader.PaginationEngine.Page>,
    headers: List<com.moyu.reader.data.model.ChapterHeader>,
    chapterIndex: Int,
    pageIndex: Int,
): Float {
    if (headers.isEmpty()) return 0f
    val total = headers.sumOf { it.length }.coerceAtLeast(1)
    val before = headers.take(chapterIndex).sumOf { it.length }
    val offsetInChapter = pages.getOrNull(pageIndex)?.start ?: 0
    return ((before + offsetInChapter).toFloat() / total).coerceIn(0f, 1f)
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("墨鱼阅读", text))
}
