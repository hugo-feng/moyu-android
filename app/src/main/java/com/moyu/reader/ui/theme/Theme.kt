package com.moyu.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyu.reader.data.prefs.FontFamilyId
import com.moyu.reader.data.prefs.ReaderSettings
import com.moyu.reader.data.prefs.ThemeId

/**
 * 墨阅配色。
 *
 * 取值与 Web 验证器的设计令牌（src/styles.css 的 :root）以及
 * res/values/colors.xml 三处保持一致。三方同源的意义是：
 * 调整配色时能明确知道还有哪两处要改，不会出现「网页版是暖棕、安卓版是冷灰」这种分裂。
 */
data class MoyuPalette(
    /** 阅读页背景 */
    val background: Color,
    /** 阅读页正文色 */
    val text: Color,
    /** 次要文字（页码、章节名） */
    val textSecondary: Color,
    /** 界面主色 */
    val primary: Color,
    /** 界面背景（书架、设置页） */
    val surface: Color,
    /** 卡片背景 */
    val card: Color,
    /** 分割线 */
    val divider: Color,
    /** 书架页顶部区域（略深于 surface，用于建立层次） */
    val shelfHeader: Color,
    val dark: Boolean,
    val eyeFriendly: Boolean,
)

val PaperPalette = MoyuPalette(
    background = Color(0xFFF7F3EA),
    text = Color(0xFF33302B),
    textSecondary = Color(0xFF8C8377),
    primary = Color(0xFF8A6A46),
    surface = Color(0xFFFBF8F2),
    card = Color(0xFFFFFFFF),
    divider = Color(0xFFE7E0D3),
    shelfHeader = Color(0xFFF2ECE0),
    dark = false,
    eyeFriendly = true,
)

val SepiaPalette = MoyuPalette(
    background = Color(0xFFF0E4CC),
    text = Color(0xFF4A3F2F),
    textSecondary = Color(0xFF9A8A70),
    primary = Color(0xFF967A50),
    surface = Color(0xFFF6EEDC),
    card = Color(0xFFFFFBF2),
    divider = Color(0xFFE0D2B6),
    shelfHeader = Color(0xFFEDE1C8),
    dark = false,
    eyeFriendly = true,
)

val GreenPalette = MoyuPalette(
    background = Color(0xFFDCE8DA),
    text = Color(0xFF2F3A2E),
    textSecondary = Color(0xFF6E7C6C),
    primary = Color(0xFF4E7A4A),
    surface = Color(0xFFE6EFE4),
    card = Color(0xFFF5FAF3),
    divider = Color(0xFFC9D8C6),
    shelfHeader = Color(0xFFD8E6D6),
    dark = false,
    eyeFriendly = true,
)

val NightPalette = MoyuPalette(
    background = Color(0xFF16171A),
    text = Color(0xFFB9BCC2),
    textSecondary = Color(0xFF71757D),
    primary = Color(0xFFC2A179),
    surface = Color(0xFF1D1F23),
    card = Color(0xFF24262B),
    divider = Color(0xFF31343A),
    shelfHeader = Color(0xFF202226),
    dark = true,
    eyeFriendly = true,
)

val InkPalette = MoyuPalette(
    background = Color(0xFF000000),
    text = Color(0xFF9BA0A6),
    textSecondary = Color(0xFF5E646B),
    primary = Color(0xFF7C93B0),
    surface = Color(0xFF0C0D0F),
    card = Color(0xFF15171A),
    divider = Color(0xFF242629),
    shelfHeader = Color(0xFF0F1113),
    dark = true,
    eyeFriendly = false,
)

fun paletteFor(theme: ThemeId): MoyuPalette = when (theme) {
    ThemeId.PAPER -> PaperPalette
    ThemeId.SEPIA -> SepiaPalette
    ThemeId.GREEN -> GreenPalette
    ThemeId.NIGHT -> NightPalette
    ThemeId.INK -> InkPalette
}

/** 主题名（设置页展示用）。 */
fun themeDisplayName(theme: ThemeId): String = when (theme) {
    ThemeId.PAPER -> "纸白"
    ThemeId.SEPIA -> "羊皮"
    ThemeId.GREEN -> "青竹"
    ThemeId.NIGHT -> "夜幕"
    ThemeId.INK -> "墨黑"
}

