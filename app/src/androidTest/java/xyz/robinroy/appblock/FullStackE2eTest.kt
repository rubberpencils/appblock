package xyz.robinroy.appblock

import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.robinroy.appblock.E2e.Backend
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.FIXTURE
import xyz.robinroy.appblock.E2e.YOUTUBE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.eventually
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.scrollTo
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor

/**
 * The app against a real server: the local one, with a database of its own,
 * that scripts/e2e.sh starts for the run (docs/local-dev.md). Nothing here
 * stands in for either side. What the app sends is read back from the
 * server's database and from the mail it wrote; the detection rules the
 * app uses are the ones it downloaded from that server.
 *
 * The other test classes cover each side's every case with the other side
 * played by the test (FeedbackE2eTest, FeedE2eTest, server/test). These
 * hold the join between the two.
 */
@RunWith(AndroidJUnit4::class)
class FullStackE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        Feedback.resetForTests()
        Feeds.useForTests(null)
        Feeds.useServerForTests(null, null)
        Feeds.forgetFetchedForTests(ctx)
        E2e.shell("am force-stop $FIXTURE")
        device.pressBack(); device.pressBack(); device.pressBack()
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    /** A port on the device that leads nowhere: the server out of reach. */
    private val nowhere = "http://localhost:8788"

    // ---- feedback ----

    private lateinit var backend: String

    /** A clean app that sends its feedback to the backend. */
    private fun appOnTheBackend() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        backend = Backend.need()
        Feedback.resetForTests()
        Feedback.apiBase = backend
    }

    private fun openForm() {
        openHome()
        waitFor(By.desc("About")).click()
        scrollTo(text("Help & feedback")).click()
        waitFor(By.clazz(EditText::class.java))
    }

    private fun boxes(): List<UiObject2> = device.findObjects(By.clazz(EditText::class.java))
    private fun send() = waitFor(text("Send")).parent.click()

    /** A message no other run or test has sent. */
    private fun unique(what: String) = "$what (${java.util.UUID.randomUUID()})"

    private fun stored(message: String) = Backend.rows("SELECT * FROM feedback WHERE message = ?", message)

    @Test
    fun feedbackSentFromTheForm_isInTheServersDatabase_andMailedToTheMaintainer() {
        appOnTheBackend()
        val mailsBefore = Backend.mails().map { it.id }.toSet()
        val message = unique("The wall didn't come up on Clock today.")
        openForm()
        boxes()[0].text = message
        boxes()[1].text = "tester@example.com"
        send()
        waitFor(text("Sent. Thank you."))

        // The server has it, as the app described itself.
        val row = stored(message).single()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        assertEquals("feedback", row["kind"])
        assertEquals("tester@example.com", row["contact"])
        assertEquals(info.versionName, row["app_version"])
        assertEquals(android.os.Build.VERSION.SDK_INT, (row["android_sdk"] as Number).toInt())
        assertTrue("${row["device"]}", (row["device"] as String).isNotBlank())
        assertEquals(Storage.senderId(ctx), row["sender_id"])
        assertEquals(java.time.Instant.ofEpochMilli(info.firstInstallTime).toString().take(19),
            (row["installed_at"] as String).take(19))
        val setup = JSONObject(row["diagnostics"] as String)
        assertEquals(true, setup.getBoolean("accessibility"))
        assertEquals(1, setup.getInt("limited_apps"))
        // The limited app is counted, never named, anywhere in what the server keeps.
        assertFalse(row.toString(), row.toString().contains(CLOCK))

        // The id the phone kept is the server's.
        assertEquals(row["id"], Storage.loadSentFeedback(ctx).single().id)

        // And the maintainer was mailed about it, answerable to the sender.
        assertEquals(1, (row["emailed"] as Number).toInt())
        val mail = Backend.mails().single { it.id !in mailsBefore }
        assertEquals(listOf("maintainer@example.com"), mail.to)
        assertTrue(mail.subject, mail.subject.startsWith("[Feedback] Moly · ${info.versionName} · "))
        assertEquals("tester@example.com", mail.replyTo)
    }

    @Test
    fun aSupportRequest_isStoredAsSupport_andItsMailSaysSoFirst() {
        appOnTheBackend()
        val mailsBefore = Backend.mails().map { it.id }.toSet()
        val message = unique("Strict mode won't turn off after the cooldown.")
        openForm()
        waitFor(text("Support")).click()
        boxes()[0].text = message
        boxes()[1].text = "stuck@example.com"
        send()
        waitFor(text("We'll write back to stuck@example.com."))

        val row = stored(message).single()
        assertEquals("support", row["kind"])
        assertEquals("stuck@example.com", row["contact"])
        val mail = Backend.mails().single { it.id !in mailsBefore }
        assertTrue(mail.subject, mail.subject.startsWith("[Support] Moly · "))
        assertEquals("stuck@example.com", mail.replyTo)
    }

    @Test
    fun withTheServerOutOfReach_nothingIsLost_andTryAgainSendsItExactlyOnce() {
        appOnTheBackend()
        Feedback.apiBase = nowhere
        val message = unique("Written while the server was down.")
        openForm()
        boxes()[0].text = message
        send()
        waitFor(textContains("Couldn't send it"))
        assertEquals(message, boxes()[0].text)
        assertEquals(emptyList<Map<String, Any?>>(), stored(message))
        assertEquals(emptyList<Storage.SentFeedback>(), Storage.loadSentFeedback(ctx))

        // The server is back.
        Feedback.apiBase = backend
        waitFor(text("Try again")).parent.click()
        waitFor(text("Sent. Thank you."))
        assertEquals(1, stored(message).size)
        assertEquals(stored(message).single()["id"], Storage.loadSentFeedback(ctx).single().id)
    }

    // ---- the Shorts and Reels detection rules ----
    //
    // The server serves rule files made for the run (server/scripts/
    // with-backend.ts), signed with the run's own key, for the tests'
    // stand-in for YouTube: "old" names a view its player no longer has,
    // "new" is the fix, "tampered" is the fix's signature on other rules.

    private val feedWall = textContains("Shorts are blocked")
    private val player = listOf("reel_watch_player")

    private fun ids() = Feeds.rules(ctx)?.feeds?.get(FIXTURE)?.flatMap { it.ids }

    private fun rulesAt(which: String) = "$backend/e2e/rules/$which/v1/rules.signed.json"

    /** A clean app whose rules come from the backend, and the fixture's Shorts blocked. */
    private fun rulesFromTheBackend(which: String) {
        freshState()
        backend = Backend.need()
        val key = Backend.get("/e2e/public-key.txt").second.trim()
        Feeds.useForTests(null, mapOf(FIXTURE to Storage.FEEDS.getValue(YOUTUBE)))
        Feeds.useServerForTests(rulesAt(which), key)
        Storage.setFeedBlocked(ctx, FIXTURE, true)
    }

    /** The server now has other rules at the address the app fetches, and the app's next fetch is due. */
    private fun serverNowServes(url: String) {
        val key = Backend.get("/e2e/public-key.txt").second.trim()
        Feeds.useServerForTests(url, key)
        Feeds.dueAgainForTests(ctx)
    }

    /** The fixture brought to the front afresh: the blocker looks at it, and fetches if a fetch is due. */
    private fun openFixture() {
        E2e.shell("am force-stop $FIXTURE")
        openApp(FIXTURE)
        waitFor(text("Fixture home"))
    }

    private fun openFixtureFeed() {
        openFixture()
        waitFor(text("Open feed")).click()
    }

    @Test
    fun aRuleFixPublishedOnTheServer_reachesThePhone_andTheWallFollowsIt() {
        // The app redesigned its player: the rules the phone gets don't spot it any more.
        rulesFromTheBackend("old")
        openFixture()
        assertTrue("the rules weren't downloaded", eventually { ids() == listOf("player_before_the_redesign") })
        waitFor(text("Open feed")).click()
        assertFalse("walled by rules that don't name the player", appears(feedWall, timeout = 3_000))

        // The fix is published. Nothing changes on the phone but what the server answers.
        serverNowServes(rulesAt("new"))
        openFixture()
        assertTrue("the fix wasn't downloaded", eventually { ids() == player })
        waitFor(text("Open feed")).click()
        assertTrue("the feed isn't walled under the fixed rules", appears(feedWall))
    }

    @Test
    fun rulesThatAreUnchanged_tamperedWith_older_unsigned_orOutOfReach_changeNothing() {
        rulesFromTheBackend("new")
        openFixture()
        assertTrue("the rules weren't downloaded", eventually { ids() == player })
        val have = Feeds.rules(ctx)!!.updated

        val cases = mapOf(
            "the same rules again" to rulesAt("new"),
            "rules someone changed under a real signature" to rulesAt("tampered"),
            "older rules" to rulesAt("old"),
            // Newer than what the phone has, and properly signed: by a key this app doesn't trust.
            "newer rules signed with someone else's key" to rulesAt("stranger"),
            // The real rules: the maintainer's key (not the run's), and older besides.
            "the real rules, under a key this run doesn't trust" to "$backend/v1/rules.signed.json",
            "a file that isn't there" to "$backend/e2e/rules/nope/v1/rules.signed.json",
            "a server out of reach" to "$nowhere/v1/rules.signed.json",
        )
        for ((what, url) in cases) {
            serverNowServes(url)
            openFixture()
            assertTrue("$what: no fetch was tried", eventually { Feeds.fetchedForTests(ctx) })
            assertEquals("$what replaced the rules", player, ids())
            assertEquals("$what replaced the rules", have, Feeds.rules(ctx)!!.updated)
        }
        // After all of it the feed is still walled.
        waitFor(text("Open feed")).click()
        assertTrue("the feed isn't walled", appears(feedWall))
    }

    @Test
    fun theRealRulesTheServerPublishes_areTheOnesTheMaintainerSigned() {
        // The app as shipped (the real key), fetching the real file from this server.
        freshState()
        backend = Backend.need()
        val (status, file) = Backend.get("/v1/rules.signed.json")
        assertEquals(200, status)
        assertNotNull("the server's rules aren't signed with the key the app trusts",
            Storage.openSignedRules(file))
        // And they are the ones in the APK, or newer: never something the app would refuse for age.
        val shipped = Feeds.rules(ctx)!!.updated
        assertFalse(Storage.openSignedRules(file)!!.updated < shipped)
    }
}
