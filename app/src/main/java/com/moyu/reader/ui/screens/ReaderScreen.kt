package com.moyu.reader.ui.screens

import com.moyu.reader.ui.theme.moyuPalette

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ThemeId
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ReaderViewModel
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.theme.moyuPalette
import com.moyu.reader.ui.theme.fontFamilyFor

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
) {
    val viewModel: ReaderViewModel = viewModel(factory = factory)
    val density = LocalDensity.current
    val context = LocalContext.current
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

    var chromeVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }

    val palette = moyuPalette()
    val chapterContent = chapter?.content.orEmpty()
    val currentPage = pages.getOrNull(pageIndex)

    // 打开书籍：视口尺寸要等布局完成才知道，因此这里先触发一次 open
    LaunchedEffect(bookId) {
        viewModel.open(bookId, density)
        viewModel.resumeSession()
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

    // 切后台时也要结算，否则「读了两小时后切走」会丢掉这段时长
    DisposableEffect(context) {
        val activity = context as? android.app.Activity
        onDispose { }
    }

    // 音量键翻页
    if (settings.volumeKeyPaging) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // 由 Activity 的按键分发处理（见 MainActivity 的 dispatchKeyEvent）
        }
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
                    pageLabel = "${pageIndex + 1}/${pages.size.coerceAtLeast(1)}",
                    percentLabel = "${(viewModelPercent(pages, chapterHeaders, chapterIndex, pageIndex) * 100).toInt()}%",
                    highlights = highlights,
                    flipToken = 0,
                )
            }
        }

        // —— 手势层 ——
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings.pageMode, pages.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // 水平滑动幅度交给 tap 手势判断，这里只做收尾
                        },
                    ) { _, dragAmount ->
                        if (dragAmount < -60) {
                            viewModel.flip(1, density)
                        } else if (dragAmount > 60) {
                            viewModel.flip(-1, density)
                        }
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
    pageLabel: String,
    percentLabel: String,
    highlights: List<IntRange>,
    flipToken: Int,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation
                translationX = translation
                alpha = progress.coerceIn(0f, 1f)
                cameraDistance = 18f * density
            }
            .padding(
                horizontal = settings.marginDp.dp,
                vertical = (settings.marginDp * 0.9f).dp,
            ),
    ) {
        // 正文
        Box(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                Column {
                    if (showTitle) {
                        Text(
                            text = chapterNumberLabel,
                            style = MaterialTheme.typography.labelSmall,
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
                        text = buildPageText(pageText, settings, highlights, pageStart),
                        style = bodyTextStyle(settings),
                        color = palette.text,
                    )
                }
            }
        }

        // 页脚：章节名 + 页码 + 进度
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .alpha(0.9f),
            )
            if (settings.showPageNumber) {
                Text(
                    text = "$pageLabel · $percentLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

/** 组装当前页的文本。逐段缩进 + 划线高亮。 */
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

    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    var cursor = pageStart
    val lines = pageText.split('\n')

    lines.forEachIndexed { index, rawLine ->
        // 行首空白统一剥掉：否则会与首行缩进叠加成双倍缩进
        val leading = rawLine.length - rawLine.trimStart().length
        val line = rawLine.trimStart()
        val lineStart = cursor + leading

        if (index > 0) builder.append("\n")
        if (line.isNotEmpty()) {
            if (indent.isNotEmpty()) builder.append(indent)
            val contentStart = builder.length
            builder.append(line)

            // 划线高亮：把章内绝对区间换算成当前页内相对位置
            highlights.forEach { range ->
                val from = (range.first - lineStart).coerceIn(0, line.length)
                val to = (range.last + 1 - lineStart).coerceIn(0, line.length)
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
                    .padding(vertical = (settings.marginDp * 0.9f).dp),
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
