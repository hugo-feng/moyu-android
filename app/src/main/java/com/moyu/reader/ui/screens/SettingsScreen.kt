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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyu.reader.data.prefs.PageMode
import com.moyu.reader.data.prefs.ThemeId
import com.moyu.reader.ui.SettingsViewModel
import com.moyu.reader.ui.components.MoyuChip
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.MoyuTopBar
import com.moyu.reader.ui.components.SegmentedControl
import com.moyu.reader.ui.components.SettingDivider
import com.moyu.reader.ui.components.SettingRow
import com.moyu.reader.ui.theme.moyuPalette
import com.moyu.reader.ui.theme.paletteFor
import com.moyu.reader.ui.theme.themeDisplayName

/**
 * 设置页。
 *
 * 所有开关与滑杆都直接写入 DataStore，改动即时通过 Flow 生效，
 * 因此这里不需要「保存」按钮 —— 那反而会让用户不确定改动是否已应用。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    /**
     * 进入「导入本地书籍」页。
     *
     * 导入从书架/书库的工具栏移到了设置里：它是**低频且一次性**的动作
     * （导入完就不再用），摆在书库顶部会长期占一个显眼位置，
     * 而用户的原话是「导入本地书籍应该出现在设置页的设置项，而不是到处都是」。
     */
    onOpenImport: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val palette = moyuPalette()
    val context = androidx.compose.ui.platform.LocalContext.current

    /**
     * 导入自定义词典。
     *
     * `SettingsViewModel.importDictionary` 早就写好了，但**从来没有任何调用点**
     * —— 于是设置页里那一组「词典」永远只显示内置条数，用户词典列表永远是空的，
     * 连「删除词典」按钮都不可能被点到。这里把它接上 SAF 选择器：
     * 选一个文本文件（每行「词条 拼音 释义」），交给既有的解析与入库流程。
     */
    val dictionaryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "自定义词典"
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            viewModel.importDictionary(name, text)
        }.onFailure {
            viewModel.importDictionary("", "")
        }
    }

    var confirmClear by remember { mutableStateOf(false) }
    /** 当前打开的二级页；null 表示停在一级分类列表。 */
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    /**
     * 返回层级：二级页 → 一级分类列表 → 退出设置。
     *
     * 不做这件事的话，系统返回手势/返回键会**直接退出整个设置页**，
     * 哪怕用户只是想从「外观与主题」回到分类列表。
     * 用户的原话是「进入了二级界面后用系统返回手势，直接返回到书库了」。
     *
     * 用 `enabled` 控制拦截时机而不是在回调里判断：只有确实处在二级页时
     * 才拦截，一级页面上返回手势的手感与系统完全一致
     * （否则会吃掉本该退出设置页的那次返回）。
     */
    androidx.activity.compose.BackHandler(enabled = page != null) {
        page = null
        // 离开二级页时清掉未确认的「清除数据」状态，
        // 否则下次进来会看到上次留下的「确认清除」按钮
        confirmClear = false
    }

    LaunchedEffect(Unit) { viewModel.loadDictionaries() }

    // ================= 一级：分类入口 =================
    if (page == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.surface),
        ) {
            MoyuTopBar(title = "设置", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 30.dp),
            ) {
                Text(
                    text = "所有设置都即时生效，没有「保存」按钮。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                SettingsPage.entries.forEach { entry ->
                    CategoryCard(
                        title = entry.title,
                        summary = entry.summary(settings, dictionaries.size),
                        icon = entry.icon,
                        onClick = { page = entry },
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface),
    ) {
        MoyuTopBar(title = page!!.title, onBack = { page = null })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 30.dp),
        ) {
            // ================= 外观 =================
            if (page == SettingsPage.APPEARANCE) {
            GroupTitle("外观")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeId.entries.forEach { theme ->
                    val swatch = paletteFor(theme)
                    val active = settings.theme == theme
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(swatch.background)
                            .clickable { viewModel.setTheme(theme) }
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = themeDisplayName(theme),
                            style = MaterialTheme.typography.labelSmall,
                            color = swatch.text,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }

            SettingRow(
                label = "跟随系统深色模式",
                hint = "开启后由系统主题决定，忽略上面的选择",
                trailing = {
                    MoyuSwitch(settings.followSystemDark) { viewModel.setFollowSystemDark(it) }
                },
            )
            SettingDivider()
            SettingRow(
                label = "动态取色（跟随壁纸）",
                hint = "Android 12+ 跟随壁纸取色（仅影响界面，不影响阅读页底色）",
                trailing = {
                    MoyuSwitch(settings.dynamicColor) { viewModel.setDynamicColor(it) }
                },
            )
            SettingDivider()
            SettingRow(
                label = "护眼色温",
                hint = "整体偏暖，夜间阅读更柔和",
                trailing = {
                    Text(
                        text = if (settings.eyeCareWarmth == 0f) "关闭" else "${(settings.eyeCareWarmth * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                },
            )
            SliderRow(
                label = "",
                valueText = "",
                value = settings.eyeCareWarmth,
                range = 0f..1f,
                onValueChange = { viewModel.setEyeCare(it) },
            )
            }

            // ================= 书架与显示 =================
            //
            // 布局与排序原先摆在书架页顶部，每次进书架都看到一排标签。
            // 它们是「设一次就不动」的偏好，放进设置更合适，
            // 书架页也能保持干净（只显示书）。
            if (page == SettingsPage.SHELF) {
            GroupTitle("书架显示")

            /**
             * 导入入口。
             *
             * 放在「书架与显示」这一类的**最上面**：它是这个分类里唯一
             * 会改变「有哪些书」的动作，其余都是显示偏好。
             */
            SettingRow(
                label = "导入本地书籍",
                hint = "从本机选择 TXT / EPUB / PDF，不需要存储权限",
                onClick = onOpenImport,
                trailing = {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = palette.textSecondary,
                    )
                },
            )
            SettingDivider()

            SettingRow(
                label = "显示方式",
                hint = "网格显示封面，列表显示更多信息",
                trailing = {
                    SegmentedControl(
                        options = listOf(
                            com.moyu.reader.data.prefs.ShelfLayout.GRID to "网格",
                            com.moyu.reader.data.prefs.ShelfLayout.LIST to "列表",
                        ),
                        selected = settings.shelfLayout,
                        onSelect = { viewModel.setShelfLayout(it) },
                    )
                },
            )
            SettingDivider()

            SettingRow(
                label = "默认排序",
                hint = "书库与书架都按此排序",
                trailing = {
                    Text(
                        text = SHELF_SORT_LABELS[settings.shelfSort] ?: "最近",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                },
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                com.moyu.reader.ui.components.ChipRow {
                    com.moyu.reader.data.prefs.ShelfSort.entries.forEach { sort ->
                        MoyuChip(
                            text = SHELF_SORT_LABELS[sort] ?: sort.name,
                            active = settings.shelfSort == sort,
                            onClick = { viewModel.setShelfSort(sort) },
                        )
                    }
                }
            }
            }

            // ================= 阅读习惯 =================
            //
            // 这里**刻意不放**字号、行距、段间距、页边距、首行缩进、两端对齐、
            // 正文加粗、显示页码 —— 那些是「排版」，阅读页里点中间就能调，
            // 边调边看效果。放在全局设置里第一个人不会来这里找，
            // 第二个改完还得切回阅读页才能看到结果。两处重复只是噪音。
            if (page == SettingsPage.READING) {
            GroupTitle("阅读习惯")

            SettingRow(
                label = "阅读时常亮",
                hint = "阅读时不让屏幕自动熄灭",
                trailing = { MoyuSwitch(settings.keepScreenOn) { viewModel.setKeepScreenOn(it) } },
            )
            SettingDivider()
            SettingRow(
                label = "音量键翻页",
                hint = "用音量键翻上/下一页，单手时更方便",
                trailing = { MoyuSwitch(settings.volumeKeyPaging) { viewModel.setVolumeKeyPaging(it) } },
            )
            SettingDivider()

            SliderRow(
                label = "自动阅读节奏",
                valueText = "${settings.autoReadSecondsPerPage} 秒/页",
                value = settings.autoReadSecondsPerPage.toFloat(),
                range = 2f..60f,
                onValueChange = { viewModel.setAutoReadSeconds(it.toInt()) },
            )
            }

            // ================= 朗读与词典 =================
            if (page == SettingsPage.SPEECH) {
            GroupTitle("朗读")
            SliderRow(
                label = "语速",
                valueText = String.format("%.1f×", settings.ttsRate),
                value = settings.ttsRate,
                range = 0.5f..2f,
                onValueChange = { viewModel.setTtsRate(it) },
            )
            SliderRow(
                label = "音调",
                valueText = String.format("%.1f", settings.ttsPitch),
                value = settings.ttsPitch,
                range = 0f..2f,
                onValueChange = { viewModel.setTtsPitch(it) },
            )
            SettingRow(
                label = "朗读引擎",
                hint = "使用系统内置的语音合成，无需联网与额外权限",
            )

            GroupTitle("词典")
            SettingRow(
                label = "内置词典",
                hint = "离线可用，无需联网",
                trailing = {
                    Text(
                        text = "${viewModel.builtinDictionarySize} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                },
            )
            SettingDivider()
            SettingRow(
                label = "导入自定义词典",
                hint = "文本文件，每行「词条 拼音 释义」，用空格或制表符分隔",
                trailing = {
                    MoyuTextButton(
                        text = "选择文件",
                        onClick = { dictionaryPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
                    )
                },
            )
            dictionaries.forEach { (id, name, count) ->
                SettingDivider()
                SettingRow(
                    label = name,
                    hint = "$count 条",
                    trailing = {
                        MoyuTextButton(text = "删除", onClick = { viewModel.deleteDictionary(id) })
                    },
                )
            }
            }

            // ================= 数据 =================
            if (page == SettingsPage.DATA) {
            GroupTitle("数据")

            SettingRow(
                label = "清除全部数据",
                hint = "会删除所有书籍、进度、笔记与设置，不可恢复",
                trailing = {
                    MoyuTextButton(
                        text = if (confirmClear) "确认清除" else "清除",
                        onClick = {
                            if (confirmClear) {
                                viewModel.clearAllData()
                                confirmClear = false
                            } else {
                                confirmClear = true
                            }
                        },
                    )
                },
            )
            if (confirmClear) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "再点一次「确认清除」以执行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    MoyuTextButton(text = "取消", onClick = { confirmClear = false })
                }
            }

            /**
             * 结果反馈。
             *
             * 之前 `SettingsViewModel.message` 里写了「已清除全部数据」，
             * 但设置页从来不订阅它 —— 用户点了确认之后界面上没有任何变化，
             * 无从判断到底清没清干净。这里把它显示出来。
             */
            message?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            }

            // ================= 关于 =================
            if (page == SettingsPage.ABOUT) {
            GroupTitle("关于")
            SettingRow(
                label = "墨鱼阅读 · 本地阅读器",
                hint = "版本 ${com.moyu.reader.BuildConfig.VERSION_NAME}",
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = "完全离线的本地阅读器：支持 TXT / EPUB / PDF，自动分章、按实际排版分页、" +
                        "全文搜索、书签与划线笔记、朗读与阅读统计。所有数据只保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "通过存储访问框架（SAF）读取你授权的文件，不申请任何存储权限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 排序项的中文名。放在这里而不是复用 ShelfViewModel 的常量 —— 那是 UI 文案。 */
private val SHELF_SORT_LABELS = mapOf(
    com.moyu.reader.data.prefs.ShelfSort.RECENT to "最近",
    com.moyu.reader.data.prefs.ShelfSort.ADDED to "加入",
    com.moyu.reader.data.prefs.ShelfSort.TITLE to "书名",
    com.moyu.reader.data.prefs.ShelfSort.AUTHOR to "作者",
    com.moyu.reader.data.prefs.ShelfSort.PROGRESS to "进度",
)

/** 设置页的一级分类。 */
private enum class SettingsPage(val title: String, val icon: ImageVector) {
    APPEARANCE("外观与主题", Icons.Filled.Palette),
    SHELF("书架与显示", Icons.Filled.GridView),
    READING("阅读习惯", Icons.Filled.AutoStories),
    SPEECH("朗读与词典", Icons.Filled.RecordVoiceOver),
    DATA("数据管理", Icons.Filled.FolderDelete),
    ABOUT("关于", Icons.Filled.Info);

    /**
     * 分类入口右侧的当前值摘要。
     *
     * 只放**最常被确认的一两个值**，不是把所有设置列一遍 ——
     * 列全了就退化成原来的长列表，分类也就白分了。
     */
    fun summary(
        settings: com.moyu.reader.data.prefs.ReaderSettings,
        dictionaryCount: Int,
    ): String = when (this) {
        APPEARANCE -> buildString {
            append(themeDisplayName(settings.theme))
            if (settings.followSystemDark) append(" · 跟随系统")
            if (settings.eyeCareWarmth > 0f) append(" · 护眼 ${(settings.eyeCareWarmth * 100).toInt()}%")
        }
        SHELF -> {
            val layout = if (settings.shelfLayout == com.moyu.reader.data.prefs.ShelfLayout.GRID) "网格" else "列表"
            "$layout · ${SHELF_SORT_LABELS[settings.shelfSort] ?: "最近"}排序"
        }
        READING -> buildString {
            append("自动 ${settings.autoReadSecondsPerPage} 秒/页")
            if (settings.volumeKeyPaging) append(" · 音量键翻页")
            if (settings.keepScreenOn) append(" · 常亮")
        }
        SPEECH -> String.format("语速 %.1f× · 音调 %.1f · 词典 %d 部", settings.ttsRate, settings.ttsPitch, dictionaryCount)
        DATA -> "清除全部本地数据"
        ABOUT -> "版本 ${com.moyu.reader.BuildConfig.VERSION_NAME}"
    }
}

/** 一级分类入口卡片。 */
@Composable
private fun CategoryCard(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.primary, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = moyuPalette().textSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun MoyuSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = moyuPalette().primary,
        ),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(96.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        if (trailing != NO_TRAILING) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

private val NO_TRAILING: @Composable () -> Unit = {}

