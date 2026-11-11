package xyz.robinroy.appblock

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.WAIT
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.limitFields
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where Moly meets the system: the back gesture, and the status and
 * navigation bars. Both changed under apps targeting Android 15/16 (back no
 * longer reaches onBackPressed; screens draw behind the bars).
 */
@RunWith(AndroidJUnit4::class)
class SystemUiE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup { device.pressHome() }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    @Test
    fun onboarding_backStepsToThePageBefore_thenLeaves() {
        freshState()
        openHome()
        ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(text("Tie yourself to the mast."))
        waitFor(text("Continue")).click()
        waitFor(text("A small allowance, on a loop."))

        device.pressBack()
        assertTrue("back left page 2",
            device.wait(Until.gone(text("A small allowance, on a loop.")), WAIT))
        waitFor(text("Tie yourself to the mast."))

        device.pressBack()
        waitFor(text("Block an app"))
    }

    @Test
    fun infoSheet_backStepsToThePageBefore_thenCloses() {
        freshState()
        openHome()
        waitFor(By.desc("About")).click()
        waitFor(text("Philosophy")).click()
        waitFor(text("Tie yourself to the mast."))
        assertTrue("Philosophy didn't open", device.wait(Until.gone(text("Philosophy")), WAIT))

        device.pressBack()
        waitFor(text("Philosophy"))
        assertTrue("back left Philosophy open",
            device.wait(Until.gone(text("Tie yourself to the mast.")), WAIT))

        device.pressBack()
        assertTrue("back didn't close the sheet", device.wait(Until.gone(text("Philosophy")), WAIT))
        waitFor(text("Block an app"))
    }

    @Test
    fun home_staysClearOfTheStatusAndNavigationBars() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        freshState()
        openHome()
        val about = waitFor(By.desc("About")).visibleBounds
        val add = waitFor(text("Block an app")).visibleBounds
        val bars = systemBars()
        val height = device.displayHeight
        assertTrue("About ($about) is under the status bar (${bars.top}px)", about.top >= bars.top)
        assertTrue("Block an app ($add) is under the navigation bar (${bars.bottom}px)",
            add.bottom <= height - bars.bottom)
    }

    @Test
    fun limitSheet_reachesTheBottomEdge_andRisesAboveTheKeyboard() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        waitFor(By.desc("Set limit for Clock")).click()
        var sheet = waitFor(text("Set limit"))
        while (sheet.parent != null) sheet = sheet.parent
        val bars = systemBars()   // home is still the resumed activity under the sheet
        val height = device.displayHeight
        assertEquals("the sheet stops short of the bottom edge", height, sheet.visibleBounds.bottom)
        val ok = waitFor(text("OK")).visibleBounds
        assertTrue("OK ($ok) is under the navigation bar (${bars.bottom}px)", ok.bottom <= height - bars.bottom)

        limitFields()[0].click()
        val keyboard = keyboardBounds()
        assertNotNull("the keyboard didn't open", keyboard)
        val okNow = waitFor(text("OK")).visibleBounds
        assertTrue("OK ($okNow) is under the keyboard ($keyboard)", okNow.bottom <= keyboard!!.top)
    }

    /** Where the on-screen keyboard is, once it's up (null if it never opens). */
    private fun keyboardBounds(): Rect? {
        val deadline = System.currentTimeMillis() + WAIT
        while (System.currentTimeMillis() < deadline) {
            E2e.uiAutomation.windows
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { w -> return Rect().also { w.getBoundsInScreen(it) } }
            device.waitForIdle()
        }
        return null
    }

    /** The status and navigation bars' insets as the system reports them to the front activity. */
    private fun systemBars(): Rect {
        var bars = Rect()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity: Activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single()
            val i = activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
            bars = Rect(i.left, i.top, i.right, i.bottom)
        }
        return bars
    }
}
