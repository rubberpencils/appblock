package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.PHONE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.homeScreenPackage
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZoneId

/**
 * Screen time on the home screen: the day's card, stepping through the
 * history and jumping to a day on the calendar, the Usage page with every
 * app, an app's own page with its patterns, leaving an app out of screen
 * time, the card asking for usage access (and hiding for good), and the
 * real thing: an app opened shows up in the history. The history is seeded on past days at fixed hours, so a run
 * near midnight can't move it.
 */
@RunWith(AndroidJUnit4::class)
class UsageE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        History.autoSync = false
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val zone = ZoneId.systemDefault()
    private val today get() = LocalDate.now(zone)

    /** `pkg` on screen on `day` from `h`:`m` for `minutes`. */
    private fun used(pkg: String, day: LocalDate, h: Int, m: Int, minutes: Int): Storage.Session {
        val start = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        return Storage.Session(pkg, start, start + minutes.min)
    }

    /** Yesterday: Clock (limited) 9:00-9:40 PM, Phone 8:00-8:15 AM; the day before, Phone for 5 minutes. */
    private fun seed() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 1440)))
        val yesterday = today.minusDays(1)
        History.insertForTests(ctx, listOf(
            used(CLOCK, yesterday, 21, 0, 40), used(PHONE, yesterday, 8, 0, 15),
            used(PHONE, yesterday.minusDays(1), 12, 0, 5)))
    }

    @Test
    fun card_stepsThroughTheDays() {
        seed()
        openHome()
        E2e.waitForFresh(text("Today"))
        assertFalse("there's no tomorrow", waitFor(By.desc("Next day")).isEnabled)

        waitFor(By.desc("Previous day")).click()
        E2e.waitForFresh(text("Yesterday"))
        E2e.waitForFresh(text("55 minutes"))
        E2e.waitForFresh(text("40 minutes"))   // limited apps: Clock
        E2e.waitForFresh(text("Busiest hour: 9 PM, 40 minutes"))

        waitFor(By.desc("Previous day")).click()
        E2e.waitForFresh(text(Storage.dayTitle(today.minusDays(2), today)))
        E2e.waitForFresh(text("5 minutes"))
        // The history starts here: no further back.
        assertTrue(E2e.eventually { !device.findObject(By.desc("Previous day")).isEnabled })

        waitFor(By.desc("Next day")).click()
        waitFor(By.desc("Next day")).click()
        E2e.waitForFresh(text("Today"))
    }

    @Test
    fun card_opensTheUsagePage_appsMostFirst() {
        seed()
        openHome()
        waitFor(By.desc("Previous day")).click()
        waitFor(text("Yesterday"))
        waitFor(text("See apps")).click()

        // The Usage page, on the same day.
        waitFor(text("Apps"))
        waitFor(text("Yesterday"))
        val clock = waitFor(By.desc("Clock, 40 minutes, limited"))
        val phone = waitFor(By.desc("Phone, 15 minutes"))
        assertTrue("most used first", clock.visibleBounds.top < phone.visibleBounds.top)
        assertTrue(appears(text("Limited")))
    }

    @Test
    fun withoutUsageAccess_theCardAsks_andCanBeHiddenForGood() {
        freshState()
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")
        openHome()
        assertFalse(appears(text("Screen time"), timeout = 1_000))

        // Tapping it opens the usage access settings; Back comes back to it.
        waitFor(textContains("Allow usage access")).click()
        assertTrue("usage access settings didn't open", appears(By.pkg("com.android.settings")))
        device.pressBack()
        waitFor(textContains("Allow usage access"))

        // Hidden: it says where to turn it on again, and stays hidden.
        waitFor(By.desc("Hide this hint")).click()
        waitFor(textContains("tap ⓘ"))
        E2e.waitStill(text("Yes, hide it")).click()
        // (The app's own record, waited for: UI Automator can lose sight of the sheet's window and miss it closing.)
        assertTrue("hiding wasn't saved", E2e.eventually { Storage.usageCardDismissed(ctx) })
        assertTrue("the sheet didn't close", device.wait(Until.gone(text("Hide screen time?")), E2e.WAIT))
        assertTrue(E2e.eventually { device.findObject(textContains("Allow usage access")) == null })
        openHome()
        assertFalse(appears(textContains("Allow usage access"), timeout = 2_000))

        // Allowed later (from ⓘ): the stats are there.
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        openHome()
        waitFor(text("Screen time"))
    }

    @Test
    fun today_syncsByItself_andShowsWhatsOnScreenNow() {
        freshState()
        History.autoSync = true   // as in the app: freshState turns it off for the other tests
        val since = System.currentTimeMillis()
        openApp(CLOCK)
        Thread.sleep(5_000)
        device.pressHome()
        openHome()

        // The card synced on its own: Clock's few seconds are on today's list.
        waitFor(text("See apps")).click()
        waitFor(text("Apps"))
        waitFor(By.descStartsWith("Clock, "))
        assertTrue(History.allForTests(ctx).any { it.pkg == CLOCK && it.end > since })
        // AppBlock is on screen now: shown, though not stored until it leaves. (Far down the
        // list on an emulator with a day of real use behind it.)
        E2e.scrollTo(By.descStartsWith("AppBlock, "))
        assertTrue(History.allForTests(ctx).none { it.pkg == ctx.packageName && it.end > since })

        // An app's own page has its sessions alone: not AppBlock's, on screen now.
        val done = java.util.concurrent.CountDownLatch(1)
        var clockToday: History.Span? = null
        History.loadApp(ctx, CLOCK, today, today, zone) { clockToday = it; done.countDown() }
        assertTrue(done.await(E2e.WAIT, java.util.concurrent.TimeUnit.MILLISECONDS))
        val got = clockToday!!.sessions
        assertTrue("Clock's page read $got", got.isNotEmpty() && got.all { it.pkg == CLOCK })
    }

    @Test
    fun anAppOpened_isInTheHistory_onceAndOnlyOnce() {
        freshState()
        // (A first sync reads back two weeks: other tests' Clock use is in there too.)
        val since = System.currentTimeMillis()
        openApp(CLOCK)
        Thread.sleep(5_000)   // real use, for the real events
        device.pressHome()
        assertTrue(E2e.eventually { device.findObject(By.pkg(homeScreenPackage())) != null })
        Thread.sleep(1_000)

        History.syncForTests(ctx)
        val clock = History.allForTests(ctx).filter { it.pkg == CLOCK && it.end > since }
        assertEquals("one Clock session: $clock", 1, clock.size)
        assertTrue("Clock's session was ${clock[0].end - clock[0].start} ms", clock[0].end - clock[0].start in 3_000..30_000)
        assertTrue("the home screen isn't an app's screen time",
            History.allForTests(ctx).none { it.pkg == homeScreenPackage() })

        // Synced again: nothing doubled.
        val before = History.allForTests(ctx)
        History.syncForTests(ctx)
        assertEquals(before, History.allForTests(ctx))
    }

    /** The calendar's cell for `day`. */
    private fun cell(day: LocalDate) =
        By.descStartsWith(day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)))

    @Test
    fun calendar_jumpsStraightToADay() {
        seed()
        val weekAgo = today.minusDays(6)
        History.insertForTests(ctx, listOf(used(PHONE, weekAgo, 10, 0, 25)))
        openHome()
        E2e.waitForFresh(text("Today"))

        waitFor(By.desc("Today, pick a day")).click()
        if (YearMonth.from(weekAgo) != YearMonth.from(today)) waitFor(By.desc("Previous month")).click()
        // Before the history starts: there's nothing to pick.
        val before = weekAgo.minusDays(1)
        if (YearMonth.from(before) == YearMonth.from(weekAgo)) assertFalse(waitFor(cell(before)).isEnabled)
        waitFor(cell(weekAgo)).click()

        // The sheet closes on the day picked.
        assertTrue(device.wait(Until.gone(By.desc("Previous month")), E2e.WAIT))
        E2e.waitForFresh(text(Storage.dayTitle(weekAgo, today)))
        E2e.waitForFresh(text("25 minutes"))

        // Back to today in one tap.
        waitFor(By.desc("${Storage.dayTitle(weekAgo, today)}, pick a day")).click()
        E2e.waitStill(text("Today")).click()
        E2e.waitForFresh(By.desc("Today, pick a day"))
    }

    @Test
    fun anApp_hasItsOwnPage_withItsPatterns() {
        seed()
        // Clock again the day before yesterday, 9:10-9:30 PM: two days of it, 60 minutes.
        History.insertForTests(ctx, listOf(used(CLOCK, today.minusDays(2), 21, 10, 20)))
        openHome()
        waitFor(By.desc("Previous day")).click()
        E2e.waitForFresh(text("Yesterday"))
        waitFor(text("See apps")).click()
        waitFor(By.desc("Clock, 40 minutes, limited")).click()

        // Its page, on the same day: its time alone.
        waitFor(text("Count in screen time"))
        waitFor(text("Limited"))
        E2e.waitForFresh(text("Yesterday"))
        E2e.waitForFresh(text("40 minutes"))
        E2e.waitForFresh(text("Busiest hour: 9 PM, 40 minutes"))

        // The last 7 days, which are 3 here: the history starts the day before yesterday.
        E2e.waitForFresh(By.desc("Daily average: 20 minutes, Over 3 days"))
        waitFor(By.desc("Most used day: Yesterday, 40 minutes"))
        waitFor(By.desc("Opened: 2 times, About 30 minutes each"))
        waitFor(By.desc("Longest session: 40 minutes"))
        E2e.scrollTo(By.desc("Peak hours, 7 days: 9 PM to 10 PM"))

        // All time: the same history, the same answers.
        waitFor(By.scrollable(true)).fling(androidx.test.uiautomator.Direction.UP)
        E2e.waitStill(text("All time")).click()
        E2e.waitForFresh(By.desc("Daily average: 20 minutes, Over 3 days"))
        E2e.scrollTo(By.desc("Peak hours, All time: 9 PM to 10 PM"))
    }

    @Test
    fun anAppNotCounted_leavesTheTotals_butKeepsItsPage() {
        seed()
        openHome()
        waitFor(By.desc("Previous day")).click()
        E2e.waitForFresh(text("55 minutes"))
        waitFor(text("See apps")).click()
        waitFor(By.desc("Phone, 15 minutes")).click()

        // Off: the page says so, and still shows its time.
        waitFor(text("Count in screen time")).click()
        assertTrue("not saved", E2e.eventually { PHONE in Storage.screenTimeIgnored(ctx) })
        E2e.waitForFresh(text("Not counted"))
        E2e.waitForFresh(text("15 minutes"))

        // The Usage page: out of the total, listed apart.
        device.pressBack()
        E2e.waitForFresh(By.desc("Phone, 15 minutes, not counted"))
        E2e.waitForFresh(text("40 minutes"))
        assertFalse(appears(text("55 minutes"), timeout = 1_000))
        // Home too.
        device.pressBack()
        E2e.waitForFresh(text("See apps"))
        E2e.waitForFresh(text("Yesterday"))
        assertFalse(appears(text("55 minutes"), timeout = 1_000))

        // And counted again.
        waitFor(text("See apps")).click()
        waitFor(By.desc("Phone, 15 minutes, not counted")).click()
        waitFor(text("Count in screen time")).click()
        assertTrue("not saved", E2e.eventually { PHONE !in Storage.screenTimeIgnored(ctx) })
        device.pressBack()
        E2e.waitForFresh(By.desc("Phone, 15 minutes"))
        E2e.waitForFresh(text("55 minutes"))
    }

    @Test
    fun usagePage_searchesSorts_andListsTheAppsWithNoUse() {
        seed()
        openHome()
        waitFor(By.desc("Previous day")).click()
        E2e.waitForFresh(text("Yesterday"))
        waitFor(text("See apps")).click()
        fun above(a: String, b: String) =
            E2e.waitForFresh(By.descStartsWith("$a, ")).visibleBounds.top < E2e.waitForFresh(By.descStartsWith("$b, ")).visibleBounds.top

        // Time, most first; tapped again, least first. Name: A first; again, Z first.
        assertTrue(above("Clock", "Phone"))
        waitFor(By.descStartsWith("Sort by Time")).click()
        assertTrue(E2e.eventually { above("Phone", "Clock") })
        waitFor(By.descStartsWith("Sort by Name")).click()
        assertTrue(E2e.eventually { above("Clock", "Phone") })
        waitFor(By.descStartsWith("Sort by Name")).click()
        assertTrue(E2e.eventually { above("Phone", "Clock") })

        // Search: a part of a name, any case.
        waitFor(text("Search apps")).text = "cLo"
        E2e.waitForFresh(By.desc("Clock, 40 minutes, limited"))
        assertTrue(E2e.eventually { device.findObject(By.descStartsWith("Phone, ")) == null })

        // The apps with nothing that day: at the end, on request.
        waitFor(By.clazz("android.widget.EditText")).text = "fixture"
        assertTrue(E2e.eventually { device.findObject(By.descStartsWith("Clock, ")) == null })
        assertFalse(appears(By.desc("Feed fixture, No usage"), timeout = 1_000))
        E2e.scrollTo(By.descStartsWith("Apps with no logged usage, 1")).click()
        E2e.scrollTo(By.desc("Feed fixture, No usage")).click()
        // Its page: nothing that day, but it's there.
        waitFor(text("Count in screen time"))
        E2e.waitForFresh(text("Not used on this day."))
    }

    /** Where the chart draws `fraction` of its width: its plot stops short of the minute marks on the right. */
    private fun plotX(chart: androidx.test.uiautomator.UiObject2, fraction: Double, widestMark: String): Int {
        val label = android.graphics.Paint().apply { textSize = Ui.dpf(ctx, 11f); typeface = Ui.sans(ctx) }
        val b = chart.visibleBounds
        val plot = b.width() - (label.measureText(widestMark) + Ui.dpf(ctx, 8f))
        return (b.left + plot * fraction).toInt()
    }

    @Test
    fun chart_readsOffASlice_andLetsGoOnATapOutside() {
        seed()
        openHome()
        waitFor(By.desc("Previous day")).click()
        val chart = E2e.waitForFresh(By.descStartsWith("Screen time, Yesterday: 55 minutes"))

        // 9:15 to 9:30 PM: all Clock, exactly 15 minutes. (The page stays: a tap on the chart reads it.)
        device.click(plotX(chart, (21 * 60 + 22) / 1440.0, Storage.fmtTick(15)), chart.visibleBounds.centerY())
        E2e.waitForFresh(By.desc("9:15 to 9:30 PM: Limited apps: 15 minutes, Other apps: 0 seconds"))
        assertFalse(appears(text("Apps"), timeout = 500))

        // A tap outside the card: the chart as it was.
        waitFor(text("Blocked apps")).click()
        E2e.waitForFresh(By.descStartsWith("Screen time, Yesterday: 55 minutes"))

        // An app's page: its peak hours read off the same way, a day's worth on average.
        History.insertForTests(ctx, listOf(used(CLOCK, today.minusDays(2), 21, 10, 20)))
        waitFor(text("See apps")).click()
        waitFor(By.desc("Clock, 40 minutes, limited")).click()
        val bars = E2e.scrollTo(By.descStartsWith("Peak hours, 7 days"))
        device.click(plotX(bars, 21.5 / 24, Storage.fmtTick(60)), bars.visibleBounds.centerY())
        // 60 minutes at 9 PM over the 3 days of history.
        E2e.waitForFresh(By.desc("9 PM to 10 PM: 20 minutes a day on average"))
        waitFor(By.descStartsWith("Longest session")).click()
        E2e.waitForFresh(By.desc("Peak hours, 7 days: 9 PM to 10 PM"))
    }
}
