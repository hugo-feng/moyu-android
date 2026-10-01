package com.moyu.reader.reader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReadingStatsCalculator 的纯 JVM 单元测试。
 *
 * 覆盖：本地时区按天聚合、单次会话 4 小时截断、当前/最长连续天数（今天没读不算断签）、
 * heatLevel 阈值、formatSeconds 文案。
 */
class ReadingStatsCalculatorTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val utc = ZoneId.of("UTC")

    private fun stat(date: String, seconds: Int) = ReadingStatsCalculator.DailyStat(
        date = date,
        seconds = seconds,
        chars = seconds * 5,
        bookCount = 1,
        sessions = 1,
    )

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ------------------------------------------------------- 本地日期聚合

    @Test
    fun `daily aggregation groups by LOCAL date not UTC`() {
        // 2024-03-05T20:00:00Z = 上海时间 2024-03-06 04:00，UTC 下仍是 03-05
        val session = Triple(millis("2024-03-05T20:00:00Z"), 600, 100)

        val cn = ReadingStatsCalculator.aggregateDaily(listOf(session), shanghai)
        assertEquals(1, cn.size)
        assertEquals("2024-03-06", cn[0].date)

        val z = ReadingStatsCalculator.aggregateDaily(listOf(session), utc)
        assertEquals(1, z.size)
        assertEquals("2024-03-05", z[0].date)

        // localDateKey 本身也按给定时区换算
        assertEquals("2024-03-06", ReadingStatsCalculator.localDateKey(session.first, shanghai))
        assertEquals("2024-03-05", ReadingStatsCalculator.localDateKey(session.first, utc))
    }

    @Test
    fun `daily aggregation uses the JVM default zone when no zone is given`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val session = Triple(millis("2024-03-05T20:00:00Z"), 600, 100)
            val stat = ReadingStatsCalculator.aggregateDaily(listOf(session))
            assertEquals("default zone must be honoured", "2024-03-06", stat[0].date)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `aggregation merges sessions of the same local day and skips non-positive durations`() {
        val a = Triple(millis("2024-03-06T01:00:00Z"), 600, 100)
        val b = Triple(millis("2024-03-06T09:00:00Z"), 900, 200)
        val zero = Triple(millis("2024-03-06T10:00:00Z"), 0, 500)
        val negative = Triple(millis("2024-03-06T11:00:00Z"), -30, 500)

        val daily = ReadingStatsCalculator.aggregateDaily(listOf(a, b, zero, negative), shanghai)
        assertEquals(1, daily.size)
        assertEquals(1500, daily[0].seconds)
        assertEquals(300, daily[0].chars)
        assertEquals(2, daily[0].sessions)
    }

    // ------------------------------------------------------- 4 小时截断

    @Test
    fun `a session longer than 4 hours is clamped to 4 hours`() {
        val marathons = Triple(millis("2024-03-06T01:00:00Z"), 10 * 3600, 100)
        val shortOne = Triple(millis("2024-03-06T09:00:00Z"), 600, 50)

        val daily = ReadingStatsCalculator.aggregateDaily(listOf(marathons, shortOne), shanghai)
        assertEquals(1, daily.size)
        assertEquals(
            ReadingStatsCalculator.MAX_SESSION_SECONDS + 600,
            daily[0].seconds,
        )
        assertEquals(4 * 3600, ReadingStatsCalculator.MAX_SESSION_SECONDS)

        val stats = ReadingStatsCalculator.buildStats(listOf(marathons, shortOne), today = LocalDate.parse("2024-03-06"), zone = shanghai)
        assertEquals(4 * 3600 + 600, stats.totalSeconds)
    }

    // ------------------------------------------------------- 连续天数

    @Test
    fun `current streak counts back from today`() {
        val today = LocalDate.parse("2024-03-10")
        val daily = listOf(stat("2024-03-08", 100), stat("2024-03-09", 100), stat("2024-03-10", 100))
        assertEquals(3 to 3, ReadingStatsCalculator.computeStreaks(daily, today))
    }

    @Test
    fun `missing today is NOT a broken streak - it counts from yesterday`() {
        val today = LocalDate.parse("2024-03-10")
        val daily = listOf(stat("2024-03-08", 100), stat("2024-03-09", 100))
        val (current, longest) = ReadingStatsCalculator.computeStreaks(daily, today)
        assertEquals("yesterday-anchored streak", 2, current)
        assertEquals(2, longest)
    }

    @Test
    fun `a real gap yields current streak 0`() {
        val today = LocalDate.parse("2024-03-10")
        val daily = listOf(stat("2024-03-01", 100), stat("2024-03-02", 100), stat("2024-03-03", 100))
        val (current, longest) = ReadingStatsCalculator.computeStreaks(daily, today)
        assertEquals(0, current)
        assertEquals(3, longest)
    }

    @Test
    fun `longest streak is computed across a gap`() {
        val today = LocalDate.parse("2024-02-03")
        val daily = listOf(
            stat("2024-01-01", 100), stat("2024-01-02", 100), stat("2024-01-03", 100),
            stat("2024-01-04", 100), stat("2024-01-05", 100),
            stat("2024-02-01", 100), stat("2024-02-02", 100), stat("2024-02-03", 100),
        )
        val (current, longest) = ReadingStatsCalculator.computeStreaks(daily, today)
        assertEquals(3, current)
        assertEquals(5, longest)
    }

    @Test
    fun `duplicate and zero-second days do not inflate streaks`() {
        val today = LocalDate.parse("2024-03-10")
        val daily = listOf(
            stat("2024-03-09", 100), stat("2024-03-09", 50),
            stat("2024-03-10", 0),
        )
        val (current, longest) = ReadingStatsCalculator.computeStreaks(daily, today)
        // 今天 sec>0 的条目不存在 -> 从昨天起算，只有 03-09 一天
        assertEquals(1, current)
        assertEquals(1, longest)
    }

    @Test
    fun `no sessions means zero streaks`() {
        assertEquals(0 to 0, ReadingStatsCalculator.computeStreaks(emptyList(), LocalDate.parse("2024-03-10")))
    }

    // ------------------------------------------------------- heatLevel

    @Test
    fun `heatLevel thresholds`() {
        assertEquals(0, ReadingStatsCalculator.heatLevel(0))
        assertEquals(0, ReadingStatsCalculator.heatLevel(-1))
        assertEquals(1, ReadingStatsCalculator.heatLevel(1))
        assertEquals(1, ReadingStatsCalculator.heatLevel(599))
        assertEquals(2, ReadingStatsCalculator.heatLevel(600))
        assertEquals(2, ReadingStatsCalculator.heatLevel(1799))
        assertEquals(3, ReadingStatsCalculator.heatLevel(1800))
        assertEquals(3, ReadingStatsCalculator.heatLevel(3599))
        assertEquals(4, ReadingStatsCalculator.heatLevel(3600))
        assertEquals(4, ReadingStatsCalculator.heatLevel(100_000))
    }

    // ------------------------------------------------------- formatSeconds

    @Test
    fun `formatSeconds wording`() {
        assertEquals("0 秒", ReadingStatsCalculator.formatSeconds(0))
        assertEquals("0 秒", ReadingStatsCalculator.formatSeconds(-5))
        assertEquals("59 秒", ReadingStatsCalculator.formatSeconds(59))
        assertEquals("1 分钟", ReadingStatsCalculator.formatSeconds(60))
        assertEquals("59 分钟", ReadingStatsCalculator.formatSeconds(3599))
        assertEquals("1 小时", ReadingStatsCalculator.formatSeconds(3600))
        assertEquals("1 小时 1 分", ReadingStatsCalculator.formatSeconds(3660))
        assertEquals("2 小时", ReadingStatsCalculator.formatSeconds(7200))
        assertEquals("2 小时 30 分", ReadingStatsCalculator.formatSeconds(9000))
    }

    // ------------------------------------------------------- 汇总

    @Test
    fun `buildStats aggregates totals windows and reading speed`() {
        val today = LocalDate.parse("2024-03-10")
        // 03-10（今天，上海时间）= 01:00Z 起 1800 秒；03-04 = 06 天前；01-20 = 超出 30 天窗口
        val sessions = listOf(
            Triple(millis("2024-03-10T01:00:00Z"), 1800, 900),
            Triple(millis("2024-03-04T01:00:00Z"), 600, 300),
            Triple(millis("2024-01-20T01:00:00Z"), 600, 300),
        )
        val stats = ReadingStatsCalculator.buildStats(sessions, today = today, zone = shanghai)
        assertEquals(3000, stats.totalSeconds)
        assertEquals(1500, stats.totalChars)
        assertEquals(3, stats.totalSessions)
        assertEquals(3, stats.totalDays)
        assertEquals(1000, stats.averageSecondsPerActiveDay)
        assertEquals("last 7 days", 2400, stats.last7DaysSeconds)
        assertEquals("last 30 days window", 2400, stats.last30DaysSeconds)
        // 速度只统计 >= 30 秒的会话：(900+300+300)*60 / (1800+600+600) = 90000/3000 = 30
        assertEquals(30, stats.charsPerMinute)
        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
    }

    @Test
    fun `buildStats filters sessions shorter than 30 seconds out of the speed metric`() {
        val today = LocalDate.parse("2024-03-10")
        val sessions = listOf(
            Triple(millis("2024-03-10T01:00:00Z"), 600, 300),
            Triple(millis("2024-03-10T02:00:00Z"), 10, 9999),
        )
        val stats = ReadingStatsCalculator.buildStats(sessions, today = today, zone = shanghai)
        assertEquals(30, stats.charsPerMinute)
        assertEquals(610, stats.totalSeconds)
        assertEquals(10299, stats.totalChars)
    }

    @Test
    fun `heatLevel empty day and insights`() {
        val stats = ReadingStatsCalculator.buildStats(emptyList(), today = LocalDate.parse("2024-03-10"), zone = shanghai)
        assertEquals(0, stats.totalSeconds)
        assertEquals(0, stats.charsPerMinute)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
        assertTrue(ReadingStatsCalculator.buildInsights(emptyList()).isNotEmpty())
    }
}
