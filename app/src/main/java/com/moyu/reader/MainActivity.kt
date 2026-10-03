package com.moyu.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.prefs.ReaderSettings
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.SettingsViewModel
import com.moyu.reader.ui.navigation.MoyuApp
import com.moyu.reader.ui.theme.MoyuTheme
import com.moyu.reader.ui.theme.paletteFor

/**
 * 唯一的 Activity。
 *
 * 采用单 Activity + Compose 导航：阅读器、书架、统计等页面都是 Compose 目的地，
 * 而不是各开一个 Activity。这样页面切换没有 Activity 启动开销，
 * 阅读进度、设置等状态也不需要跨 Activity 传递（那是最容易出错的地方）。
 *
 * 唯一需要绕开 Compose 的地方是「关机/亮屏」这类系统交互，
 * 它们由阅读器内的 DisposableEffect 处理，不需要额外 Activity。
 */
class MainActivity : ComponentActivity() {

    /**
     * 音量键翻页的**唯一接线点**。
     *
     * ## 为什么必须做在 Activity 而不是 Compose 里
     *
     * 「音量键翻页」这个开关在设置里存在很久了，但全项目没有任何按键处理 ——
     * 存了、读了、显示了，就是没人接：用户打开它，按音量键只会调音量。
     *
     * Compose 的 `onKeyEvent` 要靠焦点，阅读页里焦点随时可能落在别处
     * （工具栏按钮、面板输入框），音量键会在到达它之前被系统处理掉。
     * 而 `Activity.onKeyDown` 是所有按键的必经之路，最可靠。
     *
     * 阅读器进入时注册、退出时注销：只有阅读页开着，音量键才是翻页键。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val handler = VolumeKeyRouter.handler
        if (handler != null) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (handler(true)) return true
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (handler(false)) return true
                else -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边显示：阅读器需要内容延伸到状态栏/导航栏之下，
        // 这样护眼色温遮罩才能覆盖整屏，观感才沉浸。
        enableEdgeToEdge()

        setContent {
            val factory = MoyuViewModelFactory((application as MoyuApplication).container)
            // 设置 ViewModel 在 Activity 级别持有：整个应用共用一份设置状态，
            // 避免每个页面各自订阅一次 DataStore 造成不必要的重复读取。
            val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
            val settings by settingsViewModel.settings.collectAsState()

            MoyuTheme(settings = settings) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(paletteFor(settings.theme).surface),
                ) {
                    // 不再把 settings 传进 MoyuApp：底部导航的可见性由路由决定，
                    // 与设置无关。少一层参数传递，少一处可能不同步的状态。
                    MoyuApp(
                        settingsViewModel = settingsViewModel,
                        factory = factory,
                    )
                }
            }
        }
    }
}

/** 供 Compose 预览使用的一组默认设置。 */
internal val PreviewSettings = ReaderSettings()

/**
 * 音量键的去处。
 *
 * 阅读页打开时注册一个处理器：参数是「是否音量减键」，返回 true 表示
 * 这次按键已经被消费（翻页了），false 表示照旧调音量。
 *
 * 做成一个全局的单点路由，是因为按键的第一个入口是 Activity，
 * 而「现在该不该翻页」只有阅读页知道（设置开关 + 是否有面板打开）。
 */
object VolumeKeyRouter {
    /** @return true 表示已消费这次按键 */
    var handler: ((volumeDown: Boolean) -> Boolean)? = null
}
