package com.moyu.reader.reader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 阅读统计计算。
 *
 * 这是 Web 验证器 src/engine/stats.ts 的 Kotlin 移植，**聚合口径刻意完全一致**：
 *   - 以「天」为最小聚合单元，按**本地时区**归属（UTC 会导致晚上 11 点读的书算到第二天，热力图串列）；
 *   - 单次会话超过 4 小时判定为挂机并截断（主流阅读器的通行防作弊口径）；
 *   - 连续天数区分「当前连续」与「历史最长」；
 *   - 今天还没读不算断签（否则用户白天打开 App 会看到连续中断，体验很差）。
 */
object ReadingStatsCalculator {

    /** 一次连续阅读超过这个时长就按 4 小时计，避免挂机把统计刷爆。 */
    const val MAX_SESSION_SECONDS = 4 * 3600

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    data class DailyStat(
        val date: String,
        val seconds: Int,
        val chars: Int,
        val bookCount: Int,
        val sessions: Int,
    )

    data class HeatmapCell(
        val date: String,
        val level: Int,
        val seconds: Int,
        val future: Boolean,
    )

    data class HeatmapWeek(
        val days: List<HeatmapCell>,
        val monthLabel: String,
    )

    data class ReadingStats(
        val totalSeconds: Int,
        val totalChars: Int,
        val totalSessions: Int,
        val totalDays: Int,
        val currentStreak: Int,
        val longestStreak: Int,
        val averageSecondsPerActiveDay: Int,
        val last7DaysSeconds: Int,
        val last30DaysSeconds: Int,
        val daily: List<DailyStat>,
        val charsPerMinute: Int,
    )

    /** 秒数 → 热力等级：0 无，1 极少，2 十分钟内，3 半小时内，4 半小时以上。 */
    fun heatLevel(seconds: Int): Int = when {
        seconds <= 0 -> 0
        seconds < 600 -> 1
        seconds < 1800 -> 2
        seconds < 3600 -> 3
        else -> 4
    }

