package com.moyu.reader.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ThemeId
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ReaderViewModel
import com.moyu.reader.ui.components.IconAction
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
    onOpenNotes: (String) -> Unit,
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
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val dictResult by viewModel.dictResult.collectAsStateWithLifecycle()
    val dictLoading by viewModel.dictLoading.collectAsStateWithLifecycle()
    val autoReading by viewModel.autoReading.collectAsStateWithLifecycle()
    val ttsState by viewModel.tts.state.collectAsStateWithLifecycle()
    val highlights by viewModel.chapterHighlights.collectAsStateWithLifecycle()
    // 翻页动画的触发令牌（每次翻页自增）。不订阅它就等于关掉了所有翻页动效。
    val flipToken by viewModel.flipToken.collectAsStateWithLifecycle()

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
                    highlights = highlights,
                    settings = settings,
                )
            } else if (currentPage != null) {
                PagedReader(
                    content = chapterContent,
                    pageStart = currentPage.start,
                    pageEnd = currentPage.end,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterNumberLabel = chapterNumberLabel(chapter?.title.orEmpty(), chapterIndex),
                    showTitle = currentPage.start == 0,
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

        // —— 手势层 ——
        //
        // 只在**分页模式**下注册：滚动模式的左右两半可以正常拖动/选中文字，
        // 若也在这里响应点击，用户想点一下屏幕就会静默跳到下一章，
        // 而屏幕上显示的还是滚动正文 —— 进度与实际脱节，且毫无提示。
        if (settings.pageMode != PageMode.SCROLL) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    /**
                     * 水平滑动翻页。
                     *
                     * 必须判断**累计位移**而不是单个事件的增量：
                     * `dragAmount` 是每次 move 事件的位移，一次正常滑动会产生很多个
                     * 超过阈值的 move，于是手指一划就连翻好几页。
                     * 这里累加到 onDragEnd 时一次性判定，并且只翻一页。
                     */
                    .pointerInput(settings.pageMode, pages.size) {
                        var accumulated = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f },
                            onDragEnd = {
                                // 60dp 的滑动才算翻页：像素阈值在不同密度上手感差异太大
                                val threshold = 60.dp.toPx()
                                when {
                                    accumulated <= -threshold -> viewModel.flip(1, density)
                                    accumulated >= threshold -> viewModel.flip(-1, density)
                                }
                                accumulated = 0f
                            },
                        ) { _, dragAmount ->
                            accumulated += dragAmount
                        }
                    }
                    .pointerInput(settings.pageMode) {
                        detectTapGestures(
                            onTap = { offset ->
                                val width = size.width
                                val third = width / 3f
                                when {
                                    offset.x < third -> {
                                        if (!chromeVisible) viewModel.flip(-1, density)
                                    }

                                    offset.x > third * 2 -> {
                                        if (!chromeVisible) viewModel.flip(1, density)
                                    }

                                    else -> {
                                        chromeVisible = !chromeVisible
                                        if (!chromeVisible) sheet = ReaderSheet.NONE
                                    }
                                }
                            },
                            onLongPress = { },
                        )
                    },
            )
        }

        // —— 顶部栏 ——
        if (chromeVisible) {
            ReaderTopBar(
                bookTitle = book?.title.orEmpty(),
                chapterTitle = chapter?.title.orEmpty(),
                speaking = ttsState == com.moyu.reader.reader.TtsController.State.SPEAKING,
                onBack = {
                    viewModel.persistNow()
                    onExit()
                },
                onSpeak = { viewModel.toggleSpeech(density) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // —— 底部工具栏 ——
        if (chromeVisible) {
            ReaderBottomBar(
                chapterIndex = chapterIndex,
                chapterCount = chapterHeaders.size,
                onChapterSeek = { index -> viewModel.jumpToChapter(index, density) },
                autoReading = autoReading,
                isNight = settings.theme == ThemeId.NIGHT,
                onToc = { sheet = if (sheet == ReaderSheet.TOC) ReaderSheet.NONE else ReaderSheet.TOC },
                onNotes = { sheet = if (sheet == ReaderSheet.NOTES) ReaderSheet.NONE else ReaderSheet.NOTES },
                onToggleNight = { viewModel.quickToggleNight() },
                onToggleAuto = { viewModel.toggleAutoRead(density) },
                onSearch = { onOpenSearch(bookId) },
                onTypography = { sheet = if (sheet == ReaderSheet.TYPOGRAPHY) ReaderSheet.NONE else ReaderSheet.TYPOGRAPHY },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // —— 选中文字的浮动操作条 ——
        if (selection != null) {
            SelectionActions(
                selectionText = selection!!.text,
                onLookup = { viewModel.lookupSelection() },
                onBookmark = { viewModel.addBookmarkAtSelection() },
                onHighlight = { color, note -> viewModel.addHighlightFromSelection(note, color) },
                onCopy = { copyToClipboard(context, selection!!.text) },
                onDismiss = { viewModel.clearSelection() },
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

            ReaderSheet.NOTES -> ReaderNotesSheet(
                viewModel = viewModel,
                onJump = { chapterIdx, offset ->
                    viewModel.jumpToChapter(chapterIdx, density, offset)
                    sheet = ReaderSheet.NONE
                },
                onOpenAll = { onOpenNotes(bookId) },
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
private enum class ReaderSheet { NONE, TOC, NOTES, TYPOGRAPHY }

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
        animationSpec = tween(durationMillis = if (settings.pageMode == PageMode.NONE) 0 else 320),
        label = "pageFlip",
    )

    // 动画进行中才画旧页。画完就撤掉，否则它会一直叠在下面白耗一层合成。
    val animating = entered && progress < 0.999f
    val outgoing = viewModel.outgoingPage.collectAsStateWithLifecycle().value
    val forward by viewModel.forward.collectAsStateWithLifecycle()

    // 按屏宽算位移量：三种模式的位移都应当以「一屏」为单位，
    // 而不是写死 80/240 这类像素值 —— 那在小屏上会显得没动、大屏上又太夸张。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val pageWidthPx = with(uiDensity) { screenWidthDp.dp.toPx() }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBatteryPercent provides batteryPercent,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            /**
             * 即将翻走的那一页，画在下层。
             *
             * 它存在与否，正是「三种模式看起来不一样」的关键：
             * 只有一页在场时，无论新页怎么位移/旋转都不像翻页 ——
             * 因为真实的翻页里，旧页要么被推走、要么留在原地被盖住、要么被掀起。
             */
            if (animating && outgoing != null) {
                val g = pageGeometry(
                    mode = settings.pageMode,
                    progress = progress,
                    incoming = false,
                    forward = forward,
                    widthPx = pageWidthPx,
                )
                ReaderPage(
                    text = outgoing.text,
                    pageNumber = outgoing.pageNumber,
                    showTitle = outgoing.showTitle,
                    chapterTitle = outgoing.chapterTitle,
                    chapterNumberLabel = outgoing.chapterNumberLabel,
                    settings = settings,
                    highlights = highlights,
                    pageStart = outgoing.pageStart,
                    percent = percent,
                    viewModel = viewModel,
                    uiDensity = uiDensity,
                    // 旧页不上报尺寸：两页尺寸一致时来回覆盖没有意义，
                    // 不一致时更会把分页测量值搞乱
                    onContentSize = null,
                    modifier = Modifier.graphicsLayer {
                        translationX = g.translationX
                        rotationY = g.rotationY
                        scaleX = g.scaleX
                        alpha = g.alpha
                        cameraDistance = 20f * uiDensity.density
                    },
                )
            }

            // 即将翻到的那一页，画在上层
            val incomingGeometry = pageGeometry(
                mode = settings.pageMode,
                progress = if (entered) progress else 0f,
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
        }
    }
}
/**
 * 把「渲染文本里的下标」换算回「章内字符偏移」。
 *
 * ## 为什么不能直接 `pageStart + offset`
 *
 * [buildPageText] 在渲染时会**插入内容**：每段首加全角缩进、段落之间补空行。
 * 于是渲染文本比源文本长，两者的下标不是一一对应 ——
 * 直接相加会随段数累积漂移，越往后偏得越多。
 * 用户长按一句想划线，结果划到的是别处。
 *
 * 这里不靠估算，而是**重新走一遍与 buildPageText 相同的拼接过程**，
 * 同时记录每个渲染下标对应的源下标。规则只有一处（两处必须同步修改），
 * 但因为用的是同一套拼接顺序，结果与渲染完全一致。
 *
 * @param pageText    这一页的源文本切片（章内 `[pageStart, pageEnd)`）
 * @param pageStart   这一页在章内的起始偏移
 * @param settings    排版设置（缩进与段间距会影响插入量）
 * @param renderOffset 渲染文本里的下标（来自 TextLayoutResult）
 * @return 章内字符偏移；越界时返回 null
 */
private fun renderOffsetToChapterOffset(
    pageText: String,
    pageStart: Int,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
    renderOffset: Int,
): Int? {
    if (renderOffset < 0) return null

    val indentLen = if (settings.indentEm > 0f) {
        settings.indentEm.toInt().coerceIn(0, 4)
    } else {
        0
    }
    val paragraphBreaks = settings.paragraphSpacingMultiplier
        .coerceIn(0.5f, 3f)
        .toInt()
        .coerceAtLeast(1)

    var render = 0
    var cursor = pageStart
    var firstEmitted = true

    for (rawLine in pageText.split('\n')) {
        val line = rawLine.trimStart()
        val leading = rawLine.length - line.length
        val sourceLineStart = cursor + leading

        if (line.isNotEmpty()) {
            if (!firstEmitted) {
                // 段落之间的空行：落在这些换行上就归到本段开头
                if (renderOffset < render + paragraphBreaks) return sourceLineStart
                render += paragraphBreaks
            }
            firstEmitted = false

            // 缩进：落在缩进上就归到本段开头
            if (indentLen > 0) {
                if (renderOffset < render + indentLen) return sourceLineStart
                render += indentLen
            }

            // 正文：落在这一段内，就是精确对应
            val lineEnd = render + line.length
            if (renderOffset < lineEnd) {
                return sourceLineStart + (renderOffset - render)
            }
            render = lineEnd
        }

        cursor += rawLine.length + 1
    }

    // 落在文本末尾（长按最后一行的空白区）时归到最后一个字符
    return (pageStart + pageText.length - 1).takeIf { it >= pageStart }
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
        // 平移：两页一起沿横向推移，像卷轴。旧页左移出新页右移入。
        com.moyu.reader.data.prefs.PageMode.SLIDE -> {
            val offset = remaining * widthPx * dir
            if (incoming) PageGeometry(offset, 0f, 1f, 1f)
            else PageGeometry(-widthPx * dir + offset, 0f, 1f, 1f)
        }

        // 覆盖：旧页**完全不动**，新页从右侧盖上来。
        // 「不动」正是覆盖与平移的本质区别 —— 平移时旧页会跟着走。
        com.moyu.reader.data.prefs.PageMode.COVER -> {
            if (incoming) PageGeometry(remaining * widthPx * dir, 0f, 1f, 1f)
            else PageGeometry(0f, 0f, 1f, 1f)
        }

        // 仿真：新页绕左缘旋转，旧页反向小幅旋转，像把纸掀起来。
        // 透视靠 cameraDistance（在 graphicsLayer 里设），缺了它只会看到横向压缩。
        com.moyu.reader.data.prefs.PageMode.SIMULATION -> {
            if (incoming) {
                PageGeometry(0f, -remaining * 90f * dir, 1f, 1f)
            } else {
                PageGeometry(0f, remaining * 14f * dir, 1f, 1f)
            }
        }

        // 无动画：不做任何变换，靠 alpha 收尾避免闪一下
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
        // 按真实书籍体例，书眉只出现在次页起。章首页的标题本身就在版心内，
        // 顶上再压一条书眉就是同一句话印两遍。
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
                    .alpha(0.85f),
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
            SelectionContainer {
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
                     * 正文。长按取词 → 弹出标注操作条。
                     *
                     * `onTextLayout` 拿到 [androidx.compose.ui.text.TextLayoutResult]，
                     * 长按时用它把**触摸坐标**换算成**字符下标** ——
                     * 这是唯一可靠的方式：自己按字号估算位置會在换行、标点避头尾
                     * 等情况下偏掉，用户长按到的词和实际选中的词对不上。
                     *
                     * 下标要再加上 `pageStart` 才是**章内偏移**（书签与笔记存的坐标系）。
                     */
                    var bodyLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                    Text(
                        text = buildPageText(text, settings, highlights, pageStart),
                        style = bodyTextStyle(settings),
                        color = palette.text,
                        onTextLayout = { bodyLayout = it },
                        modifier = Modifier.pointerInput(text, pageStart, settings.fontSizeSp) {
                            detectTapGestures(
                                onLongPress = { pos ->
                                    val layout = bodyLayout ?: return@detectTapGestures
                                    val offset = layout.getOffsetForPosition(pos)
                                    // 用真实映射换算回章内偏移 ——
                                    // 直接 pageStart + offset 会因为缩进与段落空行而漂移
                                    val chapterOffset =
                                        com.moyu.reader.reader.PageTextComposer
                                            .renderOffsetToChapterOffset(
                                                pageText = text,
                                                pageStart = pageStart,
                                                indentEm = settings.indentEm,
                                                paragraphSpacingMultiplier =
                                                settings.paragraphSpacingMultiplier,
                                                renderOffset = offset,
                                            )
                                    if (chapterOffset != null) {
                                        viewModel.selectWordAt(chapterOffset)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        // —— 地脚：左页码、右时间与电量 ——
        //
        // 高度固定，不随内容变化：正文区是 weight(1f)，拿到的是
        // 「本 Column 扣掉其他子项之后」的空间。地脚高度一旦跳动
        // （例如电量从 100% 变 99%），正文区高度就跟着变、触发重新分页，
        // 表现为读到一半突然重排。给死高度后测量值稳定，分页也就稳定。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FOOTER_HEIGHT)
                .padding(top = 10.dp),
        ) {
            if (settings.showPageNumber) {
                Text(
                    text = pageNumber.toString(),
                    style = furnitureStyle,
                    color = palette.textSecondary,
                    modifier = Modifier.align(Alignment.CenterStart).alpha(0.85f),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentClockText(),
                    style = furnitureStyle,
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
): androidx.compose.ui.text.AnnotatedString {
    /**
     * 排版布局来自 [PageTextComposer.layoutOf] —— 与长按取词的下标换算
     * **共用同一个实现**。
     *
     * 这一点很关键：早先这里自己写了一遍拼接（插缩进、补空行），
     * 而长按处按「页起始 + 渲染下标」估算源位置 —— 两套规则必然漂移，
     * 表现为「长按划线的位置越往后越偏」。现在只有一处规则。
     */
    val layout = com.moyu.reader.reader.PageTextComposer.layoutOf(
        pageText = pageText,
        pageStart = pageStart,
        indentEm = settings.indentEm,
        paragraphSpacingMultiplier = settings.paragraphSpacingMultiplier,
    )

    val indent = if (layout.indentLength > 0) "\u3000".repeat(layout.indentLength) else ""
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()

    layout.lines.forEachIndexed { index, span ->
        if (index > 0) repeat(layout.paragraphBreaks) { builder.append("\n") }
        if (indent.isNotEmpty()) builder.append(indent)

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
    fontWeight = if (settings.bold) FontWeight.Medium else FontWeight.Normal,
    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
)

/** 滚动阅读模式。 */
@Composable
private fun ScrollReader(
    content: String,
    highlights: List<IntRange>,
    settings: com.moyu.reader.data.prefs.ReaderSettings,
) {
    val palette = moyuPalette()
    val scrollState = rememberScrollState()
    val safeTop = com.moyu.reader.ui.safeTop
    val safeBottom = com.moyu.reader.ui.safeBottom

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = settings.marginDp.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // 滚动模式同样要让开系统栏：否则首行压状态栏、末行压手势条。
                    // 纵向取「页边距」与「安全区」的较大值，与分页模式口径一致。
                    .padding(
                        top = maxOf(settings.marginDp.dp * 0.9f, safeTop),
                        bottom = maxOf(settings.marginDp.dp * 0.9f, safeBottom),
                    ),
            ) {
                Text(
                    text = buildPageText(content, settings, highlights, 0),
                    style = bodyTextStyle(settings),
                    color = palette.text,
                )
            }
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
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Reader", text))
}