/**
 * 正文字体。
 *
 * ## 为什么用字体族名而不是打包字体文件
 *
 * 打包一份中文字体动辄 10~20 MB（思源宋体全量约 20 MB），
 * 而系统已有高质量中文字体。这里通过**系统字体族名**让不同 ROM
 * 使用各自的默认字体，在中文环境下分别是宋体/黑体族 ——
 * 这正是中文阅读的常见观感。
 *
 * ## 之前这里是假的
 *
 * 早先 KAI / SONG / HEI 三个选项**都映射到 Serif 或 SansSerif**，
 * 也就是「楷体」「思源宋」「黑体」实际渲染出来与前两项完全一样。
 * 用户反馈「添加更多字体」时看不出任何变化，根因就在这里。
 *
 * 现在每一项都对应一个真实存在的系统字体族：
 *   - `serif` / `sans-serif`：通用族，各 ROM 都会映射到自己的中文字体
 *   - `sans-serif-light` / `-medium` / `-condensed`：Android 必备族，
 *     任何设备上都有，字重与字宽**确实不同**
 *
 * 中文场景下后三者主要改变西文与数字，汉字仍回落到系统 CJK 字体 ——
 * 这是不打包字体时能做到的上限，但至少选项不再是骗人的。
 * 若要真正区分汉字字形（楷体 vs 宋体），必须打包或按需下载字体文件。
 */
/**
 * 正文字体的实际可用族。
 *
 * ## 为什么只有三种
 *
 * 用的 Compose 版本（BOM 2024.10.01）只提供五个通用族：
 * `Default / Serif / SansSerif / Monospace / Cursive`，
 * **没有**「按系统字体族名取字体」的 API（`DeviceFontFamilyName` 是更高版本才有的）。
 * 而 `FontFamily(Typeface)` 那个构造是 protected，外部拿不到。
 *
 * 也就是说：不打包字体文件的前提下，能真正区分开的只有这几种。
 * 早先列出的「楷体 / 思源宋 / 黑体」三项其实**都映射到 Serif 或 SansSerif** ——
 * 选项是假的，点了没有任何变化。用户反馈「添加更多字体」却没效果，根因在此。
 *
 * ## 怎么做到「更多」
 *
 * 与其摆一排点了没反应的假选项，不如把真实的差异做实：
 * 族（[fontFamilyFor]）× 字重（[fontWeightFor]）。
 * 字重是 Compose 会真正应用并渲染出差异的，两端也都取同一个值。
 *
 * 要真正区分汉字字形（楷体 vs 宋体），必须打包或按需下载字体文件 ——
 * 一份中文字体 10~20 MB，这个取舍超出「加几个选项」的范围，
 * 因此这里如实说明限制，而不是继续摆假选项。
 */
fun fontFamilyFor(id: FontFamilyId): FontFamily = when (id) {
    FontFamilyId.SERIF -> FontFamily.Serif
    FontFamilyId.SANS -> FontFamily.SansSerif
    FontFamilyId.MONO -> FontFamily.Monospace
    // 以下三项复用上面的族，靠字重区分（见 fontWeightFor）
    FontFamilyId.SANS_LIGHT -> FontFamily.SansSerif
    FontFamilyId.SANS_BOLD -> FontFamily.SansSerif
    FontFamilyId.SERIF_BOLD -> FontFamily.Serif
}

/**
 * 字体族名（给分页引擎用）。
 *
 * 分页引擎走 `android.graphics.Typeface.create(name, style)`，
 * 需要的是系统族名而不是 Compose 的 FontFamily。
 * **两边必须表达同一个字体**，否则换行位置不同、分页与显示错位。
 */
fun fontFamilyNameFor(id: FontFamilyId): String = when (id) {
    FontFamilyId.SERIF, FontFamilyId.SERIF_BOLD -> "serif"
    FontFamilyId.SANS, FontFamilyId.SANS_LIGHT, FontFamilyId.SANS_BOLD -> "sans-serif"
    FontFamilyId.MONO -> "monospace"
}

/**
 * 字重（给分页引擎用）。
 *
 * 与 [fontWeightFor] 是同一条规则的两个表达：这里给 `Typeface` 的样式常量，
 * 那边给 Compose 的 `FontWeight`。改一处必须改另一处。
 */
fun typefaceStyleFor(id: FontFamilyId): Int = when (id) {
    FontFamilyId.SANS_LIGHT -> android.graphics.Typeface.NORMAL
    FontFamilyId.SANS_BOLD, FontFamilyId.SERIF_BOLD -> android.graphics.Typeface.BOLD
    else -> android.graphics.Typeface.NORMAL
}