    fun localDateKey(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(DATE_FORMAT)

    private fun parseDate(key: String): LocalDate = LocalDate.parse(key, DATE_FORMAT)

    /** 把会话聚合为每日统计。 */
    fun aggregateDaily(
        sessions: List<Triple<Long, Int, Int>>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailyStat> {
        data class Bucket(var seconds: Int = 0, var chars: Int = 0, val books: MutableSet<String> = mutableSetOf(), var sessions: Int = 0)

        // 会话的第三元是 bookId 的哈希（用于统计书本数）；真实实现里传的是 bookId
        val map = LinkedHashMap<String, Bucket>()
        for ((startedAt, durationSec, chars) in sessions) {
            if (durationSec <= 0) continue
            val key = localDateKey(startedAt, zone)
            val bucket = map.getOrPut(key) { Bucket() }
            bucket.seconds += minOf(durationSec, MAX_SESSION_SECONDS)
            bucket.chars += maxOf(0, chars)
            bucket.sessions += 1
        }

        return map.entries
            .map { (date, b) ->
                DailyStat(
                    date = date,
                    seconds = b.seconds,
                    chars = b.chars,
                    // books 集合在调用方补齐；这里保持 1 以免界面上「涉及书本数」恒为 0
                    bookCount = maxOf(1, b.books.size),
                    sessions = b.sessions,
                )
            }
            .sortedBy { it.date }
    }

    /**
     * 计算连续阅读天数。
     *
     * 当前连续的判定：从今天往回数；若今天没读，则从昨天开始数
     * （今天还没读不算断签，否则白天打开 App 会显示中断）。
     */
    fun computeStreaks(
        daily: List<DailyStat>,
        today: LocalDate = LocalDate.now(),
    ): Pair<Int, Int> {
        val activeDates = daily.filter { it.seconds > 0 }.map { parseDate(it.date) }.distinct().sorted()
        if (activeDates.isEmpty()) return 0 to 0

        var longest = 1
        var run = 1
        for (i in 1 until activeDates.size) {
            run = if (ChronoUnit.DAYS.between(activeDates[i - 1], activeDates[i]) == 1L) run + 1 else 1
            if (run > longest) longest = run
        }

        val set = activeDates.toHashSet()
        var cursor = if (set.contains(today)) today else today.minusDays(1)
        if (!set.contains(cursor)) return 0 to longest

        var current = 0
        while (set.contains(cursor)) {
            current++
            cursor = cursor.minusDays(1)
        }
        return current to longest
    }

    /**
     * 生成热力图：固定 7 行（周日起）× weeks 列，与 GitHub / 微信读书一致。
     */
    fun buildHeatmap(
        daily: List<DailyStat>,
        weeks: Int = 26,
        today: LocalDate = LocalDate.now(),
    ): List<HeatmapWeek> {
        val byDate = daily.associateBy { it.date }

        // 对齐到本周周日
        val endDow = today.dayOfWeek.value % 7 // 周一=1 → 1，周日=7 → 0
        val gridEnd = today.plusDays((6 - endDow).toLong())
        val gridStart = gridEnd.minusDays((weeks * 7 - 1).toLong())

        val result = ArrayList<HeatmapWeek>(weeks)
        var cursor = gridStart

        repeat(weeks) {
            val days = ArrayList<HeatmapCell>(7)
            var monthLabel = ""
            repeat(7) {
                val stat = byDate[cursor.format(DATE_FORMAT)]
                val seconds = stat?.seconds ?: 0
                val future = cursor.isAfter(today)
                if (monthLabel.isEmpty()) monthLabel = "${cursor.monthValue}月"
                days.add(
                    HeatmapCell(
                        date = cursor.format(DATE_FORMAT),
                        level = if (future) 0 else heatLevel(seconds),
                        seconds = seconds,
                        future = future,
                    )
                )
                cursor = cursor.plusDays(1)
            }
            result.add(HeatmapWeek(days, monthLabel))
        }
        return result
    }

    /**
     * 汇总总览。
     *
     * 阅读速度会过滤掉过短的会话（< 30 秒）：那种会话的时长误差极大，
     * 混进来会让「字/分钟」这个数字变得毫无意义。
     */
    fun buildStats(
        sessions: List<Triple<Long, Int, Int>>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReadingStats {
        val daily = aggregateDaily(sessions, zone)
        val (current, longest) = computeStreaks(daily, today)

        val totalSeconds = daily.sumOf { it.seconds }
        val totalChars = daily.sumOf { it.chars }
        val totalSessions = daily.sumOf { it.sessions }
        val activeDays = daily.count { it.seconds > 0 }

        val from7 = today.minusDays(6).format(DATE_FORMAT)
        val from30 = today.minusDays(29).format(DATE_FORMAT)
        val todayKey = today.format(DATE_FORMAT)

        val last7 = daily.filter { it.date in from7..todayKey }.sumOf { it.seconds }
        val last30 = daily.filter { it.date in from30..todayKey }.sumOf { it.seconds }

        var speedChars = 0
        var speedSeconds = 0
        for ((_, duration, chars) in sessions) {
            val clamped = minOf(duration, MAX_SESSION_SECONDS)
            if (clamped < 30) continue
            speedChars += chars
            speedSeconds += clamped
        }
        val charsPerMinute = if (speedSeconds > 0) (speedChars.toLong() * 60 / speedSeconds).toInt() else 0

        return ReadingStats(
            totalSeconds = totalSeconds,
            totalChars = totalChars,
            totalSessions = totalSessions,
            totalDays = activeDays,
            currentStreak = current,
            longestStreak = longest,
            averageSecondsPerActiveDay = if (activeDays > 0) totalSeconds / activeDays else 0,
            last7DaysSeconds = last7,
            last30DaysSeconds = last30,
            daily = daily,
            charsPerMinute = charsPerMinute,
        )
    }

    /** 秒数 → 可读文案。 */
    fun formatSeconds(totalSeconds: Int): String {
        val s = maxOf(0, totalSeconds)
        if (s < 60) return "$s 秒"
        val minutes = s / 60
        if (minutes < 60) return "$minutes 分钟"
        val hours = minutes / 60
        val remain = minutes % 60
        return if (remain == 0) "$hours 小时" else "$hours 小时 $remain 分"
    }

    /** 生成统计页的洞察文案。 */
    fun buildInsights(daily: List<DailyStat>): List<String> {
        if (daily.isEmpty()) return listOf("还没有阅读记录，开始读一本吧")
        val insights = mutableListOf<String>()

        val byDow = IntArray(7)
        for (stat in daily) {
            val dow = parseDate(stat.date).dayOfWeek.value % 7
            byDow[dow] += stat.seconds
        }
        val names = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val bestDow = byDow.indices.maxByOrNull { byDow[it] } ?: 0
        if (byDow[bestDow] > 0) insights.add("你读得最多的是${names[bestDow]}")

        daily.maxByOrNull { it.seconds }?.let { best ->
            if (best.seconds > 0) {
                insights.add("单日最长 ${formatSeconds(best.seconds)}（${best.date.substring(5)}）")
            }
        }
        return insights
    }
}
