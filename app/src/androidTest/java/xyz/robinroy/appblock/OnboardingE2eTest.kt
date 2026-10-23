package xyz.robinroy.appblock

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first-run pages, walked the way a new user does. */
@RunWith(AndroidJUnit4::class)
class OnboardingE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup { device.pressHome() }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    @Test
    fun theRulePage_showsTheRollingWindow() {
        freshState()
        openHome()
        ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(text("Tie yourself to the mast."))
        waitFor(text("Continue")).click()
        waitFor(text("A small allowance, on a loop."))

        val window = waitFor(By.descContains("ageing out of the last two hours")).visibleBounds
        val heading = waitFor(text("5 min")).visibleBounds
        assertTrue("the rolling window ($window) isn't on screen", window.width() > 0 && window.height() > 0)
        assertTrue("the rolling window ($window) isn't under the 5 min heading ($heading)",
            window.top >= heading.bottom)
    }
}
