package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.YOUTUBE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.textContains
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Do the shipped rules (rules/rules.json) still catch the real YouTube's
 * Shorts? Needs a current YouTube on the device and the network, and
 * YouTube changes under us, so it's not part of the e2e suite: it runs
 * only when asked (`canary=true`), in CI's youtube-canary job and
 * scripts/youtube.sh. A failure here means the rules need updating.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeCanaryTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        shell("am force-stop $YOUTUBE")
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val feedWall = textContains("Shorts are blocked")

    @Before
    fun onlyWhenAsked() {
        assumeTrue("the YouTube canary runs only with canary=true",
            InstrumentationRegistry.getArguments().getString("canary") == "true")
    }

    @Test
    fun shortsTab_walled_homeTabNot() {
        freshState(mapOf(YOUTUBE to Rule(allowMin = 30, windowMin = 60)))
        Storage.setFeedBlocked(ctx, YOUTUBE, true)
        // YouTube's own notifications prompt would sit over its first screen.
        shell("pm grant $YOUTUBE android.permission.POST_NOTIFICATIONS")
        openApp(YOUTUBE)

        val shortsTab = By.desc("Shorts").clickable(true)
        assertTrue("no Shorts tab: is YouTube current (scripts/youtube.sh)?",
            device.wait(Until.hasObject(shortsTab), 30_000))
        assertFalse("walled YouTube's home tab", appears(feedWall, timeout = 5_000))

        device.findObject(shortsTab).click()
        assertTrue("Shorts not walled: the rules (rules/rules.json) need updating", appears(feedWall, timeout = 20_000))

        E2e.waitFor(E2e.text("Go back")).click()
        assertTrue("wall stayed up after going back", device.wait(Until.gone(feedWall), E2e.WAIT))
    }
}
