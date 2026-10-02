package com.moyu.reader.data

import android.content.Context
import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.prefs.SettingsStore
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.data.repository.GroupRepository
import com.moyu.reader.data.repository.NoteRepository
import com.moyu.reader.data.repository.StatsRepository
import com.moyu.reader.reader.DictionaryProvider
import com.moyu.reader.reader.TtsController
import com.moyu.reader.storage.DocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 依赖容器（手写服务定位器，见 MoyuApplication 的注释说明为何不用 Hilt）。
 *
 * 所有依赖都是 lazy：冷启动时只创建真正被用到的部分。
 * 例如用户直接进入阅读器，就不会去初始化统计相关的仓库。
 *
 * `database` 与由它派生的仓库声明为 `open`：测试需要把数据库换成内存实例，
 * 否则单元测试会去碰真实设备上的用户书库。这是为可测性付出的最小代价 ——
 * 生产代码不做任何覆盖，行为完全不变。
 */
open class AppContainer(private val context: Context) {

    /**
     * 应用级协程作用域，用于**生命周期结束后仍必须完成**的收尾写入。
     *
     * 为什么需要它：`viewModelScope` 在框架调用 `ViewModel.onCleared()` **之前**
     * 就已经被取消了，因此在 onCleared 里 `viewModelScope.launch { ... }` 得到的
     * 是一个 CANCELED 的 Job，协程体永远不会执行 —— 表现就是「退出阅读器时
     * 最后一次阅读进度与这段阅读时长静默丢失」，而注释却写着「已结算」。
     * 这类收尾必须交给不随 ViewModel 消亡的作用域。
     *
     * 用 SupervisorJob：某个收尾任务失败不应连带取消其它任务。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    open val database: MoyuDatabase by lazy { MoyuDatabase.build(context) }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }

    val documentStore: DocumentStore by lazy { DocumentStore(context) }

    open val bookRepository: BookRepository by lazy {
        BookRepository(
            context = context,
            database = database,
            documentStore = documentStore,
        )
    }

    open val noteRepository: NoteRepository by lazy { NoteRepository(database) }

    open val statsRepository: StatsRepository by lazy { StatsRepository(database) }

    open val groupRepository: GroupRepository by lazy { GroupRepository(database) }

    val dictionaryProvider: DictionaryProvider by lazy { DictionaryProvider(database) }

    val ttsController: TtsController by lazy { TtsController(context) }
}
