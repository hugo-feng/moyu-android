package com.moyu.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * 枚举 ↔ 字符串的转换器。
 *
 * 用显式转换器而不是 `@TypeConverters(Enums::class)` 的通用方案：
 * 枚举名入库（如 "GB18030"），比存序号（ordinal）安全得多 ——
 * 日后在枚举中间插入新值，存序号的旧数据会整体错位，而存名字不会。
 */
class Converters {

    @TypeConverter
    fun bookFormatToString(value: BookFormat): String = value.name

    @TypeConverter
    fun stringToBookFormat(value: String): BookFormat =
        runCatching { BookFormat.valueOf(value) }.getOrDefault(BookFormat.TXT)

    @TypeConverter
    fun readingStatusToString(value: ReadingStatus): String = value.name

    @TypeConverter
    fun stringToReadingStatus(value: String): ReadingStatus =
        runCatching { ReadingStatus.valueOf(value) }.getOrDefault(ReadingStatus.UNREAD)
}

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingPositionEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        ReadingSessionEntity::class,
        BookGroupEntity::class,
        UserDictionaryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MoyuDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun userDictionaryDao(): UserDictionaryDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val DB_NAME = "moyu.db"

        /**
         * 建库。
         *
         * 刻意**不开启** `fallbackToDestructiveMigration`：那会在版本升级时静默清空用户书库，
         * 对阅读应用是不可接受的。改由调用方在 onUpgrade 未覆盖时显式提供迁移，
         * 让「忘记写迁移」在开发期就暴露出来。
         */
        fun build(context: Context): MoyuDatabase =
            Room.databaseBuilder(context.applicationContext, MoyuDatabase::class.java, DB_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        /** 所有历史迁移，按版本递增。新增迁移必须登记到这里，否则运行时会漏掉。 */
        private val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