/** 字重（给渲染侧用）。 */
fun fontWeightFor(id: FontFamilyId): FontWeight = when (id) {
    FontFamilyId.SANS_LIGHT -> FontWeight.Light
    FontFamilyId.SANS_BOLD, FontFamilyId.SERIF_BOLD -> FontWeight.Bold
    else -> FontWeight.Normal
}

fun fontDisplayName(id: FontFamilyId): String = when (id) {
    FontFamilyId.SERIF -> "宋体衬线"
    FontFamilyId.SANS -> "无衬线"
    FontFamilyId.MONO -> "等宽"
    FontFamilyId.SANS_LIGHT -> "无衬线 · 细"
    FontFamilyId.SANS_BOLD -> "无衬线 · 粗"
    FontFamilyId.SERIF_BOLD -> "衬线 · 粗"
}

/**
 * 通过 CompositionLocal 提供当前配色。
 *
 * 为什么不只用 MaterialTheme.colorScheme：
 * 阅读页的背景、正文色、页码色是**阅读主题**的概念（羊皮/青竹/夜幕…），
 * 与 Material 的语义色（surface/onSurface）不是一一对应的；
 * 单独提供一份 MoyuPalette 能让阅读器直接取到精确的颜色，
 * 而不必把阅读主题硬塞进 Material 的语义槽位里，导致语义错乱。
 */
val LocalMoyuPalette = staticCompositionLocalOf { PaperPalette }

/** 当前是否处于「沉浸阅读」状态（阅读器全屏时界面元素需要更淡）。 */
val LocalImmersive = staticCompositionLocalOf { false }

/** 主题缩放：阅读器的触摸目标与文字尺寸（Android 上暂固定为 1f，保留扩展点）。 */
val LocalScale = staticCompositionLocalOf { 1f }

@Composable
fun MoyuTheme(
    settings: ReaderSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    // 主题优先级：跟随系统深色（若开启）> 用户选择的主题
    val palette = if (settings.followSystemDark && systemDark) NightPalette else paletteFor(settings.theme)

    // Material You 动态取色：仅在 Android 12+ 且用户开启时生效。
    // 注意：动态取色只影响**界面**（书架/设置），阅读页背景始终用阅读主题，
    // 否则「羊皮纸」会被系统配色改成奇怪的色调，破坏阅读体验。
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        settings.dynamicColor && dynamicAvailable && settings.theme == ThemeId.PAPER -> {
            if (palette.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        palette.dark -> darkColorScheme(
            primary = palette.primary,
            onPrimary = Color.White,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.shelfHeader,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.divider,
        )

        else -> lightColorScheme(
            primary = palette.primary,
            onPrimary = Color.White,
            background = palette.surface,
            onBackground = palette.text,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.shelfHeader,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.divider,
        )
    }

    CompositionLocalProvider(
        LocalMoyuPalette provides palette,
        LocalImmersive provides false,
        LocalScale provides 1f,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MoyuTypography,
            content = content,
        )
    }
}

/**
 * 读取当前配色。
 *
 * 为什么是**顶层函数**而不是 `object MoyuTheme { val palette }`：
 * 带 @Composable 的属性需要可无参调用的 getter，而 object 的属性访问在
 * Compose 编译器插件里不会走这条路径（实测报 "Unresolved reference: MoyuTheme"）。
 * 顶层函数是最可靠的形式，调用点为 `moyuPalette()`。
 */
@Composable
fun moyuPalette(): MoyuPalette = LocalMoyuPalette.current

/** 阅读器通用尺寸常量，避免各处硬编码不一致。 */
object ReaderDimens {
    /** 触摸目标最小尺寸（可访问性底线） */
    val MinTouchTarget: Dp = 44.dp

    /** 工具栏高度 */
    val ToolbarHeight: Dp = 52.dp

    /** 底部导航高度 */
    val BottomBarHeight: Dp = 56.dp

    /** 封面圆角 */
    val CoverCorner: Dp = 8.dp

    /** 正文段落间距（由设置换算，这里是默认值） */
    val DefaultParagraphSpacing: Dp = 12.dp
}

/** 正文基准字号（sp），实际值由设置决定。 */
val DefaultBodyFontSize = 19.sp

val BodyFontWeightNormal = FontWeight.Normal
val BodyFontWeightBold = FontWeight.Medium
