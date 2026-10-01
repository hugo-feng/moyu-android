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

/**
 * 依赖容器（手写服务定位器，见 MoyuApplication 的注释说明为何不用 Hilt）。
 *
 * 所有依赖都是 lazy：冷启动时只创建真正被用到的部分。
 * 例如用户直接进入阅读器，就不会去初始化统计相关的仓库。
 */
class AppContainer(private val context: Context) {

    val database: MoyuDatabase by lazy { MoyuDatabase.build(context) }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }

    val documentStore: DocumentStore by lazy { DocumentStore(context) }

    val bookRepository: BookRepository by lazy {
        BookRepository(
            context = context,
            database = database,
            documentStore = documentStore,
        )
    }

    val noteRepository: NoteRepository by lazy { NoteRepository(database) }

    val statsRepository: StatsRepository by lazy { StatsRepository(database) }

    val groupRepository: GroupRepository by lazy { GroupRepository(database) }

    val dictionaryProvider: DictionaryProvider by lazy { DictionaryProvider(database) }

    val ttsController: TtsController by lazy { TtsController(context) }
}
