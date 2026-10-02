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
