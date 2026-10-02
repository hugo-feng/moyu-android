package com.moyu.reader.ui

import androidx.lifecycle.ViewModel
import androidx.room.Room
import com.moyu.reader.data.AppContainer
import com.moyu.reader.data.db.MoyuDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * ViewModel 工厂的登记完整性。
 *
 * ## 这个测试为什么存在
 *
 * 真机上「点历史直接闪退」，根因是新加的 `HistoryViewModel`
 * **没有登记进 [MoyuViewModelFactory]** —— 工厂的 else 分支抛
 * `IllegalArgumentException("未注册的 ViewModel")`，页面一打开就崩。
 *
 * 这类错误的麻烦之处在于：
 *   - 编译期完全看不出来（工厂用 `isAssignableFrom` 做运行时分发）；
 *   - 单元测试如果只测各 ViewModel 本身，也测不到；
 *   - 只有真机上点到那个页面才会暴露 —— 而本机没有可用的模拟器。
 *
 * 因此这里直接把**所有 ViewModel 类**都过一遍工厂，
 * 任何一个漏登记都会让这条测试失败。新增 ViewModel 时，
 * 只要它被加进 [ALL_VIEW_MODELS]，就无法再被忘记登记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelFactoryTest {

    private lateinit var context: android.content.Context
    private lateinit var db: MoyuDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MoyuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        // 清理某些 ViewModel 可能落下的缓存目录，避免干扰其他测试
        File(context.cacheDir, "moyu-test").deleteRecursively()
    }

    private fun container(): AppContainer = object : AppContainer(context) {
        override val database: MoyuDatabase get() = db
    }

    /**
     * 所有对外暴露的 ViewModel。
     *
     * 新增 ViewModel 时必须加到这里 —— 加进来就会被下面两条测试覆盖，
     * 不加则说明它还没有被任何页面使用（那种情况本身也该被审视）。
     */
    private val allViewModelClasses: List<Class<out ViewModel>> = listOf(
        ShelfViewModel::class.java,
        ReaderViewModel::class.java,
        SearchViewModel::class.java,
        NotesViewModel::class.java,
        HistoryViewModel::class.java,
        StatsViewModel::class.java,
        SettingsViewModel::class.java,
    )

    @Test
    fun `每一个 ViewModel 都能被工厂创建`() {
        val factory = MoyuViewModelFactory(container())
        val failures = mutableListOf<String>()

        for (cls in allViewModelClasses) {
            try {
                val instance = factory.create(cls)
                if (instance == null) failures.add("${cls.simpleName}: 工厂返回了 null")
            } catch (e: Throwable) {
                failures.add("${cls.simpleName}: ${e::class.java.simpleName} — ${e.message}")
            }
        }

        assertTrue(
            "以下 ViewModel 无法被工厂创建，页面一打开就会闪退：\n" +
                failures.joinToString("\n") { "  - $it" },
            failures.isEmpty(),
        )
    }

    @Test
    fun `未登记的类会被明确拒绝而不是静默返回错误类型`() {
        // 反向保护：工厂对不认识的类型必须抛错。
        // 若它「返回了别的东西」，页面会拿到类型不对的 ViewModel，
        // 那种错误更难查（表现为莫名其妙的空数据，而不是崩溃）。
        val factory = MoyuViewModelFactory(container())
        val threw = try {
            factory.create(UnregisteredViewModel::class.java)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("未登记的 ViewModel 应当被明确拒绝", threw)
    }

    private class UnregisteredViewModel : ViewModel()
}
