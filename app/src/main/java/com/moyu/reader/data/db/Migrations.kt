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
