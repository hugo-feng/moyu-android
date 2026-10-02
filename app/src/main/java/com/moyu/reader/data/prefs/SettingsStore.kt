package com.moyu.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 阅读主题。 */
enum class ThemeId { PAPER, SEPIA, GREEN, NIGHT, INK }

/** 翻页方式。 */
enum class PageMode { SIMULATION, SLIDE, COVER, SCROLL, NONE }

/** 字体系列。 */
enum class FontFamilyId { SERIF, SANS, KAI, SONG, HEI }

/** 书架排序方式。 */
enum class ShelfSort { RECENT, ADDED, TITLE, AUTHOR, PROGRESS }

/** 书架布局。 */
enum class ShelfLayout { GRID, LIST }

/**
 * 阅读器的全部设置项。
 *
 * 这是一个不可变数据类，通过 [SettingsStore.settings] 以 Flow 暴露。
 * 之所以不用多个独立的 preference key 直接读：排版参数（字号/行距/边距）
 * 变化时会触发重新分页，把它们聚合成一个对象可以让 UI 用一次订阅判断「排版是否变化」，
 * 而不是分别监听六七个字段。
 */
data class ReaderSettings(
    val theme: ThemeId = ThemeId.PAPER,
    val pageMode: PageMode = PageMode.SIMULATION,
    // —— 排版 ——
    val fontSizeSp: Int = 19,
    val lineHeightMultiplier: Float = 1.7f,
    val paragraphSpacingMultiplier: Float = 0.8f,
    val marginDp: Int = 22,
    val fontFamily: FontFamilyId = FontFamilyId.SERIF,
    val justify: Boolean = true,
    val bold: Boolean = false,
    val indentEm: Float = 2f,
    val letterSpacingEm: Float = 0.012f,
    // —— 护眼 ——
    /**
     * 护眼色温（0f~1f）。
     *
     * 这里**刻意没有屏幕亮度**：Android 的亮度是系统级设置，
     * 应用再叠加一层只会与系统的自动亮度互相打架 ——
     * 用户拉低应用内亮度后，系统的「根据环境光自动调节」仍按自己的逻辑走，
     * 结果屏幕忽明忽暗且找不到原因。系统下拉栏已经能调，不必重复提供。
     */
    val eyeCareWarmth: Float = 0f,
    // —— 阅读辅助 ——
    /**
     * 自动阅读的**每页停留秒数**。
     *
     * 早先这里存的是「字/秒」，再由「本页字数 ÷ 速度」算出停留时长。
     * 那个做法的问题是：停留时间随页面长短浮动，用户没法预期下一页几时翻，
     * 想「定一个节奏让它自己翻」这个需求反而落空了。
     * 现在直接设定秒数，节奏是确定的。
     */
    val autoReadSecondsPerPage: Int = 9,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val keepScreenOn: Boolean = false,
    val showStatusBar: Boolean = true,
    val showPageNumber: Boolean = true,
    val volumeKeyPaging: Boolean = true,
    // —— 外观 ——
    val dynamicColor: Boolean = true,
    val followSystemDark: Boolean = false,
    // —— 书架 ——
    val shelfSort: ShelfSort = ShelfSort.RECENT,
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
)

/**
 * 设置持久化（DataStore）。
 *
 * 选 DataStore 而不是 SharedPreferences：它是 Jetpack 官方推荐的替代品，
 * 提供类型安全的 Flow 与事务性写入，且不会像 SharedPreferences 的 apply()
 * 那样在极端情况下丢写。
 */
