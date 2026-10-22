package xyz.robinroy.appblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.FIXTURE
import xyz.robinroy.appblock.E2e.YOUTUBE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.waitFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Blocking a feed (YouTube's Shorts): the switch on the home list, and the
 * wall that goes up while the feed is on screen. The wall is tested on the
 * fixture app (fixture/), which has the Shorts player's view id, under
 * rules naming it; the real YouTube is covered by the youtube-canary job.
 */
@RunWith(AndroidJUnit4::class)
class FeedE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        E2e.shell("am force-stop $FIXTURE")
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val feedWall = E2e.textContains("Shorts are blocked")
    private val fixtureRules = Storage.FeedRules(updated = Instant.MAX,
        feeds = mapOf(FIXTURE to listOf(Storage.FeedRule(listOf("reel_watch_player"), 0.7f))))

    private fun blockFixtureFeed() {
        freshState()
        Feeds.useForTests(fixtureRules)
        Storage.setFeedBlocked(ctx, FIXTURE, true)
    }

    @Test
    fun shortsSwitch_explainsFirst_thenBlocks_andTurnsOffInOneTap() {
        freshState(mapOf(YOUTUBE to Rule(allowMin = 30, windowMin = 60)))
        openHome()

        waitFor(text("Block YouTube Shorts")).click()
        waitFor(text("Block YouTube Shorts?"))
        waitFor(text("Not now")).click()
        assertFalse(YOUTUBE in Storage.loadFeedBlocks(ctx))

        waitFor(text("Block YouTube Shorts")).click()
        waitFor(text("Block Shorts")).click()
        assertTrue(E2e.eventually { YOUTUBE in Storage.loadFeedBlocks(ctx) })
        assertTrue("switch not shown on", E2e.eventually {
            device.findObject(By.checkable(true))?.isChecked == true
        })

        waitFor(text("Block YouTube Shorts")).click()
        assertFalse("turning it off asked again", appears(text("Block Shorts"), timeout = 1_500))
        assertTrue(E2e.eventually { YOUTUBE !in Storage.loadFeedBlocks(ctx) })
    }

    @Test
    fun removingYouTube_forgetsItsShortsBlock() {
        freshState(mapOf(YOUTUBE to Rule(allowMin = 30, windowMin = 60)))
        Storage.setFeedBlocked(ctx, YOUTUBE, true)
        Storage.removeApp(ctx, YOUTUBE)
        assertEquals(emptySet<String>(), Storage.loadFeedBlocks(ctx))
    }

    @Test
    fun feedOnScreen_wallsTheApp_andGoBackLeavesIt() {
        blockFixtureFeed()
        openApp(FIXTURE)

        // The small player on the home screen is a shelf, not the feed.
        waitFor(text("Fixture home"))
        assertFalse("walled the home screen", appears(feedWall, timeout = 3_000))

        waitFor(text("Open feed")).click()
        assertTrue("feed not walled", appears(feedWall))

        waitFor(text("Go back")).click()
        assertTrue("wall stayed up", device.wait(Until.gone(feedWall), E2e.WAIT))
        assertTrue("didn't go back to the home screen", appears(text("Fixture home")))
    }

    @Test
    fun feedNotBlocked_noWall() {
        blockFixtureFeed()
        Storage.setFeedBlocked(ctx, FIXTURE, false)
        openApp(FIXTURE)

        waitFor(text("Open feed")).click()
        assertFalse("walled a feed that isn't blocked", appears(feedWall, timeout = 3_000))
    }
}
