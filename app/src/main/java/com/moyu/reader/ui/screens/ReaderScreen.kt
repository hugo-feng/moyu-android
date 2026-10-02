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
                    text = "自动阅读中 · ${settings.autoReadSpeed} 字/秒",
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

    // 翻页动画：以 flipToken 为 key 重新触发一次入场动画
    var entered by remember(flipToken) { mutableStateOf(false) }
    LaunchedEffect(flipToken) {
        entered = false
        androidx.compose.runtime.withFrameNanos { }
        entered = true
    }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = if (settings.pageMode == PageMode.NONE) 0 else 300),
        label = "pageFlip",
    )

    val rotation = when (settings.pageMode) {
        PageMode.SIMULATION -> (1f - progress) * 22f
        else -> 0f
    }
    val translation = when (settings.pageMode) {
        PageMode.SLIDE -> (1f - progress) * 80f
        PageMode.COVER -> (1f - progress) * 240f
        else -> 0f
    }

    // 天头书眉 / 地脚页码用的字号：正文的 0.62 倍，与 Web 端保持同一比例。
    val furnitureStyle = TextStyle(
        fontFamily = fontFamilyFor(settings.fontFamily),
        fontSize = (settings.fontSizeSp * 0.62f).sp,
        lineHeight = (settings.fontSizeSp * 0.62f * 1.4f).sp,
        letterSpacing = settings.letterSpacingEm.sp,
    )

    val safeTop = com.moyu.reader.ui.safeTop
    val safeBottom = com.moyu.reader.ui.safeBottom

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = rotation
                    translationX = translation
                    alpha = progress.coerceIn(0f, 1f)
                    cameraDistance = 18f * density
                }
                .padding(horizontal = settings.marginDp.dp)
                // 纵向留白取「页边距」与「系统栏」的较大值：
                // 页边距调大时仍然生效，而小米 14 这类高状态栏机型上也不会被压住。
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

            // —— 版心：正文 —— 上下留白大于左右，形成书籍的不对称版心
            //
            // clipToBounds 是必须的：Column 的测量高度不受 Box 约束，
            // 一旦分页多排了一行，文字会**画到地脚页码上面**甚至越出页面。
            // 裁掉之后溢出表现为「少一行」而不是「糊成一团」，问题更容易被发现。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { size ->
                        // 分页的纵向可用高度取这里的实测值：Box 已经把页边距、
                        // 系统栏安全区与地脚页码全部扣掉，因此它与真实版心天然一致。
                        viewModel.setContentBoxSize(size.width, size.height, uiDensity)
                    },
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
                        Text(
                            text = buildPageText(pageText, settings, highlights, pageStart),
                            style = bodyTextStyle(settings),
                            color = palette.text,
                        )
                    }
                }
            }

            // —— 地脚：页码居中 ——
            // 真书的页码只是一个数字，不带章节名、不带百分比。
            // 「还有多久读完」交给下面那条贴页缘的细线 —— 它不占版心。
            if (settings.showPageNumber) {
                Text(
                    text = pageNumber.toString(),
                    style = furnitureStyle,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .alpha(0.85f),
                )
            }
        }

        // —— 全书进度：一条 1dp 细线，贴页的下缘 ——
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

/**
 * 组装当前页的文本。逐段缩进 + 段落间距 + 划线高亮。
 *
 * 两个容易出错的点：
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
    val indent = if (settings.indentEm > 0f) {
        "\u3000".repeat(settings.indentEm.toInt().coerceIn(0, 4))
    } else {
        ""
    }

    // 段落之间插入的换行数：1 即维持原来的单空行，更大则更松散
    val paragraphBreaks = settings.paragraphSpacingMultiplier
        .coerceIn(0.5f, 3f)
        .toInt()
        .coerceAtLeast(1)

    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    var cursor = pageStart
    val lines = pageText.split('\n')
    var firstEmitted = true

    lines.forEach { rawLine ->
        val line = rawLine.trimStart()
        val leading = rawLine.length - line.length
        // 这一行在源文本里的起始偏移（高亮换算的基准）
        val sourceLineStart = cursor + leading

        if (line.isNotEmpty()) {
            if (!firstEmitted) {
                repeat(paragraphBreaks) { builder.append("\n") }
            }
            firstEmitted = false

            if (indent.isNotEmpty()) builder.append(indent)
            val contentStart = builder.length
            builder.append(line)

            // 划线高亮：把章内绝对区间换算成当前页内相对位置
            highlights.forEach { range ->
                val from = (range.first - sourceLineStart).coerceIn(0, line.length)
                val to = (range.last + 1 - sourceLineStart).coerceIn(0, line.length)
                if (to > from) {
                    builder.addStyle(
                        SpanStyle(background = Color(0x338A6A46)),
                        contentStart + from,
                        contentStart + to,
                    )
                }
            }
        }

        cursor += rawLine.length + 1
    }
    return builder.toAnnotatedString()
}

/** 正文文本样式。分页计算与实际渲染必须用同一套参数。 */
private fun bodyTextStyle(settings: com.moyu.reader.data.prefs.ReaderSettings): TextStyle = TextStyle(
    fontFamily = fontFamilyFor(settings.fontFamily),
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
    letterSpacing = settings.letterSpacingEm.sp,
    fontWeight = if (settings.bold) FontWeight.Medium else FontWeight.Normal,
    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
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

/** 章节序号标签。楔子/番外等特殊篇名不编号。 */
private fun chapterNumberLabel(title: String, index: Int): String {
    val special = Regex("^(楔子|序章|序言|自序|前言|引子|引言|尾声|终章|完结章|后记|附录|外传|作者的话|作品相关|设定|人物介绍|番外)")
    if (special.containsMatchIn(title.trim())) return "篇外"
    if (Regex("第\\s*[0-9零一二三四五六七八九十百千万两〇]+\\s*[章节回卷节篇部集话]").containsMatchIn(title)) return "正文"
    if (Regex("^(chapter|chap\\.?|part)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return "正文"
    return "第 ${index + 1} 章"
}

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
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("墨阅", text))
}
