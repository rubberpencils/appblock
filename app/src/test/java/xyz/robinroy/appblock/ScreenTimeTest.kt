package xyz.robinroy.appblock

import android.app.usage.UsageEvents
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.robinroy.appblock.Storage.EV_PAUSED
import xyz.robinroy.appblock.Storage.EV_RESUMED
import xyz.robinroy.appblock.Storage.EV_SCREEN_OFF
import xyz.robinroy.appblock.Storage.EV_SHUTDOWN
import xyz.robinroy.appblock.Storage.EV_STARTUP
import xyz.robinroy.appblock.Storage.EV_STOPPED
import xyz.robinroy.appblock.Storage.Session
import xyz.robinroy.appblock.Storage.UsageEv
import java.time.LocalDate
import java.time.ZoneId

/**
 * Screen time: Android's usage events read into sessions, the history
 * merged as it's stored, and a day cut up for the chart, the busiest hour
 * and the per-app list. Times are seconds after a fixed `t0`.
 */
class ScreenTimeTest {

    private val t0 = 1_700_000_000_000L
    private val Int.s get() = t0 + this * 1000L
    private val Int.min get() = this * 60_000L
    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"
    private val home = "com.android.launcher"
    private val ignore = setOf(home, "com.android.systemui")

    private fun ev(sec: Int, type: Int, pkg: String = "", cls: String? = "Main") = UsageEv(sec.s, type, pkg, cls)
    private fun read(vararg e: UsageEv, until: Int = 10_000, multi: Boolean = true) =
        Storage.sessionsFrom(e.toList(), until.s, ignore, multi)
    private fun sess(pkg: String, a: Int, b: Int) = Session(pkg, a.s, b.s)

    // ---- events into sessions ----

    @Test
    fun `the event codes are Android's`() {
        assertEquals(UsageEvents.Event.ACTIVITY_RESUMED, EV_RESUMED)
        assertEquals(UsageEvents.Event.MOVE_TO_FOREGROUND, EV_RESUMED)
        assertEquals(UsageEvents.Event.ACTIVITY_PAUSED, EV_PAUSED)
        assertEquals(UsageEvents.Event.SCREEN_NON_INTERACTIVE, EV_SCREEN_OFF)
        assertEquals(UsageEvents.Event.ACTIVITY_STOPPED, EV_STOPPED)
        assertEquals(UsageEvents.Event.DEVICE_SHUTDOWN, EV_SHUTDOWN)
        assertEquals(UsageEvents.Event.DEVICE_STARTUP, EV_STARTUP)
    }

