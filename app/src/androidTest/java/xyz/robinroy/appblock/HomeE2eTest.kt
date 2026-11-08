package xyz.robinroy.appblock

import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.limitFields
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.pressOk
import xyz.robinroy.appblock.E2e.seedUsage
import xyz.robinroy.appblock.E2e.setBlocker
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home screen and the limit dialog: what you set there is what gets
 * saved and what the blocker enforces.
 */
@RunWith(AndroidJUnit4::class)
class HomeE2eTest {

    // Cleanup is the outer rule, so a failure's screenshot is taken first.
    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        device.findObject(text("Go to home screen"))?.click()
        // A test failing mid-way must not leave the blocker off for the next one.
        setBlocker(true)
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private fun openLimitDialog(label: String) {
        waitFor(By.desc("Set limit for $label")).click()
        waitFor(text("Set limit"))
    }

    @Test
    fun typedLimit_isSaved_homeShowsIt_andTheBlockerEnforcesIt() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        // Allow is in minutes, "in any" opens in hours (120 min = 2 hrs).
        val (allow, window) = limitFields()
        allow.text = "10"
        window.text = "1"
        pressOk()

        waitFor(text("10 minutes in any 1 hour · 0 seconds used"))
        assertEquals(Rule(10, 60), Storage.loadRules(ctx)[CLOCK])

        // 10 min used 20-30 min ago: spent under the new rule (under the old
        // 5-in-2-hrs it would be spent too, so check the wait reflects 1 hr).
        seedUsage(CLOCK, startAgo = 30.min, endAgo = 20.min, windowMin = 60)
        openApp(CLOCK)
        val message = waitFor(textContains("Clock is blocked")).text
        assertTrue(message, Regex("(?s).*Try again in (30|31) minutes\\..*").matches(message))
    }

    @Test
    fun steppersAndUnits_changeTheLimit() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        waitFor(By.desc("Allow: more")).click()      // 5 -> 6 min
        waitFor(By.desc("In any: less")).click()     // 2 -> 1 hr
        waitFor(text("6 minutes in any 1 hour"))           // the live summary follows
        pressOk()

        waitFor(text("6 minutes in any 1 hour · 0 seconds used"))
        assertEquals(Rule(6, 60), Storage.loadRules(ctx)[CLOCK])

        // Switching the window's unit keeps the number: 1 hr -> 1 day.
        openLimitDialog("Clock")
        waitFor(text("days")).click()
        pressOk()

        waitFor(text("6 minutes in any 1 day · 0 seconds used"))
        assertEquals(Rule(6, 1440), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun quickPick_fillsBothSides() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        waitFor(text("30 minutes in 4 hours")).click()
        pressOk()

        waitFor(text("30 minutes in any 4 hours · 0 seconds used"))
        assertEquals(Rule(30, 240), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun allowanceAsLongAsTheWindow_isRefused_untilItFits() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        val (allow, window) = limitFields()
        window.text = "1"          // 1 hr
        allow.text = "60"         // as long as the window: would never block
        assertFalse(waitFor(text("OK")).isEnabled)
        waitFor(textContains("shorter than the window"))

        allow.text = "59"
        assertTrue(device.wait(Until.hasObject(text("OK").enabled(true)), E2e.WAIT))
        pressOk()

        waitFor(text("59 minutes in any 1 hour · 0 seconds used"))
        assertEquals(Rule(59, 60), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun cancel_changesNothing() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        limitFields()[0].text = "20"
        waitFor(text("Cancel")).click()

        waitFor(text("5 minutes in any 2 hours · 0 seconds used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun remove_asksFirst_thenTheAppIsGone() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        openLimitDialog("Clock")

        waitFor(text("Remove")).click()
        waitFor(text("Remove Clock?"))
        waitFor(text("Yes")).click()

        waitFor(textContains("No apps blocked yet"))
        assertTrue(Storage.loadRules(ctx).isEmpty())
    }

    @Test
    fun pickingAnApp_addsItWithTheDefaultLimit() {
        freshState()
        openHome()

        waitFor(text("Block an app")).click()
        waitFor(By.clazz(EditText::class.java)).text = "Clock"   // the search box
        waitFor(text("Clock").clazz(TextView::class.java)).click()   // the row, not the search box
        waitFor(text("Block 1 selected app")).click()

        waitFor(text("5 minutes in any 2 hours · 0 seconds used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun losingAccessibility_pausesBlocking_andKeepsTheRules() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        setBlocker(false)
        openHome()

        waitFor(textContains("Blocking is paused"))
        assertFalse(appears(By.desc("Set limit for Clock"), timeout = 1_000))

        setBlocker(true)
        openHome()

        waitFor(text("5 minutes in any 2 hours · 0 seconds used"))
        assertEquals(Rule(5, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun turningOnAccessibility_explainsWhatItSees_thenOpensItsSettings() {
        freshState()
        setBlocker(false)
        openHome()

        waitFor(text("Accessibility")).click()
        waitFor(text("Turn on AppBlock in Accessibility"))
        waitFor(textContains("never reads what's on screen"))
        waitFor(text("Agree and continue")).click()

        assertTrue("Accessibility settings didn't open",
            appears(By.pkg("com.android.settings"), timeout = E2e.WAIT))
    }

    @Test
    fun home_saysTheTimeUsedExactly_inFullWords() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 30.min, endAgo = 30.min - 125_400, windowMin = 120)   // 2:05.4
        openHome()
        // To the second, cut rather than rounded up to "3 min".
        waitFor(text("5 minutes in any 2 hours · 2 minutes 5 seconds used"))
    }

    @Test
    fun withoutUsageAccess_homeShowsTimeLeftInsteadOfScreenTime() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 30.min, endAgo = 28.min, windowMin = 120)
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")   // freshState grants it back
        openHome()

        // Optional: blocking stays on, the row just can't show today's screen time.
        waitFor(text("3 minutes left"))
        assertFalse(appears(textContains("Blocking is paused"), timeout = 1_000))
    }
}
