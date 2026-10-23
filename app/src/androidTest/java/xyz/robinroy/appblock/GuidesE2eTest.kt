package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The reading pages behind the About button. */
@RunWith(AndroidJUnit4::class)
class GuidesE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup { device.pressBack(); device.pressHome() }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    @Test
    fun howItWorks_showsTheRollingWindowUnderTheLoop() {
        // A rule, so home has no empty state with its own "How it works" link.
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        openHome()
        waitFor(By.desc("About")).click()
        waitFor(text("How it works")).click()

        val heading = waitFor(text("A few minutes, on a loop")).visibleBounds
        val window = waitFor(By.descContains("ageing out of the last two hours")).visibleBounds
        assertTrue("the rolling window ($window) isn't on screen", window.width() > 0 && window.height() > 0)
        assertTrue("the rolling window ($window) isn't under the loop's heading ($heading)",
            window.top >= heading.bottom)
    }
}