    @Test
    fun `on screen from resumed to paused`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(70, EV_PAUSED, yt))
        assertEquals(listOf(sess(yt, 10, 70)), d.closed)
        assertEquals(emptyList<Session>(), d.open)
        assertEquals(10_000.s, d.safePoint)
    }

    @Test
    fun `moving between an app's own screens is one session`() {
        // Android 10+: the old screen pauses, the new one resumes, the old one stops.
        val d = read(ev(10, EV_RESUMED, yt, "Home"), ev(40, EV_PAUSED, yt, "Home"),
            ev(41, EV_RESUMED, yt, "Watch"), ev(42, EV_STOPPED, yt, "Home"), ev(90, EV_PAUSED, yt, "Watch"))
        assertEquals(listOf(sess(yt, 10, 90)), d.closed)
    }

    @Test
    fun `back after more than a moment is a new session`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(20, EV_PAUSED, yt), ev(23, EV_RESUMED, yt), ev(30, EV_PAUSED, yt))
        assertEquals(listOf(sess(yt, 10, 20), sess(yt, 23, 30)), d.closed)
    }

    @Test
    fun `split screen is two sessions at once`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(15, EV_RESUMED, ig), ev(50, EV_PAUSED, yt), ev(60, EV_PAUSED, ig))
        assertEquals(listOf(sess(yt, 10, 50), sess(ig, 15, 60)), d.closed)
    }

    @Test
    fun `before Android 10 another app coming on screen ends the last, pause or not`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(50, EV_RESUMED, ig), ev(60, EV_PAUSED, ig), multi = false)
        assertEquals(listOf(sess(yt, 10, 50), sess(ig, 50, 60)), d.closed)
        // The launcher too, though it makes no session of its own.
        val viaHome = read(ev(10, EV_RESUMED, yt), ev(50, EV_RESUMED, home), multi = false)
        assertEquals(listOf(sess(yt, 10, 50)), viaHome.closed)
        assertEquals(emptyList<Session>(), viaHome.open)
    }

    @Test
    fun `the screen going off ends everything, and a late pause after it changes nothing`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(15, EV_RESUMED, ig), ev(100, EV_SCREEN_OFF), ev(400, EV_PAUSED, yt))
        assertEquals(listOf(sess(yt, 10, 100), sess(ig, 15, 100)), d.closed)
        assertEquals(emptyList<Session>(), d.open)
    }

    @Test
    fun `a shutdown ends everything, and a startup with none before it ends everything at the last event`() {
        assertEquals(listOf(sess(yt, 10, 80)), read(ev(10, EV_RESUMED, yt), ev(80, EV_SHUTDOWN)).closed)
        // Battery pulled: no shutdown, the next thing seen is the startup.
        val pulled = read(ev(10, EV_RESUMED, yt), ev(30, EV_RESUMED, ig, "A"), ev(3000, EV_STARTUP))
        // (Instagram's open for no time before that, too short to keep.)
        assertEquals(listOf(sess(yt, 10, 30)), pulled.closed)
        assertEquals(emptyList<Session>(), pulled.open)
    }

    @Test
    fun `a stop with no pause ends it, and a stop after the pause doesn't move the end`() {
        assertEquals(listOf(sess(yt, 10, 60)), read(ev(10, EV_RESUMED, yt), ev(60, EV_STOPPED, yt)).closed)
        assertEquals(listOf(sess(yt, 10, 60)),
            read(ev(10, EV_RESUMED, yt), ev(60, EV_PAUSED, yt), ev(65, EV_STOPPED, yt)).closed)
    }

    @Test
    fun `a pause with nothing resumed was read last time`() {
        val d = read(ev(5, EV_PAUSED, yt), ev(10, EV_RESUMED, ig), ev(20, EV_PAUSED, ig))
        assertEquals(listOf(sess(ig, 10, 20)), d.closed)
    }

    @Test
    fun `an app still on screen is open, and the next read starts from it`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(20, EV_PAUSED, yt), ev(30, EV_RESUMED, ig), until = 500)
        assertEquals(listOf(sess(yt, 10, 20)), d.closed)
        assertEquals(listOf(sess(ig, 30, 500)), d.open)
        assertEquals(30.s, d.safePoint)
    }

    @Test
    fun `events after the end of the read are left for the next one`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(600, EV_PAUSED, yt), until = 500)
        assertEquals(listOf(sess(yt, 10, 500)), d.open)
    }

    @Test
    fun `the launcher and the system UI make no sessions`() {
        val d = read(ev(10, EV_RESUMED, home), ev(20, EV_PAUSED, home),
            ev(30, EV_RESUMED, "com.android.systemui"), ev(40, EV_PAUSED, "com.android.systemui"))
        assertEquals(emptyList<Session>(), d.closed)
    }

    @Test
    fun `too short is dropped, too long is cut to a day`() {
        assertEquals(emptyList<Session>(), read(ev(10, EV_RESUMED, yt), UsageEv(10.s + 999, EV_PAUSED, yt, "Main")).closed)
        assertEquals(listOf(Session(yt, 10.s, 10.s + 1000)),
            read(ev(10, EV_RESUMED, yt), UsageEv(10.s + 1000, EV_PAUSED, yt, "Main")).closed)
        val long = read(ev(10, EV_RESUMED, yt), ev(10 + 30 * 3600, EV_PAUSED, yt), until = 40 * 3600)
        assertEquals(listOf(Session(yt, 10.s, 10.s + Storage.MAX_SESSION_MS)), long.closed)
    }

    @Test
    fun `the same screen resumed twice is still one`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(20, EV_RESUMED, yt), ev(50, EV_PAUSED, yt))
        assertEquals(listOf(sess(yt, 10, 50)), d.closed)
    }

    @Test
    fun `events out of order are read in order, and other kinds ignored`() {
        val d = read(ev(50, EV_PAUSED, yt), ev(12, 7 /* user interaction */, yt), ev(10, EV_RESUMED, yt))
        assertEquals(listOf(sess(yt, 10, 50)), d.closed)
    }

    @Test
    fun `two windows of one screen end at the first one's pause (Android doesn't say which window)`() {
        val d = read(ev(10, EV_RESUMED, yt), ev(15, EV_RESUMED, yt), ev(30, EV_PAUSED, yt), ev(80, EV_PAUSED, yt))
        assertEquals(listOf(sess(yt, 10, 30)), d.closed)
    }

    // ---- storing: merged, so reading again is harmless ----

    @Test
    fun `merging joins an app's overlapping and touching sessions only`() {
        val merged = Storage.mergeSessions(listOf(sess(yt, 10, 50), sess(yt, 40, 60), sess(yt, 62, 70),
            sess(yt, 80, 90), sess(ig, 50, 55)))
        assertEquals(listOf(sess(yt, 10, 70), sess(ig, 50, 55), sess(yt, 80, 90)), merged)
        assertEquals(merged, Storage.mergeSessions(merged + merged))
    }

    @Test
    fun `merging never makes a session longer than a day`() {
        val day = Storage.MAX_SESSION_MS
        // On screen for 30 hours, read as 20-hour pieces a second apart.
        val a = Session(yt, t0, t0 + 20 * 3_600_000L)
        val b = Session(yt, a.end + 1000, t0 + 30 * 3_600_000L)
        val merged = Storage.mergeSessions(listOf(a, b))
        assertEquals(listOf(a, b), merged)
        assertTrue(merged.all { it.end - it.start <= day })
        // Overlapping, too long together: the rest starts where the first ends.
        val c = Session(yt, t0 + 10 * 3_600_000L, t0 + 30 * 3_600_000L)
        assertEquals(listOf(a, Session(yt, a.end, c.end)), Storage.mergeSessions(listOf(a, c)))
        // Merged again: the same.
        assertEquals(merged, Storage.mergeSessions(merged + merged))
    }

    @Test
    fun `reading again from a later point and merging changes nothing`() {
        val events = listOf(ev(10, EV_RESUMED, yt), ev(100, EV_PAUSED, yt), ev(101, EV_RESUMED, yt),
            ev(300, EV_PAUSED, yt), ev(400, EV_RESUMED, ig), ev(450, EV_PAUSED, ig))
        val first = Storage.mergeSessions(Storage.sessionsFrom(events, 1000.s, ignore, true).closed)
        // From 150: the pause at 300 has no resume in view; nothing new, nothing doubled.
        val again = Storage.sessionsFrom(events.filter { it.time >= 150.s }, 1000.s, ignore, true).closed
        assertEquals(first, Storage.mergeSessions(first + again))
        // An open session read twice, then closed: stored once.
        val open = Storage.sessionsFrom(events.take(1), 50.s, ignore, true)
        assertEquals(10.s, open.safePoint)
        val later = Storage.sessionsFrom(events.filter { it.time >= open.safePoint }, 1000.s, ignore, true).closed
        assertEquals(first, Storage.mergeSessions(first + later))
    }

    @Test
    fun `a sync reads from a little before the last, or two weeks back at first`() {
        val now = t0
        assertEquals(now - Storage.HISTORY_BACKFILL_MS, Storage.syncFrom(null, now))
        assertEquals(now - 60.min - Storage.HISTORY_OVERLAP_MS, Storage.syncFrom(now - 60.min, now))
        assertEquals(now - Storage.HISTORY_BACKFILL_MS, Storage.syncFrom(now - 30 * 1440.min, now))
    }

    @Test
    fun `a sync is due after the gap, after a restart, or the first time`() {
        assertTrue(Storage.syncDue(0, 1, 5000, 1, 60.min))
        assertEquals(false, Storage.syncDue(1000, 1, 1000 + 59.min, 1, 60.min))
        assertTrue(Storage.syncDue(1000, 1, 1000 + 60.min, 1, 60.min))
        assertTrue(Storage.syncDue(1000, 1, 500, 2, 60.min))
        assertTrue(Storage.syncDue(1000, 1, 500, 1, 60.min))
    }

    @Test
    fun `moving the clock moves this boot's history with it`() {
        val anchor = Storage.ClockAnchor(wall = t0, elapsed = 10.min, boot = 4)
        // An hour forward, 5 minutes later.
        assertEquals((t0 - 10.min) to 60.min, Storage.historyShift(anchor, t0 + 65.min, 15.min, 4))
        assertNull(Storage.historyShift(anchor, t0 + 5.min, 15.min, 4))
        assertNull("a restart can't be measured", Storage.historyShift(anchor, t0 + 65.min, 1.min, 5))
        assertNull(Storage.historyShift(null, t0, 1.min, 4))
    }

    // ---- a day ----

    private val zone = ZoneId.of("Europe/London")

    @Test
    fun `a day is midnight to midnight, 23 or 25 hours when the clocks change`() {
        val (a, b) = Storage.dayBounds(LocalDate.of(2026, 6, 1), zone)
        assertEquals(24 * 60.min, b - a)
        val (c, d) = Storage.dayBounds(LocalDate.of(2026, 3, 29), zone)
        assertEquals(23 * 60.min, d - c)
        val (e, f) = Storage.dayBounds(LocalDate.of(2026, 10, 25), zone)
        assertEquals(25 * 60.min, f - e)
        // São Paulo's day of 4 Nov 2018 began at 01:00.
        val sp = ZoneId.of("America/Sao_Paulo")
        val (g, h) = Storage.dayBounds(LocalDate.of(2018, 11, 4), sp)
        assertEquals(23 * 60.min, h - g)
    }

    @Test
    fun `a session across midnight counts in both days`() {
        val clipped = Storage.clip(listOf(sess(yt, 100, 300), sess(ig, 400, 500)), 200.s, 450.s)
        assertEquals(listOf(sess(yt, 200, 300), sess(ig, 400, 450)), clipped)
    }

    @Test
    fun `screen time counts two apps side by side once`() {
        assertEquals(100_000L, Storage.screenTimeMs(listOf(sess(yt, 0, 60), sess(ig, 30, 100)), 0.s, 1000.s))
        assertEquals(0L, Storage.screenTimeMs(emptyList(), 0.s, 1000.s))
    }

    @Test
    fun `buckets hold each slice's share, limited apps first, never over the whole`() {
        // Buckets of 100 s from 0 to 400. YouTube (limited) 50..150, Instagram 0..250 beside it.
        val b = Storage.buckets(listOf(sess(yt, 50, 150), sess(ig, 0, 250)), 0.s, 400.s, 100_000, setOf(yt), 400.s)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0f, 0f), b.limited, 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0f), b.other, 1e-6f)
        assertEquals(4, b.known)
        for (i in 0 until 4) assertTrue(b.limited[i] + b.other[i] <= 1f + 1e-6f)
        // Together they come to the day's screen time.
        val total = (b.limited.sum() + b.other.sum()) * 100_000
        assertEquals(Storage.screenTimeMs(listOf(sess(yt, 50, 150), sess(ig, 0, 250)), 0.s, 400.s).toFloat(), total, 1f)
    }

    @Test
    fun `a short session lands in its one bucket, and the rest of today isn't known yet`() {
        val b = Storage.buckets(listOf(sess(yt, 210, 240)), 0.s, 1000.s, 100_000, emptySet(), 350.s)
        assertEquals(0.3f, b.other[2], 1e-6f)
        assertEquals(0f, b.other.sum() - b.other[2], 1e-6f)
        assertEquals(4, b.known)
        assertEquals(0, Storage.buckets(emptyList(), 0.s, 1000.s, 100_000, emptySet(), 0.s).known)
        // Now is 320: the slice from 300 is 20 s old, and on screen all of it is full.
        val now = Storage.buckets(listOf(sess(yt, 290, 320)), 0.s, 1000.s, 100_000, emptySet(), 320.s)
        assertEquals(1f, now.other[3], 1e-6f)
        assertEquals(0.1f, now.other[2], 1e-6f)
        // A last bucket shorter than the rest (a 23-hour day) is a share of its own length.
        val short = Storage.buckets(listOf(sess(yt, 200, 250)), 0.s, 250.s, 100_000, emptySet(), 250.s)
        assertEquals(3, short.other.size)
        assertEquals(1f, short.other[2], 1e-6f)
    }

    @Test
    fun `apps by time, most first, cut to the day`() {
        val totals = Storage.appTotals(listOf(sess(yt, 0, 60), sess(ig, 100, 300), sess(yt, 50, 90), sess(yt, 900, 2000)),
            0.s, 1000.s)
        assertEquals(listOf(ig to 200_000L, yt to 190_000L), totals)
    }

    @Test
    fun `the busiest hour, earliest on a tie`() {
        val day = LocalDate.of(2026, 6, 1)
        val (from, _) = Storage.dayBounds(day, zone)
        fun at(h: Int, m: Int) = from + h * 60.min + m.min
        val s = listOf(Session(yt, at(9, 0), at(9, 20)), Session(ig, at(21, 10), at(21, 50)), Session(yt, at(13, 0), at(13, 40)))
        val peak = Storage.peakHour(s, day, zone)!!
        assertEquals(at(13, 0), peak.start)
        assertEquals(40.min, peak.ms)
        assertEquals("1 PM", Storage.fmtHour(peak.start, zone))
        assertNull(Storage.peakHour(emptyList(), day, zone))
        // A session over the hour's edge counts in each hour for its part.
        val edge = Storage.peakHour(listOf(Session(yt, at(9, 50), at(10, 30))), day, zone)!!
        assertEquals(at(10, 0), edge.start)
    }

    @Test
    fun `the hour that happens twice when the clocks go back is two hours`() {
        val day = LocalDate.of(2026, 10, 25)   // London: 01:00-02:00 happens twice
        val (from, _) = Storage.dayBounds(day, zone)
        // 40 min in the first 1 AM, 30 in the second: the first wins alone.
        val s = listOf(Session(yt, from + 60.min, from + 100.min), Session(yt, from + 120.min, from + 150.min))
        val peak = Storage.peakHour(s, day, zone)!!
        assertEquals(from + 60.min, peak.start)
        assertEquals(40.min, peak.ms)
    }

    @Test
    fun `day titles`() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals("Today", Storage.dayTitle(today, today))
        assertEquals("Yesterday", Storage.dayTitle(today.minusDays(1), today))
        assertEquals("Sat, 7 Nov", Storage.dayTitle(today.minusDays(2), today))
        assertEquals("Wed, 31 Dec 2025", Storage.dayTitle(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `screen time in words, never rounded up`() {
        assertEquals("0 minutes", Storage.fmtScreenTime(0))
        assertEquals("Less than a minute", Storage.fmtScreenTime(59_000))
        assertEquals("1 minute", Storage.fmtScreenTime(119_000))
        assertEquals("1 hour", Storage.fmtScreenTime(60.min))
        assertEquals("2 hours 1 minute", Storage.fmtScreenTime(121.min))
        assertEquals("7 hours 17 minutes", Storage.fmtScreenTime(7 * 60.min + 17.min + 23_000))
    }

    @Test
    fun `the card asks for usage access until dismissed, and shows stats once granted`() {
        assertEquals(Storage.UsageCard.STATS, Storage.usageCard(granted = true, dismissed = false))
        assertEquals(Storage.UsageCard.STATS, Storage.usageCard(granted = true, dismissed = true))
        assertEquals(Storage.UsageCard.ASK, Storage.usageCard(granted = false, dismissed = false))
        assertEquals(Storage.UsageCard.NONE, Storage.usageCard(granted = false, dismissed = true))
    }
}