class SettingsStore(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "moyu_settings")

    private val store: DataStore<Preferences> get() = context.dataStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val pageMode = stringPreferencesKey("page_mode")
        val fontSize = intPreferencesKey("font_size_sp")
        val lineHeight = floatPreferencesKey("line_height")
        val paragraphSpacing = floatPreferencesKey("paragraph_spacing")
        val margin = intPreferencesKey("margin_dp")
        val fontFamily = stringPreferencesKey("font_family")
        val justify = booleanPreferencesKey("justify")
        val bold = booleanPreferencesKey("bold")
        val indent = floatPreferencesKey("indent_em")
        val letterSpacing = floatPreferencesKey("letter_spacing_em")
        val eyeCare = floatPreferencesKey("eye_care_warmth")
        /**
         * 亮度相关键已废弃（`brightness` / `brightness_follow_system`）。
         *
         * 键名与旧数据刻意**保留不删**：DataStore 是持久化的，
         * 老用户升级上来时旧键还在文件里，删掉常量不影响读取，
         * 但保留注释能让人明白「为什么这里少了两个键」，
         * 避免后来者以为是不小心漏了又加回去。
         */
        val autoReadSeconds = intPreferencesKey("auto_read_seconds_per_page")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val showStatusBar = booleanPreferencesKey("show_status_bar")
        val showPageNumber = booleanPreferencesKey("show_page_number")
        val volumeKeyPaging = booleanPreferencesKey("volume_key_paging")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val followSystemDark = booleanPreferencesKey("follow_system_dark")
        val shelfSort = stringPreferencesKey("shelf_sort")
        val shelfLayout = stringPreferencesKey("shelf_layout")
    }

    val settings: Flow<ReaderSettings> = store.data.map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): ReaderSettings {
        return ReaderSettings(
            theme = enumOrDefault(this[Keys.theme], ThemeId.PAPER),
            pageMode = enumOrDefault(this[Keys.pageMode], PageMode.SIMULATION),
            fontSizeSp = this[Keys.fontSize] ?: 19,
            lineHeightMultiplier = this[Keys.lineHeight] ?: 1.7f,
            paragraphSpacingMultiplier = this[Keys.paragraphSpacing] ?: 0.8f,
            marginDp = this[Keys.margin] ?: 22,
            fontFamily = enumOrDefault(this[Keys.fontFamily], FontFamilyId.SERIF),
            justify = this[Keys.justify] ?: true,
            bold = this[Keys.bold] ?: false,
            indentEm = this[Keys.indent] ?: 2f,
            letterSpacingEm = this[Keys.letterSpacing] ?: 0.012f,
            eyeCareWarmth = this[Keys.eyeCare] ?: 0f,
            autoReadSecondsPerPage = this[Keys.autoReadSeconds] ?: 9,
            ttsRate = this[Keys.ttsRate] ?: 1.0f,
            ttsPitch = this[Keys.ttsPitch] ?: 1.0f,
            keepScreenOn = this[Keys.keepScreenOn] ?: false,
            showStatusBar = this[Keys.showStatusBar] ?: true,
            showPageNumber = this[Keys.showPageNumber] ?: true,
            volumeKeyPaging = this[Keys.volumeKeyPaging] ?: true,
            dynamicColor = this[Keys.dynamicColor] ?: true,
            followSystemDark = this[Keys.followSystemDark] ?: false,
            shelfSort = enumOrDefault(this[Keys.shelfSort], ShelfSort.RECENT),
            shelfLayout = enumOrDefault(this[Keys.shelfLayout], ShelfLayout.GRID),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        if (name == null) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    // ============================================================
    // 写入：每一项单独提供 setter，避免调用方拼装整个对象时漏字段
    // ============================================================

    suspend fun setTheme(value: ThemeId) = store.edit { it[Keys.theme] = value.name }

    suspend fun setPageMode(value: PageMode) = store.edit { it[Keys.pageMode] = value.name }

    suspend fun setFontSize(sp: Int) =
        store.edit { it[Keys.fontSize] = sp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) }

    suspend fun setLineHeight(multiplier: Float) =
        store.edit { it[Keys.lineHeight] = multiplier.coerceIn(1.2f, 2.6f) }

    suspend fun setParagraphSpacing(multiplier: Float) =
        store.edit { it[Keys.paragraphSpacing] = multiplier.coerceIn(0f, 2f) }

    suspend fun setMargin(dp: Int) =
        store.edit { it[Keys.margin] = dp.coerceIn(MIN_MARGIN, MAX_MARGIN) }

    suspend fun setFontFamily(value: FontFamilyId) = store.edit { it[Keys.fontFamily] = value.name }

    suspend fun setJustify(value: Boolean) = store.edit { it[Keys.justify] = value }

    suspend fun setBold(value: Boolean) = store.edit { it[Keys.bold] = value }

    suspend fun setIndent(em: Float) = store.edit { it[Keys.indent] = em.coerceIn(0f, 4f) }

    suspend fun setLetterSpacing(em: Float) = store.edit { it[Keys.letterSpacing] = em.coerceIn(0f, 0.08f) }

    suspend fun setEyeCare(warmth: Float) = store.edit { it[Keys.eyeCare] = warmth.coerceIn(0f, 1f) }

    /**
     * 自动阅读的每页停留秒数。
     *
     * 下限 2 秒：再快的话页面刚出现就翻走了，实际上看不清任何内容，
     * 那不是「自动阅读」而是快速翻页。上限 120 秒足够覆盖慢读。
     */
    suspend fun setAutoReadSeconds(seconds: Int) =
        store.edit { it[Keys.autoReadSeconds] = seconds.coerceIn(2, 120) }

    suspend fun setTtsRate(rate: Float) = store.edit { it[Keys.ttsRate] = rate.coerceIn(0.5f, 2f) }

    suspend fun setTtsPitch(pitch: Float) = store.edit { it[Keys.ttsPitch] = pitch.coerceIn(0f, 2f) }

    suspend fun setKeepScreenOn(value: Boolean) = store.edit { it[Keys.keepScreenOn] = value }

    suspend fun setShowStatusBar(value: Boolean) = store.edit { it[Keys.showStatusBar] = value }

    suspend fun setShowPageNumber(value: Boolean) = store.edit { it[Keys.showPageNumber] = value }

    suspend fun setVolumeKeyPaging(value: Boolean) = store.edit { it[Keys.volumeKeyPaging] = value }

    suspend fun setDynamicColor(value: Boolean) = store.edit { it[Keys.dynamicColor] = value }

    suspend fun setFollowSystemDark(value: Boolean) = store.edit { it[Keys.followSystemDark] = value }

    suspend fun setShelfSort(value: ShelfSort) = store.edit { it[Keys.shelfSort] = value.name }

    suspend fun setShelfLayout(value: ShelfLayout) = store.edit { it[Keys.shelfLayout] = value.name }

    /** 清除所有设置（设置页的「恢复默认」）。 */
    suspend fun resetToDefaults() = store.edit { it.clear() }

    companion object {
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 34
        const val MIN_MARGIN = 8
        const val MAX_MARGIN = 48
    }
}
