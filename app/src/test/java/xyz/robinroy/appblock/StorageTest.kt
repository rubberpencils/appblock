package xyz.robinroy.appblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rolling-window budget math. Times are built around a fixed
 * `now`; `now - 30.min` reads as "30 minutes ago".
 */
class StorageTest {

    private val Int.min get() = this * 60_000L
    private val now = 1_000_000_000_000L
    private val rule = Rule(allowMin = 5, windowMin = 120)

    @Test
    fun `no usage - nothing counted, no wait`() {
        assertEquals(0L, Storage.usedMs(emptyList(), rule.windowMin, now))
        assertEquals(0L, Storage.msUntilUnblocked(emptyList(), rule, now))
    }

    @Test
    fun `usage inside the window is summed`() {
        val intervals = listOf(
            (now - 30.min) to (now - 27.min),  // 3 min
            (now - 10.min) to (now - 9.min),   // 1 min
        )
        assertEquals(4.min, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `usage older than the window is ignored`() {
        val intervals = listOf((now - 200.min) to (now - 190.min))
        assertEquals(0L, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `usage straddling the window edge is clipped`() {
        // 10-minute session, but only its last 5 minutes fall inside the 120-min window.
        val intervals = listOf((now - 125.min) to (now - 115.min))
        assertEquals(5.min, Storage.usedMs(intervals, rule.windowMin, now))
    }

    @Test
    fun `under budget - usable immediately`() {
        val intervals = listOf((now - 4.min) to now)  // 4 of 5 allowed minutes used
        assertEquals(0L, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `budget spent in one burst just now - wait until it starts aging out`() {
        // Used all 5 minutes ending right now. The burst leaves the 120-min window
        // starting at now+115min; one minute later enough has aged out.
        val intervals = listOf((now - 5.min) to now)
        assertEquals(116.min, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `budget spent in two chunks - oldest chunk aging out unblocks sooner`() {
        val intervals = listOf(
            (now - 119.min) to (now - 116.min),  // 3 min, almost aged out
            (now - 2.min) to now,                // 2 min, fresh
        )
        // Blocked at `now` (3+2 = 5 min used). Two minutes later the old chunk has
        // partially left the window (only 2 of its 3 min remain), freeing budget.
        assertEquals(5.min, Storage.usedMs(intervals, rule.windowMin, now))
        assertEquals(2.min, Storage.msUntilUnblocked(intervals, rule, now))
    }

    @Test
    fun `ceilMin - partial minutes round up`() {
        assertEquals(0L, Storage.ceilMin(0))
        assertEquals(1L, Storage.ceilMin(1))
        assertEquals(1L, Storage.ceilMin(60_000))
        assertEquals(2L, Storage.ceilMin(60_001))
    }

    @Test
    fun `minutes used - zero use shows zero`() {
        assertEquals(0L, Storage.displayedUsedMin(0, 5))
    }

    @Test
    fun `minutes used - partial minutes round up`() {
        assertEquals(1L, Storage.displayedUsedMin(1_000, 5))       // 1s -> "1"
        assertEquals(1L, Storage.displayedUsedMin(60_000, 5))      // exactly 1 min
        assertEquals(2L, Storage.displayedUsedMin(60_001, 5))      // just over 1 min
    }

    @Test
    fun `minutes used - display capped at the allowance`() {
        assertEquals(5L, Storage.displayedUsedMin(9.min, 5))       // overshoot -> "5/5"
    }

    @Test
    fun `warn level - 0 below 50, then 50, then 90`() {
        // 5-min allowance: 50% = 2.5 min, 90% = 4.5 min.
        assertEquals(0, Storage.crossedWarnLevel(0L, 5))
        assertEquals(0, Storage.crossedWarnLevel(149_999, 5))      // 49.99%
        assertEquals(50, Storage.crossedWarnLevel(150_000, 5))     // exactly 50%
        assertEquals(50, Storage.crossedWarnLevel(4.min, 5))       // 80%
        assertEquals(90, Storage.crossedWarnLevel(270_000, 5))     // exactly 90%
        assertEquals(90, Storage.crossedWarnLevel(9.min, 5))       // way over
    }

    @Test
    fun `warn level - zero allowance never divides by zero`() {
        assertEquals(0, Storage.crossedWarnLevel(1.min, 0))
    }

    @Test
    fun `warning minutes - one truncated decimal, matching the percent`() {
        assertEquals("2.5", Storage.fmtMin(150_000))   // 50% of 5 min
        assertEquals("2.5", Storage.fmtMin(155_999))   // truncates, like the pct
        assertEquals("2.6", Storage.fmtMin(156_000))
        assertEquals("3", Storage.fmtMin(3.min))       // whole -> no decimal
        assertEquals("0", Storage.fmtMin(0))
    }

    @Test
    fun `home list - three states`() {
        // Nothing blocked -> the empty hint, set up or not: on a fresh
        // install the "Finish setup" checklist covers the required settings.
        assertEquals(Storage.HomeList.EMPTY_HINT, Storage.homeList(false, 0))
        assertEquals(Storage.HomeList.EMPTY_HINT, Storage.homeList(true, 0))
        // A required setting off, with rules -> "blocking is paused" note, rules kept.
        assertEquals(Storage.HomeList.PAUSED_NOTE, Storage.homeList(false, 3))
        // Normal operation -> the app cards.
        assertEquals(Storage.HomeList.CARDS, Storage.homeList(true, 3))
    }

    @Test
    fun `block-an-app guard - one popup naming every missing requirement`() {
        // All missing: each requirement contributes its own named segment,
        // in declaration order (= fix-first priority: the blocker, then
        // keeping it alive)...
        val all = Storage.requirementsMessage(Storage.Requirement.entries)
        assertTrue(all.contains("Accessibility"))
        assertTrue(all.contains("Unrestricted battery"))
        assertTrue(all.indexOf("Accessibility") < all.indexOf("Unrestricted battery"))
        // ...and the fix is pointed at the home-screen checklist, not a
        // settings shortcut (the sheet has only an OK button).
        assertTrue(all.contains("checklist on the home screen"))
        // Only battery missing -> the others aren't mentioned.
        val one = Storage.requirementsMessage(listOf(Storage.Requirement.BATTERY))
        assertTrue(one.contains("Unrestricted battery"))
        assertFalse(one.contains("Accessibility"))
    }

    @Test
    fun `remaining - allowance minus use, floored at zero`() {
        assertEquals(5.min, Storage.remainingMs(0L, 5))
        assertEquals(2.min, Storage.remainingMs(3.min, 5))
        assertEquals(0L, Storage.remainingMs(5.min, 5))
        assertEquals(0L, Storage.remainingMs(9.min, 5))       // overshoot
        assertEquals(0L, Storage.remainingMs(0L, 0))
    }

    @Test
    fun `session timer - reposts for a real move, not for jitter`() {
        // Nothing shown yet -> post.
        assertTrue(Storage.timerNeedsRepost(null, now, 0, 0))
        // Same deadline, or a few ms of tick jitter -> leave it alone.
        assertFalse(Storage.timerNeedsRepost(now, now, 0, 0))
        assertFalse(Storage.timerNeedsRepost(now, now + 999, 50, 50))
        assertFalse(Storage.timerNeedsRepost(now, now - 999, 0, 0))
        // Old usage aged out of the window and handed a minute back -> repost.
        assertTrue(Storage.timerNeedsRepost(now, now + 1.min, 0, 0))
        assertTrue(Storage.timerNeedsRepost(now, now - 1_000, 0, 0))
    }

    @Test
    fun `session timer - reposts when a warning threshold is crossed either way`() {
        // Usage climbed past 50%, then 90% -> the text gains the warning.
        assertTrue(Storage.timerNeedsRepost(now, now, 0, 50))
        assertTrue(Storage.timerNeedsRepost(now, now, 50, 90))
        // Old usage aged out and dropped it back below 50% -> warning goes.
        assertTrue(Storage.timerNeedsRepost(now, now + 1.min, 50, 0))
    }

    // ---- what's on screen, from the accessibility window list ----

    private val insta = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val self = "xyz.robinroy.appblock"
    private val screen = Storage.Bounds(0, 0, 1080, 2400)
    private val top = Storage.Bounds(0, 0, 1080, 1180)
    private val bottom = Storage.Bounds(0, 1220, 1080, 2400)
    private val popup = Storage.Bounds(100, 600, 900, 1500)
    private val statusBar = Storage.ScreenWindow(Storage.WindowKind.SYSTEM, Storage.Bounds(0, 0, 1080, 80))
    private val navBar = Storage.ScreenWindow(Storage.WindowKind.SYSTEM, Storage.Bounds(0, 2340, 1080, 2400))
    private fun app(pkg: String, b: Storage.Bounds) = Storage.ScreenWindow(Storage.WindowKind.APP, b, pkg)

    @Test
    fun `on screen - nothing, or only system windows`() {
        assertEquals(emptyMap<String, Storage.Bounds>(), Storage.appsOnScreen(emptyList(), self))
        assertEquals(emptyMap<String, Storage.Bounds>(), Storage.appsOnScreen(listOf(statusBar, navBar), self))
    }

    @Test
    fun `on screen - one app full screen`() {
        assertEquals(mapOf(insta to screen), Storage.appsOnScreen(listOf(app(insta, screen), statusBar), self))
    }

    @Test
    fun `on screen - both halves of a split screen`() {
        assertEquals(mapOf(insta to top, yt to bottom),
            Storage.appsOnScreen(listOf(app(insta, top), app(yt, bottom), statusBar), self))
    }

    @Test
    fun `on screen - a pop-up window over another app, or over home`() {
        assertEquals(mapOf(yt to screen, insta to popup),
            Storage.appsOnScreen(listOf(app(yt, screen), app(insta, popup)), self))
        assertEquals(mapOf("com.android.launcher3" to screen, insta to popup),
            Storage.appsOnScreen(listOf(app("com.android.launcher3", screen), app(insta, popup)), self))
    }

    @Test
    fun `on screen - an app's windows merge into one area`() {
        val dialog = Storage.Bounds(100, 900, 980, 1500)
        assertEquals(mapOf(insta to screen), Storage.appsOnScreen(listOf(app(insta, screen), app(insta, dialog)), self))
        // Two windows side by side: the area around both.
        assertEquals(mapOf(insta to Storage.Bounds(0, 0, 1080, 2400)),
            Storage.appsOnScreen(listOf(app(insta, top), app(insta, bottom)), self))
    }

    @Test
    fun `on screen - AppBlock itself, and windows of no app, don't count`() {
        assertEquals(mapOf(insta to top), Storage.appsOnScreen(listOf(
            app(insta, top), app(self, bottom),
            Storage.ScreenWindow(Storage.WindowKind.APP, bottom, pkg = null),
            Storage.ScreenWindow(Storage.WindowKind.OTHER, screen, pkg = yt)), self))
    }

    @Test
    fun `system on top - the pulled-down shade or power menu, not the bars or keyboard`() {
        assertFalse(Storage.systemOnTop(listOf(app(insta, screen), statusBar, navBar), 2400))
        assertFalse(Storage.systemOnTop(listOf(app(insta, screen),
            Storage.ScreenWindow(Storage.WindowKind.OTHER, Storage.Bounds(0, 1400, 1080, 2400))), 2400))
        assertFalse(Storage.systemOnTop(listOf(app(insta, screen),
            Storage.ScreenWindow(Storage.WindowKind.SYSTEM, Storage.Bounds(0, 0, 1080, 1200))), 2400))
        assertTrue(Storage.systemOnTop(listOf(app(insta, screen),
            Storage.ScreenWindow(Storage.WindowKind.SYSTEM, screen)), 2400))
    }

    @Test
    fun `on screen after - apps under the pulled-down shade stay as they were`() {
        val before = mapOf(insta to top, yt to bottom)
        val shade = Storage.ScreenWindow(Storage.WindowKind.SYSTEM, screen)
        // The shade hides them from the window list; they're still open underneath.
        assertEquals(before, Storage.onScreenAfter(listOf(shade), self, 2400, before))
        // Shade closed again: a fresh read.
        assertEquals(mapOf(insta to screen),
            Storage.onScreenAfter(listOf(app(insta, screen), statusBar), self, 2400, before))
        // No windows at all (screen off) is a fresh read too: nothing on screen.
        assertEquals(emptyMap<String, Storage.Bounds>(), Storage.onScreenAfter(emptyList(), self, 2400, before))
    }

    @Test
    fun `wall - the app's area, leaving the status bar uncovered`() {
        assertEquals(Storage.Bounds(0, 80, 1080, 2400), Storage.wallBounds(screen, listOf(statusBar, navBar), 2400))
        // The bottom half of a split and a pop-up are clear of it already.
        assertEquals(bottom, Storage.wallBounds(bottom, listOf(statusBar, navBar), 2400))
        assertEquals(popup, Storage.wallBounds(popup, listOf(statusBar), 2400))
        // No status bar (a full-screen video hides it) -> all of it.
        assertEquals(screen, Storage.wallBounds(screen, listOf(navBar), 2400))
        // A big system window at the top isn't the status bar.
        assertEquals(screen, Storage.wallBounds(screen,
            listOf(Storage.ScreenWindow(Storage.WindowKind.SYSTEM, Storage.Bounds(0, 0, 1080, 1200))), 2400))
        // A window entirely under the status bar keeps its own area rather than none.
        val sliver = Storage.Bounds(0, 0, 1080, 60)
        assertEquals(sliver, Storage.wallBounds(sliver, listOf(statusBar), 2400))
    }

    @Test
    fun `wall size - full on a phone screen, compact in a split half, tiny in a small pop-up`() {
        assertEquals(Storage.WallSize.FULL, Storage.wallSize(411, 915))
        assertEquals(Storage.WallSize.FULL, Storage.wallSize(160, 560))
        // Split-screen halves, portrait and landscape.
        assertEquals(Storage.WallSize.COMPACT, Storage.wallSize(411, 440))
        assertEquals(Storage.WallSize.COMPACT, Storage.wallSize(440, 380))
        // Stock Android's pop-up: room for the whole headline.
        assertEquals(Storage.WallSize.COMPACT, Storage.wallSize(220, 411))
        // OxygenOS's pop-up on a 1080-wide phone: 324x540 px at 3x.
        assertEquals(Storage.WallSize.TINY, Storage.wallSize(108, 180))
        // Too narrow or too short alone is enough.
        assertEquals(Storage.WallSize.TINY, Storage.wallSize(159, 900))
        assertEquals(Storage.WallSize.TINY, Storage.wallSize(411, 239))
        assertEquals(Storage.WallSize.COMPACT, Storage.wallSize(160, 240))
    }

    @Test
    fun `pop-up - short of the screen both ways`() {
        assertTrue(Storage.isPopUp(popup, 1080, 2400))
        assertTrue(Storage.isPopUp(Storage.Bounds(696, 120, 1020, 660), 1080, 2400))
        assertFalse(Storage.isPopUp(screen, 1080, 2400))
        // Split-screen halves span the width (portrait) or the height (landscape).
        assertFalse(Storage.isPopUp(top, 1080, 2400))
        assertFalse(Storage.isPopUp(bottom, 1080, 2400))
        assertFalse(Storage.isPopUp(Storage.Bounds(0, 0, 1180, 1080), 2400, 1080))
        // Full screen above a 3-button navigation bar, or beside one in landscape.
        assertFalse(Storage.isPopUp(Storage.Bounds(0, 0, 1080, 2274), 1080, 2400))
        assertFalse(Storage.isPopUp(Storage.Bounds(0, 0, 2274, 1080), 2400, 1080))
    }

    @Test
    fun `plan - spent apps walled, other limited apps timed, the rest left alone`() {
        val rules = mapOf(insta to Rule(5, 120), yt to Rule(10, 60))
        val spent = setOf(insta)
        val plan = Storage.plan(listOf(insta, yt, "com.android.launcher3"), rules) { pkg, _ -> pkg in spent }
        assertEquals(setOf(insta), plan.walled)
        assertEquals(setOf(yt), plan.timed)
        // Both spent -> both walled, nothing timed.
        assertEquals(Storage.Plan(setOf(insta, yt), emptySet()),
            Storage.plan(listOf(insta, yt), rules) { _, _ -> true })
        // Nothing on screen -> nothing to do.
        assertEquals(Storage.Plan(emptySet(), emptySet()), Storage.plan(emptyList(), rules) { _, _ -> true })
    }

    @Test
    fun `accessibility setting - finds our service in either spelling, among others`() {
        val cls = "xyz.robinroy.appblock.BlockerService"
        assertTrue(Storage.serviceEnabled("xyz.robinroy.appblock/xyz.robinroy.appblock.BlockerService", self, cls))
        assertTrue(Storage.serviceEnabled("xyz.robinroy.appblock/.BlockerService", self, cls))
        assertTrue(Storage.serviceEnabled(
            "com.google.android.marvin.talkback/.TalkBackService:xyz.robinroy.appblock/.BlockerService", self, cls))
        assertFalse(Storage.serviceEnabled(null, self, cls))
        assertFalse(Storage.serviceEnabled("", self, cls))
        assertFalse(Storage.serviceEnabled("com.google.android.marvin.talkback/.TalkBackService", self, cls))
        assertFalse(Storage.serviceEnabled("xyz.robinroy.appblock/.OtherService", self, cls))
    }

    @Test
    fun `picker durations - minutes, whole hours, mixed`() {
        assertEquals("0m", Storage.fmtDuration(0))
        assertEquals("0m", Storage.fmtDuration(59_999))
        assertEquals("1m", Storage.fmtDuration(60_000))
        assertEquals("45m", Storage.fmtDuration(45.min))
        assertEquals("1h", Storage.fmtDuration(60.min))
        assertEquals("1h 1m", Storage.fmtDuration(61.min))
        assertEquals("2h 12m", Storage.fmtDuration(132.min + 30_000))
    }

    @Test
    fun `rule spans - minutes, hours, days, mixed`() {
        assertEquals("5 min", Storage.fmtSpan(5))
        assertEquals("45 min", Storage.fmtSpan(45))
        assertEquals("1 hr", Storage.fmtSpan(60))
        assertEquals("1 hr 30 min", Storage.fmtSpan(90))
        assertEquals("2 hrs", Storage.fmtSpan(120))
        assertEquals("2 hrs 5 min", Storage.fmtSpan(125))
        assertEquals("1 day", Storage.fmtSpan(1440))
        assertEquals("1 day 6 hrs", Storage.fmtSpan(1800))
        assertEquals("7 days", Storage.fmtSpan(10080))
        assertEquals("2 days 30 min", Storage.fmtSpan(2910))
    }

    @Test
    fun `today's screen time - unused, under a minute, then whole minutes`() {
        assertEquals("Not used today", Storage.fmtToday(0))
        assertEquals("Less than a minute today", Storage.fmtToday(59_999))
        assertEquals("1 min today", Storage.fmtToday(60_000))
        assertEquals("1 hr 7 min today", Storage.fmtToday(67.min + 59_000))   // truncates
    }

    @Test
    fun `valid rule - some allowance, shorter than the window`() {
        assertTrue(Storage.validRule(Rule(5, 120)))
        assertTrue(Storage.validRule(Rule(360, 10080)))    // 6 hrs in any week
        assertFalse(Storage.validRule(Rule(0, 120)))       // no allowance
        assertFalse(Storage.validRule(Rule(30, 30)))       // never blocks
        assertFalse(Storage.validRule(Rule(60, 15)))
    }

    @Test
    fun `presets - every one is a valid rule`() {
        assertTrue(Storage.PRESETS.all(Storage::validRule))
    }

    @Test
    fun `limit unit - the largest unit that divides evenly`() {
        val all = Storage.SpanUnit.entries
        assertEquals(Storage.SpanUnit.HR, Storage.unitFor(120, all))
        assertEquals(Storage.SpanUnit.MIN, Storage.unitFor(90, all))
        assertEquals(Storage.SpanUnit.DAY, Storage.unitFor(1440, all))
        assertEquals(Storage.SpanUnit.DAY, Storage.unitFor(10080, all))
        assertEquals(Storage.SpanUnit.MIN, Storage.unitFor(60, listOf(Storage.SpanUnit.MIN)))
        assertEquals(Storage.SpanUnit.HR, Storage.unitFor(60, listOf(Storage.SpanUnit.MIN, Storage.SpanUnit.HR)))
    }

    @Test
    fun `limit amounts - digits only, 1 up to the cap`() {
        assertEquals(5, Storage.parseAmount(" 5 "))
        assertEquals(null, Storage.parseAmount(""))
        assertEquals(null, Storage.parseAmount("0"))
        assertEquals(null, Storage.parseAmount("-3"))
        assertEquals(null, Storage.parseAmount("1.5"))
        assertEquals(null, Storage.parseAmount("99999999999"))
    }

    @Test
    fun `limit rule - each side is a number and a unit`() {
        val (min, hr, day) = Storage.SpanUnit.entries
        assertEquals(Rule(5, 120), Storage.limitRule("5", min, "2", hr))
        assertEquals(Rule(90, 1440), Storage.limitRule("90", min, "1", day))
        assertEquals(Rule(360, 10080), Storage.limitRule("6", hr, "7", day))   // 6 hrs in any week
        assertEquals(Rule(59, 60), Storage.limitRule(" 59 ", min, "60", min))
    }

    @Test
    fun `limit rule - nonsense is rejected`() {
        val (min, hr, _) = Storage.SpanUnit.entries
        assertEquals(null, Storage.limitRule("", min, "2", hr))      // no allowance
        assertEquals(null, Storage.limitRule("0", min, "2", hr))
        assertEquals(null, Storage.limitRule("5", min, "", hr))      // no window
        assertEquals(null, Storage.limitRule("120", min, "2", hr))   // allowance = window
        assertEquals(null, Storage.limitRule("3", hr, "2", hr))      // allowance > window
    }

    @Test
    fun `limit error - flags only a complete rule that can't block`() {
        val (min, hr, _) = Storage.SpanUnit.entries
        assertEquals(null, Storage.limitError("5", min, "2", hr))
        assertEquals(null, Storage.limitError("", min, "2", hr))     // still typing
        assertEquals(null, Storage.limitError("5", min, "", hr))     // window not typed yet
        assertEquals("The allowance has to be shorter than the window.",
            Storage.limitError("120", min, "2", hr))
        assertEquals("The allowance has to be shorter than the window.",
            Storage.limitError("3", hr, "2", hr))
    }

    @Test
    fun `stepper - minutes go by ones, then by fives landing on fives`() {
        val min = Storage.SpanUnit.MIN
        assertEquals(6, Storage.stepAmount(5, min, up = true))
        assertEquals(10, Storage.stepAmount(9, min, up = true))
        assertEquals(15, Storage.stepAmount(10, min, up = true))
        assertEquals(15, Storage.stepAmount(12, min, up = true))
        assertEquals(9, Storage.stepAmount(10, min, up = false))
        assertEquals(10, Storage.stepAmount(15, min, up = false))
        assertEquals(10, Storage.stepAmount(12, min, up = false))
        assertEquals(1, Storage.stepAmount(1, min, up = false))      // never below 1
    }

    @Test
    fun `stepper - hours and days go by one`() {
        assertEquals(13, Storage.stepAmount(12, Storage.SpanUnit.HR, up = true))
        assertEquals(1, Storage.stepAmount(2, Storage.SpanUnit.DAY, up = false))
        assertEquals(1, Storage.stepAmount(1, Storage.SpanUnit.HR, up = false))
    }

    @Test
    fun `usage log - a flush continuing the last entry extends it`() {
        val log = listOf((now - 10.min) to (now - 5.min))
        // The service flushes a running session every few seconds: one entry, not many.
        assertEquals(listOf((now - 10.min) to now),
            Storage.appendUsage(log, now - 5.min, now, cutoff = now - 120.min))
    }

    @Test
    fun `usage log - a separate session is a new entry, aged-out ones dropped`() {
        val log = listOf((now - 200.min) to (now - 190.min), (now - 30.min) to (now - 20.min))
        assertEquals(listOf((now - 30.min) to (now - 20.min), (now - 5.min) to now),
            Storage.appendUsage(log, now - 5.min, now, cutoff = now - 120.min))
    }

    @Test
    fun `week-long window - wait spans days and lands on the right minute`() {
        val week = Rule(allowMin = 60, windowMin = 7 * 24 * 60)
        // An hour used a day ago: free again once it starts leaving the week,
        // 6 days minus an hour from now, plus the one minute that frees.
        val intervals = listOf((now - 25 * 60.min) to (now - 24 * 60.min))
        assertEquals((6 * 24 * 60 - 60 + 1).min, Storage.msUntilUnblocked(intervals, week, now))
    }

    @Test
    fun `used percent - exact, capped at 100`() {
        assertEquals(0, Storage.usedPct(0L, 5))
        assertEquals(52, Storage.usedPct(156_000, 5))              // 2.6 of 5 min
        assertEquals(90, Storage.usedPct(270_000, 5))
        assertEquals(100, Storage.usedPct(9.min, 5))               // overshoot
        assertEquals(100, Storage.usedPct(1.min, 0))
    }

    @Test
    fun `heavy overuse - wait is longer but never exceeds the window`() {
        val intervals = listOf((now - 60.min) to now)  // 60 min of use
        val wait = Storage.msUntilUnblocked(intervals, rule, now)
        // Usable once the window keeps less than 5 min of that hour: 116 min later.
        assertEquals(116.min, wait)
        assert(wait <= rule.windowMin * 60_000L)
    }
}
