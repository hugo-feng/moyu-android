package com.moyu.reader.data.repository

import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.model.ReadingSession
import com.moyu.reader.data.toEntity
import com.moyu.reader.data.toModel
import com.moyu.reader.reader.ReadingStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 阅读统计仓储。
 *
 * 会话的**结算**放在这里而不是 ViewModel：会话时长的计算规则
 * （最少 5 秒、单次上限 4 小时）属于业务规则，散在界面层会导致
 * 「阅读器退出时算一次、切后台时又算一次」这类重复计数问题。
 */
class StatsRepository(private val database: MoyuDatabase) {

    private val sessionDao get() = database.readingSessionDao()

    val sessions: Flow<List<ReadingSession>> =
        sessionDao.observeAll().map { list -> list.map { it.toModel() } }

    fun sessionsOf(bookId: String): Flow<List<ReadingSession>> =
        sessionDao.observeByBook(bookId).map { list -> list.map { it.toModel() } }

    suspend fun getAllSessions(): List<ReadingSession> = withContext(Dispatchers.IO) {
        sessionDao.getAll().map { it.toModel() }
    }

    /**
     * 记录一次阅读会话。
     *
     * @param durationSec 持续秒数
     * @param charCount 本次阅读字数
     * @return 是否真的记录（时长过短会被丢弃）
     */
    suspend fun recordSession(bookId: String, durationSec: Int, charCount: Int): Boolean =
        withContext(Dispatchers.IO) {
            // 少于 5 秒通常只是误触，不计入统计，避免污染「阅读天数」
            if (durationSec < MIN_SESSION_SECONDS) return@withContext false

            sessionDao.insert(
                ReadingSession(
                    id = "s_" + UUID.randomUUID().toString().replace("-", "").take(16),
                    bookId = bookId,
                    startedAt = System.currentTimeMillis() - durationSec * 1000L,
                    durationSec = minOf(durationSec, ReadingStatsCalculator.MAX_SESSION_SECONDS),
                    charCount = maxOf(0, charCount),
                ).toEntity()
            )
            true
        }

    /** 直接给出统计汇总（界面只订阅一次，避免在 Compose 里做重计算）。 */
    suspend fun computeStats(bookId: String? = null) = withContext(Dispatchers.IO) {
        val all = sessionDao.getAll()
        val filtered = if (bookId == null) all else all.filter { it.bookId == bookId }
        ReadingStatsCalculator.buildStats(
            filtered.map { Triple(it.startedAt, it.durationSec, it.charCount) }
        )
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { sessionDao.clearAll() }

    companion object {
        /** 少于这个秒数的会话不记录。 */
        const val MIN_SESSION_SECONDS = 5
    }
}
