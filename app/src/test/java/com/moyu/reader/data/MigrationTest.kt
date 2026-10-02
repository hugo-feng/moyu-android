package com.moyu.reader.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.moyu.reader.data.db.MIGRATION_1_2
import com.moyu.reader.data.db.MIGRATION_2_3
import com.moyu.reader.data.db.MoyuDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试。
 *
 * 关键设计：v1 库的建表 SQL **不是我手写的**，而是直接读 Room 自己导出的
 * `app/schemas/com.moyu.reader.data.db.MoyuDatabase/1.json`。
 *
 * 这一点很重要。迁移测试最常见的失败模式，是作者凭记忆手抄一份旧表结构，
 * 抄错一列的类型或 NOT NULL，于是测试要么永远红、要么在错误的前提下变绿。
 * 直接消费导出的 schema 就没有这个问题：那份 JSON 是 Room 在构建期
 * 依据真实实体生成的，是 v1 表结构唯一的权威来源。
 *
 * 为什么不用 `MigrationTestHelper`：它同样要读这份 schema，但要求把 schema
 * 复制进 androidTest 的 assets 并改写 sourceSets，链路更长；而本测试跑在
 * Robolectric（JVM）上，直接用文件路径读更直接，报错也更清楚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbName = "migration-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 定位导出的 v1 schema。Gradle 测试的工作目录是模块目录（app/）。 */
    private fun schemaFile(): File {
        val candidates = listOf(
            File("schemas/com.moyu.reader.data.db.MoyuDatabase/1.json"),
            File("app/schemas/com.moyu.reader.data.db.MoyuDatabase/1.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到导出的 v1 schema，已尝试：" +
                    candidates.joinToString { it.absolutePath } +
                    "（当前工作目录：${File(".").absolutePath}）",
            )
    }

    /**
     * 按 v1 schema 建库并写入夹具数据。
     *
     * 夹具刻意包含**两本书**与**两条阅读位置**：
     *   - b1：书存在，位置存在 → 迁移后位置必须原样保留；
     *   - ghost：位置指向一本不存在的书（v1 没有外键，所以这是允许的脏数据）
     *     → 迁移必须清掉它，否则加外键时 INSERT 会失败、整个迁移中断。
     */
    private fun createV1Database() {
        val entities = JSONObject(schemaFile().readText()).getJSONObject("database")
            .getJSONArray("entities")

        // 先把 schema 里的建表语句收集出来（回调里不能抛受检异常，故提前解析）
        val ddl = buildList {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                add(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (k in 0 until indices.length()) {
                        add(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                }
            }
        }

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    ddl.forEach(db::execSQL)
                    db.execSQL(
                        "INSERT INTO `books` (`id`,`title`,`author`,`intro`,`format`,`encoding`," +
                            "`cover_path`,`char_count`,`chapter_count`,`source_length`,`group_id`,`status`," +
                            "`source_uri`,`source_modified`,`added_at`,`last_read_at`) " +
                            "VALUES ('b1','活着的书','佚名','','TXT','UTF-8',NULL,100,2,100,NULL,'READING'," +
                            "NULL,0,1,2)",
                    )
                    db.execSQL(
                        "INSERT INTO `reading_positions` (`book_id`,`chapter_index`,`chapter_offset`," +
                            "`global_offset`,`page_index`,`percent`,`updated_at`) " +
                            "VALUES ('b1',1,42,142,3,0.25,999)",
                    )
                    db.execSQL(
                        "INSERT INTO `reading_positions` (`book_id`,`chapter_index`,`chapter_offset`," +
                            "`global_offset`,`page_index`,`percent`,`updated_at`) " +
                            "VALUES ('ghost',0,0,0,0,0.5,1)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).let { helper ->
            helper.writableDatabase.use { /* 触发 onCreate */ }
            helper.close()
        }
    }

    @Test
    fun `migration 1 to 2 keeps real positions and drops orphan rows`() = runBlocking {
        createV1Database()

        val db = Room.databaseBuilder(context, MoyuDatabase::class.java, dbName)
            .allowMainThreadQueries()
            // 两条迁移都要登记：目标 schema 是 v3，Room 需要一条
            // 从 v1 一路走到 v3 的完整路径。只给 1→2 会直接报
            // 「A migration from 1 to 3 was required but not found」。
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        try {
            // 打开即迁移。Room 会逐表校验迁移后的结构，任何不符都会抛
            // IllegalStateException —— 所以能走到下面的断言，本身就说明 schema 是对的。
            val position = db.readingPositionDao().find("b1")
            assertNotNull("b1 的阅读位置必须被保留下来", position)
            assertEquals(1, position!!.chapterIndex)
            assertEquals(42, position.chapterOffset)
            assertEquals(142, position.globalOffset)
            assertEquals(3, position.pageIndex)
            assertEquals(0.25f, position.percent, 0.0001f)
            assertEquals(999L, position.updatedAt)

            assertNull("指向不存在的书的孤儿行必须被清掉", db.readingPositionDao().find("ghost"))

            // 迁移补上的外键必须真的生效：删书要连位置一起删
            db.bookDao().deleteById("b1")
            assertNull("迁移后外键应生效：删书级联删除阅读位置", db.readingPositionDao().find("b1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 2 to 3 adds in_shelf and keeps existing books`() = runBlocking {
        createV1Database()

        // 从 v1 一路迁到 v3：同时验证 1→2 与 2→3 能串联。
        // 真实用户的升级路径就是这样，只测单步会漏掉「链式中断」。
        val db = Room.databaseBuilder(context, MoyuDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        try {
            // v1 里插过一本书，迁移后必须还在
            val books = db.bookDao().getAll()
            assertTrue("迁移后书不应丢失", books.isNotEmpty())

            /**
             * `in_shelf` 的默认值必须是 **false**（不在书架）。
             *
             * 这是刻意的：老库里的书如果默认 true，迁移后「书架」里仍是全部书，
             * 用户会觉得新功能没生效；默认 false 则状态符合「加入了才在书架里」
             * 这个约定，书都在书库，用户自己整理。
             */
            assertTrue(
                "迁移后老书应默认不在书架（in_shelf = false）",
                books.all { !it.inShelf },
            )

            // 加入书架后应当只出现在书架查询里
            val id = books.first().id
            db.bookDao().setInShelf(id, true)
            assertEquals("书架里应有 1 本", 1, db.bookDao().observeShelfCount().first())

            db.bookDao().setInShelf(id, false)
            assertEquals("移出后书架应为空", 0, db.bookDao().observeShelfCount().first())
        } finally {
            db.close()
        }
    }
}
