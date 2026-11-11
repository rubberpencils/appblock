package xyz.robinroy.appblock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.robinroy.appblock.Storage.Period
import xyz.robinroy.appblock.Storage.Session
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.ZoneId

/**
 * Screen time over a week, a month or a year: which days each one is,
 * stepping from one to the next, what it's called, and its bars, total,
 * average and busiest day. Today is Thursday 12 November 2026 throughout.
 */
class PeriodTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 12)
    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"
    private val Int.min get() = this * 60_000L
    private fun nov(d: Int) = LocalDate.of(2026, 11, d)
    private fun oct(d: Int) = LocalDate.of(2026, 10, d)

    /** `pkg` on screen on `day` from `h`:`m` for `minutes`. */
    private fun used(pkg: String, day: LocalDate, h: Int, m: Int, minutes: Int): Session {
        val start = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        return Session(pkg, start, start + minutes.min)
    }

    private fun mins(vararg m: Int) = LongArray(m.size) { m[it].min }

    // ---- which days ----

    @Test
    fun `a week starts on the locale's day, a month on the 1st, a year on 1 January`() {
        assertEquals(today to today, Storage.periodDays(Period.DAY, today, MONDAY))
        assertEquals(nov(9) to nov(15), Storage.periodDays(Period.WEEK, today, MONDAY))
        assertEquals(nov(8) to nov(14), Storage.periodDays(Period.WEEK, today, SUNDAY))
        assertEquals(nov(7) to nov(13), Storage.periodDays(Period.WEEK, today, SATURDAY))
        // Its first day and its last are in it too.
        assertEquals(nov(9) to nov(15), Storage.periodDays(Period.WEEK, nov(9), MONDAY))
        assertEquals(nov(9) to nov(15), Storage.periodDays(Period.WEEK, nov(15), MONDAY))
        assertEquals(nov(1) to nov(30), Storage.periodDays(Period.MONTH, today, MONDAY))
        assertEquals(LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29),
            Storage.periodDays(Period.MONTH, LocalDate.of(2028, 2, 10), MONDAY))
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31), Storage.periodDays(Period.YEAR, today, MONDAY))
        assertEquals(LocalDate.of(2028, 12, 31), Storage.periodDays(Period.YEAR, LocalDate.of(2028, 2, 29), MONDAY).second)
    }

    @Test
    fun `stepping goes a period at a time, never past today nor before the history`() {
        val first = oct(20)
        fun step(p: Period, day: LocalDate, by: Long, firstDay: LocalDate? = first) =
            Storage.periodStep(p, day, by, firstDay, today, MONDAY)

        assertEquals(nov(11), step(Period.DAY, today, -1))
        assertNull(step(Period.DAY, today, 1))
        assertNull(step(Period.DAY, first, -1))
        // No history yet: today is all there is.
        assertNull(step(Period.DAY, today, -1, firstDay = null))
        assertNull(step(Period.WEEK, today, -1, firstDay = null))

        // A week: the same weekday, a week away.
        assertEquals(nov(5), step(Period.WEEK, today, -1))
        assertEquals(today, step(Period.WEEK, nov(5), 1))
        assertNull(step(Period.WEEK, today, 1))
        // Saturday's week is over; the next has begun but hasn't got to Saturday: today.
        assertEquals(today, step(Period.WEEK, nov(7), 1))
        // The week before has only the history's first days: its first.
        assertEquals(first, step(Period.WEEK, oct(26), -1))
        // And the one before that has none.
        assertNull(step(Period.WEEK, oct(22), -1))

        assertEquals(first, step(Period.MONTH, today, -1))
        assertNull(step(Period.MONTH, first, -1))
        assertEquals(today, step(Period.MONTH, oct(31), 1))
        assertNull(step(Period.MONTH, today, 1))

        assertNull(step(Period.YEAR, today, -1))
        assertEquals(LocalDate.of(2025, 12, 30), step(Period.YEAR, today, -1, firstDay = LocalDate.of(2025, 12, 30)))
        assertEquals(LocalDate.of(2025, 11, 12), step(Period.YEAR, today, -1, firstDay = LocalDate.of(2024, 5, 1)))
        assertNull(step(Period.YEAR, today, 1))

        // A day outside the history (the clock set back under a screen left open) isn't stuck there.
        assertEquals(today, step(Period.DAY, nov(14), -1))
        assertNull(step(Period.DAY, nov(14), 1))
        assertEquals(first, step(Period.DAY, oct(17), 1))
        assertNull(step(Period.DAY, oct(17), -1))
        assertEquals(today, step(Period.WEEK, nov(30), -1))
        assertEquals(first, step(Period.MONTH, LocalDate.of(2026, 8, 5), 1))
    }

    @Test
    fun `a period is called by how far back it is, then by its dates`() {
        fun title(p: Period, day: LocalDate, now: LocalDate = today) = Storage.periodTitle(p, day, now, MONDAY)
        assertEquals("Today", title(Period.DAY, today))
        assertEquals("Sun, 8 Nov", title(Period.DAY, nov(8)))

        assertEquals("This week", title(Period.WEEK, nov(9)))
        assertEquals("Last week", title(Period.WEEK, nov(8)))
        assertEquals("26 Oct to 1 Nov", title(Period.WEEK, oct(28)))
        assertEquals("19 to 25 Oct", title(Period.WEEK, oct(21)))
        assertEquals("3 to 9 Nov 2025", title(Period.WEEK, LocalDate.of(2025, 11, 5)))
        assertEquals("29 Dec to 4 Jan", title(Period.WEEK, LocalDate.of(2025, 12, 31)))
        // Weeks from Sunday: the 7th was last week's Saturday.
        assertEquals("This week", Storage.periodTitle(Period.WEEK, nov(8), today, SUNDAY))
        assertEquals("Last week", Storage.periodTitle(Period.WEEK, nov(7), today, SUNDAY))

        assertEquals("This month", title(Period.MONTH, nov(1)))
        assertEquals("Last month", title(Period.MONTH, oct(31)))
        assertEquals("September", title(Period.MONTH, LocalDate.of(2026, 9, 15)))
        assertEquals("December 2025", title(Period.MONTH, LocalDate.of(2025, 12, 15)))
        // Last month, in last year.
        assertEquals("Last month", title(Period.MONTH, LocalDate.of(2025, 12, 15), now = LocalDate.of(2026, 1, 10)))

        assertEquals("This year", title(Period.YEAR, LocalDate.of(2026, 1, 1)))
        assertEquals("Last year", title(Period.YEAR, LocalDate.of(2025, 6, 1)))
        assertEquals("2024", title(Period.YEAR, LocalDate.of(2024, 6, 1)))
    }

    // ---- adding it up ----

    @Test
    fun `each day's total - a session over midnight cut between its days, split screen once`() {
        val sessions = listOf(
            used(yt, nov(8), 23, 30, 45),    // into Monday: 15 minutes of it
            used(yt, nov(9), 23, 50, 20),    // Monday's last 10, Tuesday's first 10
            used(ig, nov(10), 10, 0, 30),
            used(yt, nov(10), 10, 15, 30),   // beside Instagram for 15
            used(ig, nov(16), 9, 0, 60),     // the week after
        )
        val per = Storage.dayTotals(sessions, nov(9), nov(15), zone, setOf(yt))
        assertEquals(nov(9), per.first)
        assertArrayEquals(mins(25, 55, 0, 0, 0, 0, 0), per.all)
        assertArrayEquals(mins(25, 40, 0, 0, 0, 0, 0), per.limited)
        // Together, the week's screen time.
        val (from, _) = Storage.dayBounds(nov(9), zone)
        val (_, to) = Storage.dayBounds(nov(15), zone)
        assertEquals(Storage.screenTimeMs(sessions, from, to), per.all.sum())
        // Nothing at all.
        assertArrayEquals(LongArray(7), Storage.dayTotals(emptyList(), nov(9), nov(15), zone, setOf(yt)).all)
    }

    @Test
    fun `the day the clocks go back has 25 hours to fill`() {
        val (from, to) = Storage.dayBounds(oct(25), zone)
        val per = Storage.dayTotals(listOf(Session(ig, from - 60.min, to + 60.min)), oct(19), oct(25), zone, emptySet())
        assertEquals(25 * 60.min, per.all[6])
        assertEquals(60.min, per.all[5])
        assertEquals(0L, per.limited.sum())
    }

    @Test
    fun `a week's bars, total, average and busiest day`() {
        val sessions = listOf(
            used(yt, nov(10), 21, 0, 40), used(ig, nov(10), 8, 0, 15),
            used(ig, nov(11), 12, 0, 5),
            used(yt, nov(12), 9, 0, 10),
        )
        // The history began on Tuesday; today is Thursday.
        val s = Storage.periodStats(sessions, Period.WEEK, nov(9), nov(15), zone, setOf(yt), nov(10), today)
        assertEquals((9..15).map { nov(it) }, s.starts)
        assertArrayEquals(mins(0, 40, 0, 10, 0, 0, 0), s.limited)
        assertArrayEquals(mins(0, 15, 5, 0, 0, 0, 0), s.other)
        assertEquals("Friday to Sunday are still to come", 4, s.known)
        assertEquals("Monday was before the history began", 1, s.since)
        assertEquals(70.min, s.totalMs)
        assertEquals(50.min, s.limitedMs)
        assertEquals("Tuesday, Wednesday and today", 3, s.days)
        assertEquals(70.min / 3, s.avgMs)
        assertEquals(1, s.busiest)

        // A past week: all seven days, all known. Two days as busy: the earlier.
        val past = Storage.periodStats(listOf(used(ig, nov(3), 9, 0, 30), used(yt, nov(5), 9, 0, 30)),
            Period.WEEK, nov(2), nov(8), zone, setOf(yt), oct(20), today)
        assertEquals(7, past.known)
        assertEquals(0, past.since)
        assertEquals(7, past.days)
        assertEquals(60.min / 7, past.avgMs)
        assertEquals(1, past.busiest)

        // Nothing: no busiest day, and nothing divided by nothing.
        val none = Storage.periodStats(emptyList(), Period.WEEK, nov(9), nov(15), zone, setOf(yt), null, today)
        assertEquals(0L, none.totalMs)
        assertEquals(0L, none.avgMs)
        assertEquals(1, none.days)
        assertNull(none.busiest)
        assertEquals("no history: today's bar alone is known, with nothing in it", 3 to 4, none.since to none.known)
    }

    @Test
    fun `a month has a bar for each of its days`() {
        val s = Storage.periodStats(listOf(used(ig, nov(1), 9, 0, 30), used(yt, nov(12), 9, 0, 20)),
            Period.MONTH, nov(1), nov(30), zone, setOf(yt), oct(20), today)
        assertEquals(30, s.starts.size)
        assertEquals(12, s.known)
        assertEquals(30.min, s.other[0])
        assertEquals(20.min, s.limited[11])
        assertEquals(12, s.days)
        assertEquals(50.min / 12, s.avgMs)
        assertEquals(0, s.busiest)
    }

    @Test
    fun `a year's bars are a day's average in each month, over the days the history has`() {
        // The history began on 22 October: ten days of it, then twelve of November.
        val sessions = listOf(
            used(yt, oct(22), 9, 0, 100), used(ig, oct(30), 9, 0, 100),
            used(yt, nov(1), 9, 0, 120),
        )
        val s = Storage.periodStats(sessions, Period.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            zone, setOf(yt), oct(22), today)
        assertEquals((1..12).map { LocalDate.of(2026, it, 1) }, s.starts)
        assertEquals("December is still to come", 11, s.known)
        assertEquals("January to September were before the history began", 9, s.since)
        assertEquals(10.min, s.limited[9])
        assertEquals(10.min, s.other[9])
        assertEquals(10.min, s.limited[10])
        assertEquals(0L, s.other[10])
        assertEquals(0L, (0..8).sumOf { s.limited[it] + s.other[it] })
        assertEquals(320.min, s.totalMs)
        assertEquals(220.min, s.limitedMs)
        assertEquals(22, s.days)
        assertEquals(320.min / 22, s.avgMs)
        assertEquals("October: 20 minutes a day, to November's 10", 9, s.busiest)
    }

    // ---- reading the chart ----

    @Test
    fun `the marks under the bars - each weekday, every seventh date, each quarter`() {
        val week = (9..15).map { nov(it) }
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), Storage.barLabels(Period.WEEK, week).map { it.second })
        assertEquals((0..6).toList(), Storage.barLabels(Period.WEEK, week).map { it.first })
        assertEquals(listOf(0 to "1", 7 to "8", 14 to "15", 21 to "22", 28 to "29"),
            Storage.barLabels(Period.MONTH, (1..30).map { nov(it) }))
        assertEquals(listOf(0 to "Jan", 3 to "Apr", 6 to "Jul", 9 to "Oct"),
            Storage.barLabels(Period.YEAR, (1..12).map { LocalDate.of(2026, it, 1) }))
        assertEquals(emptyList<Pair<Int, String>>(), Storage.barLabels(Period.DAY, listOf(today)))
    }

    @Test
    fun `a bar read off names its day, or its month and that it's an average`() {
        assertEquals("Today", Storage.barTitle(Period.WEEK, today, today))
        assertEquals("Yesterday", Storage.barTitle(Period.MONTH, nov(11), today))
        assertEquals("Sun, 8 Nov", Storage.barTitle(Period.MONTH, nov(8), today))
        assertEquals("October, daily average", Storage.barTitle(Period.YEAR, oct(1), today))
        assertEquals("October 2025, daily average", Storage.barTitle(Period.YEAR, LocalDate.of(2025, 10, 1), today))
    }

    @Test
    fun `the marks up the side are round, at most three, in minutes or whole hours`() {
        fun marks(ms: Long) = Storage.barTicks(ms).let { t -> t.map { Storage.fmtBarTick(it, t) } }
        assertEquals(emptyList<String>(), marks(0))
        assertEquals(listOf("1 min"), marks(30_000))
        assertEquals(listOf("10 min", "20 min", "30 min"), marks(25.min))
        assertEquals(listOf("15 min", "30 min", "45 min"), marks(45.min))
        assertEquals(listOf("20 min", "40 min", "60 min"), marks(55.min))
        assertEquals(listOf("30 min", "60 min", "90 min"), marks(70.min))
        assertEquals(listOf("1 hr", "2 hr"), marks(100.min))
        assertEquals(listOf("1 hr", "2 hr", "3 hr"), marks(125.min))
        assertEquals(listOf("2 hr", "4 hr", "6 hr"), marks(5 * 60.min))
        assertEquals(listOf("8 hr", "16 hr", "24 hr"), marks(24 * 60.min))
        // A day is all a bar can hold (the 25-hour one too).
        assertEquals(listOf("8 hr", "16 hr", "24 hr"), marks(25 * 60.min))
        // The room beside the chart fits every one of them.
        for (m in 1..1500) assertTrue(marks(m.min).all { it.length <= Storage.WIDEST_BAR_TICK.length })
    }
}
