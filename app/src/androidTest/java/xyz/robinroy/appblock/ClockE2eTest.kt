package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.seedUsage
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setting the phone's clock, the way a user would to get their time back
 * (Settings > Date & time, automatic time off): a spent app stays spent,
 * forward or back, and an open app is charged only the time it was really
 * on screen (issue #7).
 */
@RunWith(AndroidJUnit4::class)
class ClockE2eTest {

    /** How far the test has moved the clock, put back after it. */
    private var moved = 0L

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        if (moved != 0L) setClock(System.currentTimeMillis() - moved)
        moved = 0
        shell("settings put global auto_time 1")
        // Moved back before the next test seeds usage, not whenever TIME_SET arrives.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Clock.check(ctx) }
        device.findObject(text("Go to home screen"))?.click()
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val wall = textContains("Clock is blocked")

    private fun setClock(ms: Long) {
        shell("cmd alarm set-time $ms")
    }

    /** The clock set `by` ms forward (or back, negative), as the user would with automatic time off. */
    private fun moveClock(by: Long) {
        shell("settings put global auto_time 0")
        setClock(System.currentTimeMillis() + by)
        moved += by
    }

    private fun spentClockApp() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)
        openApp(CLOCK)
        waitFor(wall)
        waitFor(text("Go to home screen")).click()
        device.pressHome()
    }

    @Test
    fun clockSetForward_appStaysBlocked() {
        spentClockApp()

        // Three hours on, the 2-hour window would hold none of that minute.
        moveClock(180.min)
        openApp(CLOCK)

        assertTrue("setting the clock forward gave the time back", appears(wall))
    }

    @Test
    fun clockSetBack_appStaysBlocked() {
        spentClockApp()

        // Three hours back, that minute would be in the future.
        moveClock(-180.min)
        openApp(CLOCK)

        assertTrue("setting the clock back gave the time back", appears(wall))
    }

    @Test
    fun clockSetForwardMidSession_countsOnlyTheTimeUsed() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        openApp(CLOCK)
        // A few seconds in, the session's start in the log.
        assertFalse(appears(wall, timeout = 3_000))

        // Three hours on: the session is seconds long, not three hours.
        moveClock(180.min)

        // Two of the blocker's 5 s ticks.
        assertFalse("the clock's jump was counted as use", appears(wall, timeout = 12_000))
        assertTrue(Storage.usedMsInWindow(ctx, CLOCK, 120) < 1.min)
    }
}
