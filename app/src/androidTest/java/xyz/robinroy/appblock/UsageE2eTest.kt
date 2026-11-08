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
import java.time.ZoneId

/**
 * Screen time on the home screen: the day's card, stepping through the
 * history, the Usage page with every app, the card asking for usage access
 * (and hiding for good), and the real thing: an app opened shows up in the
 * history. The history is seeded on past days at fixed hours, so a run
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
        waitFor(text("Today"))
        assertFalse("there's no tomorrow", waitFor(By.desc("Next day")).isEnabled)

        waitFor(By.desc("Previous day")).click()
        waitFor(text("Yesterday"))
        waitFor(text("55 minutes"))
        waitFor(text("40 minutes"))   // limited apps: Clock
        waitFor(text("Busiest hour: 9 PM, 40 minutes"))

        waitFor(By.desc("Previous day")).click()
        waitFor(text(Storage.dayTitle(today.minusDays(2), today)))
        waitFor(text("5 minutes"))
        // The history starts here: no further back.
        assertTrue(E2e.eventually { !device.findObject(By.desc("Previous day")).isEnabled })

        waitFor(By.desc("Next day")).click()
        waitFor(By.desc("Next day")).click()
        waitFor(text("Today"))
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

        // Tapping it opens the usage access settings.
        waitFor(textContains("Allow usage access")).click()
        assertTrue("usage access settings didn't open", appears(By.pkg("com.android.settings")))
        openHome()

        // Hidden: it says where to turn it on again, and stays hidden.
        waitFor(By.desc("Hide this hint")).click()
        waitFor(textContains("tap ⓘ"))
        E2e.waitStill(text("Yes, hide it")).click()
        assertTrue("the sheet didn't close", device.wait(Until.gone(text("Hide screen time?")), E2e.WAIT))
        assertTrue(Storage.usageCardDismissed(ctx))
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
        // AppBlock is on screen now: shown, though not stored until it leaves.
        waitFor(By.descStartsWith("AppBlock, "))
        assertTrue(History.allForTests(ctx).none { it.pkg == ctx.packageName && it.end > since })
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
}
