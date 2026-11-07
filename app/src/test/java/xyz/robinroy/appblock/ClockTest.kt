package xyz.robinroy.appblock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wall clock being moved (issue #7): caught against the time since
 * boot, and the usage logs moved with it so a budget stays spent. Strict
 * mode's side is in StrictTest. Times are built around a fixed `now`.
 */
class ClockTest {

    private val Int.min get() = this * 60_000L
    private val now = 1_000_000_000_000L

    private val anchor = Storage.ClockAnchor(wall = now, elapsed = 50.min, boot = 7)

    // ---- catching a move ----

    @Test
    fun `clocks that agree haven't moved`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min, 80.min, 7))
    }

    @Test
    fun `small network corrections are ignored`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min + 50_000, 80.min, 7))
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min - 50_000, 80.min, 7))
    }

    @Test
    fun `a clock set forward or back is caught`() {
        assertEquals(2 * 60.min, Storage.clockMoved(anchor, now + 30.min + 2 * 60.min, 80.min, 7))
        assertEquals(-60.min, Storage.clockMoved(anchor, now + 30.min - 60.min, 80.min, 7))
    }

    @Test
    fun `after a restart the clocks can't be compared`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 5 * 60.min, 1.min, 8))
        assertEquals(0L, Storage.clockMoved(null, now, 1.min, 8))
    }

    @Test
    fun `a time since boot that went backwards is a restart`() {
        // A boot count that didn't change across a restart (an OEM's, or none at all).
        assertEquals(0L, Storage.clockMoved(anchor, now + 5 * 60.min, 1.min, 7))
        assertEquals(Storage.ClockAnchor(now + 5 * 60.min, 1.min, 7), Storage.nextAnchor(anchor, now + 5 * 60.min, 1.min, 7))
    }

    // ---- the anchor ----

    @Test
    fun `an unmoved clock keeps the anchor`() {
        assertEquals(anchor, Storage.nextAnchor(anchor, now + 10.min, 60.min, 7))
    }

    @Test
    fun `a move sets a new anchor`() {
        assertEquals(Storage.ClockAnchor(now + 70.min, 60.min, 7), Storage.nextAnchor(anchor, now + 70.min, 60.min, 7))
    }

    @Test
    fun `small moves add up against the first anchor`() {
        // 40 s ahead: under the slack, nothing moved, the anchor stays.
        val first = Storage.nextAnchor(anchor, now + 40_000, 50.min, 7)
        assertEquals(anchor, first)
        // Another 40 s: 80 s off the first anchor, caught.
        assertEquals(80_000L, Storage.clockMoved(first, now + 80_000, 50.min, 7))
    }

    @Test
    fun `a restart sets a new anchor`() {
        assertEquals(Storage.ClockAnchor(now + 5.min, 1.min, 8), Storage.nextAnchor(anchor, now + 5.min, 1.min, 8))
    }

    @Test
    fun `the first look sets the anchor`() {
        assertEquals(Storage.ClockAnchor(now, 1.min, 3), Storage.nextAnchor(null, now, 1.min, 3))
    }

    // ---- the usage log moves with it ----

    private val rule = Rule(allowMin = 5, windowMin = 120)

    // 5 minutes used, ending 5 minutes ago: the budget is spent.
    private val log = listOf((now - 10.min) to (now - 5.min))

    @Test
    fun `shifting moves every entry, keeping its length`() {
        val two = log + ((now - 3.min) to (now - 1.min))
        assertEquals(listOf((now + 50.min) to (now + 55.min), (now + 57.min) to (now + 59.min)),
            Storage.shiftLog(two, 60.min))
        assertEquals(two, Storage.shiftLog(two, 0))
    }

    @Test
    fun `clock set forward - the budget stays spent`() {
        assertEquals(5.min, Storage.usedMs(log, rule.windowMin, now))
        // Three hours ahead: the 2-hour window would hold none of it...
        val later = now + 180.min
        assertEquals(0L, Storage.usedMs(log, rule.windowMin, later))
        // ...but the log moves as far, so the same 5 minutes count.
        val moved = Storage.clockMoved(anchor, later, 50.min, 7)
        assertEquals(5.min, Storage.usedMs(Storage.shiftLog(log, moved), rule.windowMin, later))
        // And the next save keeps it, where it would have dropped it.
        val cutoff = later - rule.windowMin.min
        assertEquals(Storage.shiftLog(log, moved) + ((later - 1.min) to later),
            Storage.appendUsage(Storage.shiftLog(log, moved), later - 1.min, later, cutoff))
    }

    @Test
    fun `clock set back - the budget stays spent`() {
        // Three hours back: the use would be in the future, and not count.
        val earlier = now - 180.min
        assertEquals(0L, Storage.usedMs(log, rule.windowMin, earlier))
        val moved = Storage.clockMoved(anchor, earlier, 50.min, 7)
        assertEquals(5.min, Storage.usedMs(Storage.shiftLog(log, moved), rule.windowMin, earlier))
    }

    // ---- a session open while it moves (the blocker's sessionSpan) ----

    // Opened at `now`, 50 minutes after boot.
    private val opened = Storage.ClockAnchor(wall = now, elapsed = 50.min, boot = 0)

    @Test
    fun `a session logs from where it was last logged`() {
        // 5 s on, and the log's next flush starts exactly where the last ended.
        assertEquals(now to now + 5_000, Storage.sessionSpan(opened, now + 5_000, 50.min + 5_000))
        // A few ms between reading the two clocks changes nothing.
        assertEquals(now to now + 5_003, Storage.sessionSpan(opened, now + 5_003, 50.min + 5_000))
    }

    @Test
    fun `flushes of a running session stay one entry in the log`() {
        var log = emptyList<Pair<Long, Long>>()
        var since = opened
        for (tick in 1..100) {
            // Each flush a few ms off the 5 s tick, as the service's are.
            val wall = since.wall + 5_000 + tick % 3
            val elapsed = since.elapsed + 5_000 + tick % 2
            val (start, end) = Storage.sessionSpan(since, wall, elapsed)
            log = Storage.appendUsage(log, start, end, cutoff = wall - rule.windowMin.min)
            since = Storage.ClockAnchor(wall, elapsed, 0)
        }
        assertEquals(1, log.size)
    }

    @Test
    fun `a session the clock jumped ahead in counts only the time on screen`() {
        // 5 s on screen, and meanwhile the clock was set 3 hours ahead.
        assertEquals(now + 180.min to now + 180.min + 5_000,
            Storage.sessionSpan(opened, now + 180.min + 5_000, 50.min + 5_000))
    }

    @Test
    fun `a session the clock went back in still counts its time`() {
        assertEquals(now - 180.min to now - 180.min + 5_000,
            Storage.sessionSpan(opened, now - 180.min + 5_000, 50.min + 5_000))
    }

    @Test
    fun `a session that seems to start before the last entry isn't swallowed`() {
        // The clock set back 3 hours unseen (a restart in between): use at
        // the new "now" lands before the last entry, and is a new one.
        val earlier = now - 180.min
        val after = Storage.appendUsage(log, earlier - 5.min, earlier, cutoff = earlier - rule.windowMin.min)
        assertEquals(log + ((earlier - 5.min) to earlier), after)
        assertEquals(5.min, Storage.usedMs(after, rule.windowMin, earlier))
    }

    @Test
    fun `a session overlapping the last entry's start isn't swallowed either`() {
        val after = Storage.appendUsage(log, now - 12.min, now - 8.min, cutoff = now - 120.min)
        assertEquals(log + ((now - 12.min) to (now - 8.min)), after)
    }
}
