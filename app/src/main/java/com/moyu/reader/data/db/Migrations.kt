package com.moyu.reader.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 版本 1 → 2：给 reading_positions 补上指向 books 的级联外键。
 *
 * 为什么必须写迁移而不是直接改 schema：
 *   1. 数据库里已经有用户的书库与阅读进度，改了 schema 却不写迁移，
 *      Room 会在打开时抛 IllegalStateException —— 表现为「更新后应用打不开」。
 *   2. reading_positions 存的是「读到哪儿」，是用户最在意的那点数据。
 *      这里刻意**把已有行全部搬过去**，而不是删表重建 —— 重建会让所有人
 *      的阅读进度归零，那是比孤儿行严重得多的伤害。
 *
 * SQLite 不支持 ALTER TABLE 添加外键，只能新建表 → 搬数据 → 换名，
 * 且必须临时关闭外键约束（否则 DROP 旧表时会触发级联，把刚搬过去的数据一起删掉）。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reading_positions_new` (
                `book_id` TEXT NOT NULL,
                `chapter_index` INTEGER NOT NULL,
                `chapter_offset` INTEGER NOT NULL,
                `global_offset` INTEGER NOT NULL,
                `page_index` INTEGER NOT NULL,
                `percent` REAL NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`),
                FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        // 只搬「父书还在」的行：顺带清掉历史版本留下的孤儿行。
        // 没有这一步，外键约束会让 INSERT 直接失败，整个迁移中断。
        db.execSQL(
            """
            INSERT OR REPLACE INTO `reading_positions_new`
                (`book_id`, `chapter_index`, `chapter_offset`, `global_offset`, `page_index`, `percent`, `updated_at`)
            SELECT `book_id`, `chapter_index`, `chapter_offset`, `global_offset`, `page_index`, `percent`, `updated_at`
            FROM `reading_positions`
            WHERE `book_id` IN (SELECT `id` FROM `books`)
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE IF EXISTS `reading_positions`")
        db.execSQL("ALTER TABLE `reading_positions_new` RENAME TO `reading_positions`")
    }
}

/**
 * 版本 2 → 3：books 增加 `in_shelf`（是否已加入书架）。
 *
 * 引入「书库 / 书架」两分之后，导入的书先落在书库，
 * 只有用户主动加入才进书架。老数据没有这个字段，
 * 因此迁移时给一个默认值 —— 而默认值选什么是**有讲究**的。
 *
 * ## 为什么老书默认「不在书架」
 *
 * 直觉上可能想给 true（老用户的书本来就都在书架上），但那样
 * 迁移之后「书架」里仍然是全部书，用户看到的是一个没有任何变化的应用 ——
 * 新功能等于没生效，还得自己一本本移出去。
 *
 * 反过来默认 false，用户会看到书架空了、书都在书库里。
 * 这虽然也需要一次整理，但至少**状态是符合预期的**：
 * 加入了才在书架里。
 *
 * 取舍的依据是「哪种误解代价更小」：书架空着只是多一步操作；
 * 而以为功能没生效会让人反复找、甚至以为数据丢了。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `in_shelf` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * 版本 3 → 4：books 增加 `split_version`（分章结果所用的规则版本）。
 *
 * 默认 0 = 「早于版本化之前导入的书」。这些书的章节是旧规则切的，
 * 打开时会触发一次重新分章 —— 这正是我们要的效果：
 * 用户升级后不必删书重导，旧书自动用新规则重切。
 *
 * 为什么不在迁移里直接重算：迁移跑在数据库打开时，此时还没有
 * BookRepository（它依赖数据库本身），也拿不到解析器。
 * 而且重算是逐本的、可能耗时，放在迁移里会拖慢冷启动。
 * 改成「打开某本书时检查并重算」，代价分散且用户无感。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `split_version` INTEGER NOT NULL DEFAULT 0",
        )
    }
}
