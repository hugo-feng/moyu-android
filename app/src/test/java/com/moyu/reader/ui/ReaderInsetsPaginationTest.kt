package com.moyu.reader.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.unit.Density
import androidx.room.Room
import com.moyu.reader.data.AppContainer
import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.data.repository.BookRepository
import com.moyu.reader.storage.DocumentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 系统安全区（状态栏 / 手势条）参与分页的测试。
 *
 * 为什么这条逻辑必须有测试：
 *   目标机型是小米 14 的澎湃 OS 3（Android 16），强制边到边显示，
 *   正文会画到状态栏与手势条底下。若分页不扣除安全区高度，
 *   排出来的页就比可视区高一截，末行被推到屏幕外 ——
 *   用户看到的就是「字铺满后底部被遮挡」。这个缺陷只在真机上看得见，
 *   而本机没有可用模拟器，所以必须用测试把逻辑钉住。
 *
 * 测试方式：Robolectric + 真实 Room（内存库）+ 真实 StaticLayout，
 * 走 `ReaderViewModel.open(...)` 的完整路径，不是对公式的重复演算。
 *
 * 注意等待方式：`viewModelScope` 绑定 Main dispatcher，
 * 在 Robolectric 里必须显式推进 Main looper（`shadowOf(...).idle()`），
 * 用 `Thread.sleep` 等不到协程 —— 那只会让测试随机失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderInsetsPaginationTest {

    private lateinit var context: Context
    private lateinit var db: MoyuDatabase

    /**
     * 足够长的正文，**每句独占一行**。
     *
     * 为什么必须带换行：Robolectric 的 StaticLayout 对不含换行的 CJK 长文本
     * 不做换行（实测 16100 字在 948px 宽度下只排出 3 行），拿它验证「页数随高度变化」
     * 会得到恒为 1 页的假结果 —— 测试看似在跑，其实什么都没验证。
     * 显式换行让每句成为独立行，排版行为才与真机一致。
     *
     * 行数也要够多：测试视口 1080×2200 大约一页放 22 行，
     * 这里给到 600 行，页数对高度变化才足够敏感。
     */
    private val body = (1..600).joinToString("\n") { "第${it}句正文内容用来把这一章撑到足够长以便分出多页。" }

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
    }

    /** 只替换数据库，其余依赖保持真实实现。 */
    private fun container(): AppContainer = object : AppContainer(context) {
        override val database: MoyuDatabase get() = db
    }

    private fun importBook(repo: BookRepository): String {
        val text = "第一章 开端\n$body\n第二章 继续\n$body\n"
        val result = runBlocking { repo.importFromText("分页测试.txt", text, "UTF-8") }
        assertTrue("导入应成功，实际 $result", result is ImportResult.Success)
        return (result as ImportResult.Success).bookId
    }

    /** 与真机接近：1080×2400 屏幕、3.0 密度，可用高度约 2200px。 */
    private val density = Density(density = 3f, fontScale = 1f)
    private val viewportWidth = 1080
    private val viewportHeight = 2200

    /**
     * 轮询等待条件成立，期间不断推进主线程。
     *
     * 为什么不能只 idle 几次：仓库查询走 `withContext(Dispatchers.IO)`，
     * 在 Robolectric 里那是**真实线程池**，而 `idle()` 只推进主线程。
     * 两者时序对不上 —— 一次性 idle 完时 IO 可能还没返回，主线程自然没有后续任务。
     * 因此必须「让出真实时间 → 推进主线程」交替进行，直到条件成立或超时。
     */
    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) {
            throw AssertionError("等待超时：$what")
        }
    }

    /** 把主线程任务跑干净（用于「不应有变化」这类否定断言）。 */
    private fun settle(times: Int = 20) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * 打开书、给定视口与安全区，返回分页结果。
     * 每次都用全新的 ViewModel，避免上一轮的分页缓存影响结论。
     */
    private fun pageCount(topPx: Int, bottomPx: Int, bookId: String): Int {
        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }
        vm.setInsets(topPx, bottomPx, density)
        settle()
        return vm.pages.value.size
    }

    @Test
    fun `加上安全区后页数必须增加`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val noInset = pageCount(0, 0, bookId)
        val withInset = pageCount(84, 48, bookId)

        assertTrue("无安全区时应至少分出 2 页，实际 $noInset", noInset >= 2)
        assertTrue(
            "加上安全区后页数必须增加：无安全区 $noInset 页，有安全区 $withInset 页。" +
                "页数没变说明安全区没有进入分页计算 —— 真机上就会出现末行被遮挡。",
            withInset > noInset,
        )
    }

    @Test
    fun `只改安全区也必须触发重新分页`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }

        val before = vm.pages.value.size

        // 关键：只改安全区、不动视口尺寸。
        // 若分页缓存键里漏了安全区，recomputePagination 会直接 return，页数原地不动。
        vm.setInsets(84, 48, density)
        waitUntil("安全区触发的重新分页") { vm.pages.value.size > before }

        val after = vm.pages.value.size
        assertTrue("只改安全区也必须重新分页：改前 $before 页，改后 $after 页", after > before)
    }

    @Test
    fun `重复上报相同安全区不会重复分页`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setInsets(84, 48, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }

        val pages = vm.pages.value
        val firstPage = pages.first()

        // 同样的值再报一次：不应触发重算（否则每帧上报都会重新排版，明显卡顿）
        vm.setInsets(84, 48, density)
        settle()

        assertEquals("页数不应变化", pages.size, vm.pages.value.size)
        assertEquals("首页区间不应变化", firstPage, vm.pages.value.first())
    }

    @Test
    fun `负的安全区被夹到 0`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val zero = pageCount(0, 0, bookId)
        val negative = pageCount(-100, -100, bookId)
        val normal = pageCount(84, 48, bookId)

        assertEquals("负的安全区必须被夹到 0，等价于无安全区", zero, negative)
        assertTrue("有安全区时页数应更多：normal=$normal zero=$zero", normal > zero)
    }
}
