package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.PHONE
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.homeScreenPackage
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openAsPopUp
import xyz.robinroy.appblock.E2e.resizePopUp
import xyz.robinroy.appblock.E2e.seedUsage
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.splitScreenWith
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import xyz.robinroy.appblock.E2e.windowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The core promise, end to end: the blocker service watching the real apps
 * on screen and drawing the block wall over a spent one, full screen, in
 * split screen and in a pop-up window. Budgets are seeded into the usage
 * log so no test waits out real minutes.
 */
@RunWith(AndroidJUnit4::class)
class BlockingE2eTest {

    // Cleanup is the outer rule, so a failure's screenshot is taken first.
    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        device.findObject(text("Go to home screen"))?.click()
        shell("settings put global enable_freeform_support 0")
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val wall = textContains("Clock is blocked")
    // The small pop-up's wall, case and all ("Clock is blocked." ends the same).
    private val shortWall = By.text(Pattern.compile("Blocked\\..*", Pattern.DOTALL))

    @Test
    fun spentBudget_wallCoversTheApp_andSendsYouHome() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)

        openApp(CLOCK)

        val message = waitFor(wall).text
        // The minute used 10-11 min ago leaves the 2-hr window 109-110 min from now.
        assertTrue(message, Regex("(?s).*Try again in 1 hr (49|50) min\\..*").matches(message))
        waitFor(text("Go to home screen")).click()
        assertTrue(device.wait(Until.gone(wall), E2e.WAIT))
        assertTrue(appears(By.pkg(E2e.homeScreenPackage())))
    }

    @Test
    fun budgetRunningOutMidSession_blocksWithoutReopening() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 6.min, endAgo = 6.min - 50_000, windowMin = 120)   // 50 s used

        openApp(CLOCK)

        assertFalse("blocked with 10 s still left", appears(wall, timeout = 3_000))
        // The last 10 s run out, then the service's 5 s check catches it.
        assertTrue("never blocked once the budget ran out", appears(wall, timeout = 30_000))
    }

    @Test
    fun usageOlderThanTheWindow_noLongerCounts() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 130.min, endAgo = 121.min, windowMin = 120)   // 9 min, aged out

        openApp(CLOCK)

        // Opening checks at once and the session ticks every 5 s: two chances to block.
        assertFalse("blocked by usage outside the window", appears(wall, timeout = 8_000))
        assertEquals(CLOCK, device.currentPackageName)
    }

    @Test
    fun appWithoutALimit_isLeftAlone() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)   // Clock: spent

        openApp(PHONE)

        assertFalse(appears(textContains("is blocked"), timeout = 8_000))
        assertEquals(PHONE, device.currentPackageName)
    }

    @Test
    fun withoutUsageAccess_blockingStillWorks() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")   // freshState grants it back

        openApp(CLOCK)

        waitFor(wall)
    }

    @Test
    fun spentAppInSplitScreen_wallCoversItsHalf_andTheOtherHalfStaysUsable() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)
        openApp(PHONE)
        openApp(CLOCK)
        waitFor(wall)

        splitScreenWith(PHONE)   // Clock on top, Phone below

        assertTrue("the wall didn't shrink to Clock's half", E2e.eventually {
            device.findObject(wall)?.let { windowBounds(it).bottom < device.displayHeight * 0.6 } ?: false
        })
        assertTrue("the wall came down over Clock in split screen", appears(wall, timeout = 3_000))
        assertTrue("Phone's half is covered", appears(By.pkg(PHONE)))
    }

    @Test
    fun budgetRunningOutInSplitScreen_blocksWhileTheOtherHalfIsUsed() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 6.min, endAgo = 6.min - 50_000, windowMin = 120)   // 50 s used
        openApp(PHONE)
        openApp(CLOCK)
        splitScreenWith(PHONE)

        // Clock's half keeps using up its time though Phone is the app in use.
        assertTrue("never blocked once the budget ran out", appears(wall, timeout = 30_000))
    }

    @Test
    fun twoLimitedAppsInSplitScreen_eachShowsItsTimer() {
        freshState(mapOf(
            CLOCK to Rule(allowMin = 5, windowMin = 120),
            PHONE to Rule(allowMin = 5, windowMin = 120)))
        openApp(PHONE)
        openApp(CLOCK)
        splitScreenWith(PHONE)
        device.openNotification()

        waitFor(text("Clock: time left"))
        waitFor(text("Phone: time left"))
        device.pressBack()
    }

    @Test
    fun spentAppInAPopUp_wallCoversJustThePopUp_andHomeStaysUsable() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)

        openAsPopUp(CLOCK)

        val wallBounds = windowBounds(waitFor(wall))
        assertTrue("the wall ($wallBounds) covers more than the pop-up",
            wallBounds.width() < device.displayWidth && wallBounds.height() < device.displayHeight / 2 + 600)
        // Its own close button is under the wall, so the wall says how to close it.
        waitFor(text(CLOSE_POP_UP))
        // Home is right there beside it, so there's no button to go there.
        assertFalse(appears(text("Go to home screen"), timeout = 1_000))
        assertTrue("the home screen is covered", appears(By.pkg(homeScreenPackage())))
    }

    @Test
    fun spentAppInASmallPopUp_wallSaysLess_soNothingBreaksMidWord() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)

        openAsPopUp(CLOCK)
        // OxygenOS's pop-ups are about 110 x 180 dp; Android won't make
        // one under 220 dp, which is still too short for the full text.
        resizePopUp(CLOCK, widthDp = 220, heightDp = 220)

        val message = waitFor(shortWall).text
        assertTrue(message, Regex("Blocked\\.\\s+Try again in 1 hr (49|50) min\\.").matches(message))
        assertFalse(appears(wall, timeout = 1_000))
        val wallBounds = windowBounds(device.findObject(shortWall))
        val hint = waitFor(text(CLOSE_POP_UP)).visibleBounds
        assertTrue("the hint ($hint) runs out of the wall ($wallBounds)", wallBounds.contains(hint))
    }

    @Test
    fun usingALimitedApp_showsTheSessionTimer() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))

        openApp(CLOCK)
        device.openNotification()

        waitFor(text("Clock: time left"))
        device.pressBack()
    }

    private companion object {
        const val CLOSE_POP_UP = "To close it, swipe it away in Recents."
    }
}
