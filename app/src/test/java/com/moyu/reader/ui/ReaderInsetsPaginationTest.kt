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
 * 正文区高度参与分页的测试。
 *
 * ## 这条契约是怎么演变的（很重要，别退回旧写法）
 *
 * 最早的实现里，ViewModel 用「视口高 − 页边距 − 系统栏」**推算**可用高度。
 * 那个公式漏掉了版心内的天头书眉与地脚页码，于是分页按 28 行排、版心只装得下
 * 26 行 —— **每页悄悄少 2~3 行字**，用户根本察觉不到自己少读了。
 *
 * 现在纵向高度只有一个来源：**渲染侧实测的正文容器高度**。
 * 容器自己已经被页边距、系统栏安全区与地脚页码约束住了，
 * 所以 `setContentBoxSize` 报上来的数字天然与真实版心一致，
 * 不可能再和布局脱节。系统栏变高 → 容器变矮 → 页数变多，整条链路是自动的。
 *
 * 因此测试断言的是**新契约**：
 *   1. 容器越矮，页数越多（这是「安全区影响分页」的等价形式）；
 *   2. 容器高度变化必须触发重新分页（缓存键要包含它）；
 *   3. 章首页标题高度必须让首页少排几行（否则首页末行会被裁）。
 *
 * 测试方舟：Robolectric + 真实 Room（内存库）+ 真实 StaticLayout。
 * 注意 `viewModelScope` 绑定 Main dispatcher，必须显式推进 Main looper
 * （`shadowOf(...).idle()`）—— 用 Thread.sleep 等不到协程。
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
     * 不做换行（实测 16100 字在 948px 宽度下只排出 3 行），
     * 拿它验证「页数随高度变化」会得到恒为 1 页的假结果。
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

    private fun container(): AppContainer = object : AppContainer(context) {
        override val database: MoyuDatabase get() = db
    }

    private fun importBook(repo: BookRepository): String {
        val text = "第一章 开端\n$body\n第二章 继续\n$body\n"
        val result = runBlocking { repo.importFromText("分页测试.txt", text, "UTF-8") }
        assertTrue("导入应成功，实际 $result", result is ImportResult.Success)
        return (result as ImportResult.Success).bookId
    }

    /** 与真机接近：1080×2400 屏幕、3.0 密度。 */
    private val density = Density(density = 3f, fontScale = 1f)
    private val viewportWidth = 1080
    private val viewportHeight = 2200

    /**
     * 轮询等待条件成立，期间不断推进主线程。
     *
     * 仓库查询走 `withContext(Dispatchers.IO)`，在 Robolectric 里那是真实线程池，
     * 而 `idle()` 只推进主线程 —— 两者时序对不上，必须交替进行。
     */
    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) throw AssertionError("等待超时：$what")
    }

    private fun settle(times: Int = 20) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 打开书 → 设定视口与正文区高度 → 返回分页结果。每次都用全新 ViewModel。 */
    private fun pages(contentBoxHeight: Int, headerHeight: Int, bookId: String): Int {
        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setContentBoxSize(viewportWidth, contentBoxHeight, density)
        vm.setFirstPageHeaderHeight(headerHeight, density)
        waitUntil("分页完成") { vm.pages.value.isNotEmpty() }
        settle()
        return vm.pages.value.size
    }

    /** 模拟小米 14 量级的正文区：约 2380px（已扣页边距、系统栏与地脚）。 */
    private val realWorldBox = 2380

    @Test
    fun `正文区越矮页数越多（安全区通过容器高度影响分页）`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val tall = pages(contentBoxHeight = realWorldBox, headerHeight = 0, bookId = bookId)
        // 少掉状态栏与手势条的高度（约 150px）应当多分出一页
        val short = pages(contentBoxHeight = realWorldBox - 150, headerHeight = 0, bookId = bookId)

        assertTrue("足够长的正文应分出多页，实际 $tall", tall >= 2)
        assertTrue(
            "正文区变矮必须导致页数增加：$realWorldBox px → $tall 页，" +
                "${realWorldBox - 150} px → $short 页。页数没变说明容器高度没有进入分页计算。",
            short > tall,
        )
    }

    @Test
    fun `正文区高度变化必须触发重新分页`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setContentBoxSize(viewportWidth, realWorldBox, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }

        val before = vm.pages.value.size

        // 只改正文区高度，不动视口尺寸。
        // 若分页缓存键里漏了它，recomputePagination 会直接 return，页数原地不动。
        vm.setContentBoxSize(viewportWidth, realWorldBox - 200, density)
        waitUntil("高度变化触发的重新分页") { vm.pages.value.size > before }

        assertTrue("改高度前 $before 页，改后 ${vm.pages.value.size} 页", vm.pages.value.size > before)
    }

    @Test
    fun `章首页标题高度必须让首页少排几行`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        // 标题块约 177px（章节序号 + 大标题），相当于 2 行正文
        val withoutHeader = pages(contentBoxHeight = realWorldBox, headerHeight = 0, bookId = bookId)
        val withHeader = pages(contentBoxHeight = realWorldBox, headerHeight = 177, bookId = bookId)

        // 首页少排 2 行，整章页数应当增加（或至少首页覆盖的字符变少）
        assertTrue(
            "首页标题应占掉正文空间：无标题 $withoutHeader 页，有标题 $withHeader 页。" +
                "页数没变说明标题高度没有参与分页 —— 首页会多排几行，末行被裁掉。",
            withHeader >= withoutHeader,
        )

        // 更直接的证据：有标题时首页的字符区间必须更短
        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setContentBoxSize(viewportWidth, realWorldBox, density)
        vm.setFirstPageHeaderHeight(177, density)
        waitUntil("分页完成") { vm.pages.value.isNotEmpty() }
        val firstPageWithHeader = vm.pages.value.first()

        val vm2 = ReaderViewModel(container())
        vm2.open(bookId, density)
        waitUntil("章节载入") { vm2.currentChapter.value != null }
        vm2.setViewport(viewportWidth, viewportHeight, density)
        vm2.setContentBoxSize(viewportWidth, realWorldBox, density)
        vm2.setFirstPageHeaderHeight(0, density)
        waitUntil("分页完成") { vm2.pages.value.isNotEmpty() }
        val firstPageWithout = vm2.pages.value.first()

        assertTrue(
            "有标题时首页字符数必须更少：有标题 ${firstPageWithHeader.end - firstPageWithHeader.start} 字，" +
                "无标题 ${firstPageWithout.end - firstPageWithout.start} 字",
            (firstPageWithHeader.end - firstPageWithHeader.start) <
                (firstPageWithout.end - firstPageWithout.start),
        )
    }

    @Test
    fun `重复上报相同高度不会重复分页`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setContentBoxSize(viewportWidth, realWorldBox, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }

        val pages = vm.pages.value
        val first = pages.first()

        // 同样的值再报一次不应触发重算：Composable 每次重组都会上报，
        // 不做短路就会每帧重新排版，翻页明显卡顿。
        vm.setContentBoxSize(viewportWidth, realWorldBox, density)
        settle()

        assertEquals("页数不应变化", pages.size, vm.pages.value.size)
        assertEquals("首页区间不应变化", first, vm.pages.value.first())
    }

    @Test
    fun `高度为 0 的上报被忽略，不会把分页搞坏`() {
        val repo = BookRepository(context, db, DocumentStore(context))
        val bookId = importBook(repo)

        val vm = ReaderViewModel(container())
        vm.open(bookId, density)
        waitUntil("章节载入") { vm.currentChapter.value != null }
        vm.setViewport(viewportWidth, viewportHeight, density)
        vm.setContentBoxSize(viewportWidth, realWorldBox, density)
        waitUntil("首次分页") { vm.pages.value.isNotEmpty() }

        val before = vm.pages.value.size

        // 布局尚未完成时会报 0，这种情况必须被忽略（否则会退化成按 1px 高度分页）
        vm.setContentBoxSize(viewportWidth, 0, density)
        settle()

        assertEquals("高度 0 的上报应被忽略", before, vm.pages.value.size)
    }
}
