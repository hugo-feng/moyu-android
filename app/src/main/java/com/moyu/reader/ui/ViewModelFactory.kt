package com.moyu.reader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moyu.reader.MoyuApplication
import com.moyu.reader.data.AppContainer

/**
 * ViewModel 工厂。
 *
 * 用手写工厂而不是 `viewModel { }` 的默认工厂：我们的 ViewModel 需要
 * AppContainer 里的多个依赖，而默认工厂只能注入无参构造或 Application。
 * 手写工厂虽然多几行，但依赖关系一目了然，也不需要引入 Hilt 的注解处理开销。
 */
class MoyuViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ShelfViewModel::class.java) ->
                ShelfViewModel(container) as T

            modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
                ReaderViewModel(container) as T

            modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                SearchViewModel(container) as T

            modelClass.isAssignableFrom(NotesViewModel::class.java) ->
                NotesViewModel(container) as T

            modelClass.isAssignableFrom(StatsViewModel::class.java) ->
                StatsViewModel(container) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(container) as T

            else -> throw IllegalArgumentException("未注册的 ViewModel: ${modelClass.name}")
        }
    }
}

/** 从任意 ViewModel 里安全取得容器。 */
val ViewModel.appContainer: AppContainer
    get() = when (this) {
        is ContainerAware -> container
        is AndroidViewModel -> (getApplication<Application>() as MoyuApplication).container
        else -> throw IllegalStateException("该 ViewModel 未持有 AppContainer")
    }

/** 标记接口：ViewModel 通过它暴露容器（便于测试时替换假实现）。 */
interface ContainerAware {
    val container: AppContainer
}

/** 让 ViewModel 能方便地拿到容器的基类。 */
abstract class MoyuViewModel(override val container: AppContainer) : ViewModel(), ContainerAware {
    protected val scope get() = viewModelScope
}
