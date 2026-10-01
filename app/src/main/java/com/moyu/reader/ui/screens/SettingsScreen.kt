package com.moyu.reader.ui.screens

import com.moyu.reader.ui.theme.moyuPalette

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
import androidx.compose.ui.text.font.FontWeight
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
import com.moyu.reader.ui.theme.fontDisplayName
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
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val palette = moyuPalette()

    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadDictionaries() }

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
                .padding(bottom = 30.dp),
        ) {
            // ================= 外观 =================
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
                label = "Material You 动态取色",
                hint = "Android 12+ 跟随壁纸取色（仅影响界面，不影响阅读页底色）",
                trailing = {
                    MoyuSwitch(settings.dynamicColor) { viewModel.setDynamicColor(it) }
                },
            )
            SettingDivider()

            SliderRow(
                label = "护眼色温",
                valueText = if (settings.eyeCareWarmth == 0f) "关闭" else "${(settings.eyeCareWarmth * 100).toInt()}%",
                value = settings.eyeCareWarmth,
                range = 0f..1f,
                onValueChange = { viewModel.setEyeCare(it) },
            )
            SliderRow(
                label = "屏幕亮度",
                valueText = settings.brightness?.let { "${(it * 100).toInt()}%" } ?: "跟随系统",
                value = settings.brightness ?: 1f,
                range = 0.15f..1f,
                onValueChange = { viewModel.setBrightness(it) },
                trailing = {
                    MoyuTextButton(
                        text = if (settings.brightness == null) "调节" else "跟随",
                        onClick = {
                            if (settings.brightness == null) viewModel.setBrightness(0.7f)
                            else viewModel.followSystemBrightness()
                        },
                    )
                },
            )

            // ================= 阅读 =================
            GroupTitle("阅读")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.moyu.reader.data.prefs.FontFamilyId.entries.forEach { font ->
                    MoyuChip(
                        text = fontDisplayName(font),
                        active = settings.fontFamily == font,
                        onClick = { viewModel.setFontFamily(font) },
                    )
                }
            }

            StepperRow(
                label = "正文字号",
                value = "${settings.fontSizeSp}px",
                onMinus = { viewModel.setFontSize(settings.fontSizeSp - 1) },
                onPlus = { viewModel.setFontSize(settings.fontSizeSp + 1) },
            )
            StepperRow(
                label = "行距",
                value = String.format("%.1f", settings.lineHeightMultiplier),
                onMinus = { viewModel.setLineHeight(settings.lineHeightMultiplier - 0.1f) },
                onPlus = { viewModel.setLineHeight(settings.lineHeightMultiplier + 0.1f) },
            )
            StepperRow(
                label = "段间距",
                value = String.format("%.1f", settings.paragraphSpacingMultiplier),
                onMinus = { viewModel.setParagraphSpacing(settings.paragraphSpacingMultiplier - 0.2f) },
                onPlus = { viewModel.setParagraphSpacing(settings.paragraphSpacingMultiplier + 0.2f) },
            )
            StepperRow(
                label = "页边距",
                value = "${settings.marginDp}px",
                onMinus = { viewModel.setMargin(settings.marginDp - 2) },
                onPlus = { viewModel.setMargin(settings.marginDp + 2) },
            )
            StepperRow(
                label = "首行缩进",
                value = if (settings.indentEm == 0f) "无" else "${settings.indentEm.toInt()} 字",
                onMinus = { viewModel.setIndent(settings.indentEm - 0.5f) },
                onPlus = { viewModel.setIndent(settings.indentEm + 0.5f) },
            )

            SettingRow(
                label = "两端对齐",
                trailing = { MoyuSwitch(settings.justify) { viewModel.setJustify(it) } },
            )
            SettingDivider()
            SettingRow(
                label = "正文加粗",
                trailing = { MoyuSwitch(settings.bold) { viewModel.setBold(it) } },
            )
            SettingDivider()
            SettingRow(
                label = "阅读时常亮",
                trailing = { MoyuSwitch(settings.keepScreenOn) { viewModel.setKeepScreenOn(it) } },
            )
            SettingDivider()
            SettingRow(
                label = "显示页码",
                trailing = { MoyuSwitch(settings.showPageNumber) { viewModel.setShowPageNumber(it) } },
            )
            SettingDivider()
            SettingRow(
                label = "音量键翻页",
                trailing = { MoyuSwitch(settings.volumeKeyPaging) { viewModel.setVolumeKeyPaging(it) } },
            )
            SettingDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "翻页方式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    modifier = Modifier.width(84.dp),
                )
                SegmentedControl(
                    options = listOf(
                        PageMode.SIMULATION to "仿真",
                        PageMode.SLIDE to "平移",
                        PageMode.COVER to "覆盖",
                        PageMode.SCROLL to "滚动",
                    ),
                    selected = settings.pageMode,
                    onSelect = { viewModel.setPageMode(it) },
                )
            }

            SliderRow(
                label = "自动阅读速度",
                valueText = "${settings.autoReadSpeed} 字/秒",
                value = settings.autoReadSpeed.toFloat(),
                range = 8f..120f,
                onValueChange = { viewModel.setAutoReadSpeed(it.toInt()) },
            )

            // ================= 朗读 =================
            GroupTitle("朗读（TTS）")
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

            // ================= 词典 =================
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
                label = "自定义词典",
                hint = "每行一条：词条<TAB>拼音<TAB>释义",
                trailing = {
                    MoyuTextButton(
                        text = "导入",
                        onClick = { /* 由文件选择器处理，见 ImportScreen 的词典导入入口 */ },
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

            // ================= 数据 =================
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

            // ================= 关于 =================
            GroupTitle("关于")
            SettingRow(label = "墨阅 · 本地阅读器", hint = "版本 1.0.0")
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

            Spacer(Modifier.height(20.dp))
        }
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

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoyuTextButton(text = "−", onClick = onMinus)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = palette.text,
                modifier = Modifier.width(58.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            MoyuTextButton(text = "+", onClick = onPlus)
        }
    }
}
