package com.moyu.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 数据访问对象。
 *
 * 约定：
 *   - 查询一律返回 Flow，让 UI 自动跟随数据变化（新增书签时列表立即刷新，无需手动通知）；
 *   - 写入用 REPLACE 语义（upsert），重复导入同一本书不会产生脏数据；
 *   - 章节的批量插入放在 @Transaction 里，避免「书已入库但章节只写了一半」的中间态 ——
 *     Web 端当初就是靠 reconcileChapterCounts 事后补救这类问题，Android 端从源头避免。
 */

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY last_read_at DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /**
     * 只观察**已加入书架**的书。
     *
     * 书架栏用它，书库栏用 [observeAll]。两个 Flow 分开写而不是在内存里 filter：
     * 书架通常只有几本到几十本，让 SQLite 用索引直接筛出来比每次
     * 拉全部书再过滤更省，而且书库涨到几百本时差别会很明显。
     */
    @Query("SELECT * FROM books WHERE in_shelf = 1 ORDER BY last_read_at DESC")
    fun observeInShelf(): Flow<List<BookEntity>>

    /** 切换「是否加入书架」。 */
    @Query("UPDATE books SET in_shelf = :inShelf WHERE id = :id")
    suspend fun setInShelf(id: String, inShelf: Boolean)

    /** 记录分章结果所用的规则版本。 */
    @Query("UPDATE books SET split_version = :version WHERE id = :id")
    suspend fun setSplitVersion(id: String, version: Int)

    @Query("SELECT COUNT(*) FROM books WHERE in_shelf = 1")
    fun observeShelfCount(): Flow<Int>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun findById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books ORDER BY last_read_at DESC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE books SET last_read_at = :timestamp WHERE id = :id")
    suspend fun touchLastRead(id: String, timestamp: Long)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: ReadingStatus)

    @Query("UPDATE books SET group_id = :groupId WHERE id = :id")
    suspend fun updateGroup(id: String, groupId: String?)

    /** 用同一标题 + 字数的组合判重，避免把同一本书导入两次。 */
    @Query("SELECT * FROM books WHERE title = :title AND char_count = :charCount LIMIT 1")
    suspend fun findByTitleAndLength(title: String, charCount: Int): BookEntity?
}

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY `index` ASC")
    suspend fun getByBook(bookId: String): List<ChapterEntity>

    /** 只取一章：大文件下这比整本加载快几个数量级。 */
    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND `index` = :index LIMIT 1")
    suspend fun getChapter(bookId: String, index: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND `index` = :index LIMIT 1")
    fun observeChapter(bookId: String, index: Int): Flow<ChapterEntity?>

    /** 章节列表只需要标题与偏移，绝不要把 content 也查出来。 */
    @Query("SELECT book_id, `index`, title, '' AS content, start, length, detected FROM chapters WHERE book_id = :bookId ORDER BY `index` ASC")
    suspend fun getChapterHeaders(bookId: String): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapters WHERE book_id = :bookId")
    suspend fun countByBook(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface ReadingPositionDao {

    @Query("SELECT * FROM reading_positions WHERE book_id = :bookId LIMIT 1")
    suspend fun find(bookId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_positions WHERE book_id = :bookId LIMIT 1")
    fun observe(bookId: String): Flow<ReadingPositionEntity?>

    @Query("SELECT * FROM reading_positions")
    fun observeAll(): Flow<List<ReadingPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE book_id = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY created_at DESC")
    suspend fun getByBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    suspend fun getAll(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeByBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY created_at DESC")
    suspend fun getByBook(bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights ORDER BY created_at DESC")
    suspend fun getAll(): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(highlight: HighlightEntity)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ReadingSessionDao {

    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId ORDER BY started_at DESC")
    fun observeByBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE started_at >= :since ORDER BY started_at ASC")
    suspend fun getSince(since: Long): List<ReadingSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity)

    @Query("DELETE FROM reading_sessions")
    suspend fun clearAll()
}

@Dao
interface BookGroupDao {

    @Query("SELECT * FROM book_groups ORDER BY created_at ASC")
    fun observeAll(): Flow<List<BookGroupEntity>>

    @Query("SELECT * FROM book_groups ORDER BY created_at ASC")
    suspend fun getAll(): List<BookGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: BookGroupEntity)

    @Query("DELETE FROM book_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除分组时把组内书籍的 group_id 清空，避免出现指向不存在分组的悬空引用。 */
    @Query("UPDATE books SET group_id = NULL WHERE group_id = :groupId")
    suspend fun clearGroupOnBooks(groupId: String)

    /**
     * 清空全部分组。
     *
     * 「清除全部数据」必须用它 —— 之前只删书不删分组：分组是用户建的，
     * 清空数据后仍然留在书架上，点进去却是空的，看起来像没清干净。
     */
    @Query("DELETE FROM book_groups")
    suspend fun clearAll()

    @Query("UPDATE books SET group_id = NULL")
    suspend fun clearAllGroupRefs()
}

@Dao
interface UserDictionaryDao {

    @Query("SELECT * FROM user_dictionaries ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<UserDictionaryEntity>>

    @Query("SELECT * FROM user_dictionaries ORDER BY imported_at DESC")
    suspend fun getAll(): List<UserDictionaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dictionary: UserDictionaryEntity)

    @Query("DELETE FROM user_dictionaries WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空用户导入的词典（「清除全部数据」要连它一起清）。 */
    @Query("DELETE FROM user_dictionaries")
    suspend fun clearAll()
}

/**
 * 跨表事务操作。
 *
 * 单独放一个 DAO 是为了让「多表一致性」有明确的归属地：
 * 凡是要么全成功、要么全回滚的写入都写在这里，不要在 ViewModel 里手拼事务。
 */
@Dao
interface TransactionDao {

    /** 保存书籍及其全部章节，并初始化阅读位置。任一步失败则整体回滚。 */
    @Transaction
    suspend fun saveBookWithChapters(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        initialPosition: ReadingPositionEntity,
        bookDao: BookDao,
        chapterDao: ChapterDao,
        positionDao: ReadingPositionDao,
    ) {
        bookDao.upsert(book)
        chapterDao.deleteByBook(book.id)
        chapterDao.insertAll(chapters)
        positionDao.upsert(initialPosition)
    }

    /** 删除分组并清理组内书籍的引用。 */
    @Transaction
    suspend fun deleteGroup(groupId: String, groupDao: BookGroupDao) {
        groupDao.clearGroupOnBooks(groupId)
        groupDao.deleteById(groupId)
    }

    /**
     * 用重切后的章节替换旧章节，并记录规则版本。任一步失败则整体回滚。
     *
     * 抽到这里而不是在仓储里手拼：多表一致性属于 DAO 的职责，
     * 而且 `@Transaction` 标注只在这个接口上生效。
     */
    @Transaction
    suspend fun replaceChapters(
        bookId: String,
        chapters: List<ChapterEntity>,
        splitVersion: Int,
        chapterDao: ChapterDao,
        bookDao: BookDao,
    ) {
        chapterDao.deleteByBook(bookId)
        chapterDao.insertAll(chapters)
        bookDao.setSplitVersion(bookId, splitVersion)
    }
}
