package xyz.robinroy.appblock

import android.content.Context
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.eventually
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.scrollTo
import xyz.robinroy.appblock.E2e.shot
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor

/**
 * "Help & feedback" on the info sheet: the form, its two kinds of message,
 * what it sends, and every way a send ends. There is no server here: each test says what the server
 * answers (Feedback.post), and reads what the app sent it. The server's
 * side is server/test.
 */
@RunWith(AndroidJUnit4::class)
class FeedbackE2eTest {

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        held?.countDown()
        device.pressBack(); device.pressBack(); device.pressBack()
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    /** The bodies the app posted, in order. */
    private val posted = mutableListOf<JSONObject>()

    /** A send held back until this is let go, so "Sending…" can be seen. */
    private var held: CountDownLatch? = null

    /** The answer the real server gives a message it took: its id, back. */
    private fun taken(body: String) =
        201 to """{"id":"${JSONObject(body).getString("id")}","receivedAt":"2026-11-11T12:00:00.000Z"}"""

    /** The server takes whatever is sent. */
    private fun serverTakesIt() {
        Feedback.post = { body ->
            held?.await(20, TimeUnit.SECONDS)
            synchronized(posted) { posted += JSONObject(body) }
            taken(body)
        }
    }

    private fun openForm() {
        // A rule, so home is the list and not its empty state.
        openHome()
        waitFor(By.desc("About")).click()
        scrollTo(text("Help & feedback")).click()
        waitFor(By.clazz(EditText::class.java))
    }

    private fun boxes(): List<UiObject2> = device.findObjects(By.clazz(EditText::class.java))
    private fun messageBox() = boxes()[0]
    private fun contactBox() = boxes()[1]
    private fun sendButton() = waitFor(text("Send")).parent

    private fun past() = Storage.loadSentFeedback(ctx)

    private fun sentToday(times: Int) {
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences(Storage.FEEDBACK_PREFS, Context.MODE_PRIVATE).edit()
            .putString("times", JSONArray(List(times) { now - (it + 1) * 60_000L }).toString()).commit()
    }

    @Test
    fun aMessage_isSentWithWhatMolyKnowsAboutItself_thanked_andKeptInPastFeedback() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        held = CountDownLatch(1)
        openForm()

        // Nothing to send yet, and no past feedback to open.
        assertFalse("Send is on with nothing written", sendButton().isEnabled)
        assertEquals(null, device.findObject(textContains("Your past messages")))
        shot("1-form-empty")

        val message = "The wall didn't come up on Clock today.\nIt did yesterday."
        messageBox().text = message
        contactBox().text = "tester@example.com"
        assertTrue("Send is off with a message written", eventually { sendButton().isEnabled })
        shot("2-form-filled")

        sendButton().click()
        // While it goes: said so, and nothing can be changed or sent twice.
        val sending = waitFor(text("Sending…"))
        assertFalse(sending.parent.isEnabled)
        assertFalse(messageBox().isEnabled)
        shot("3-sending")
        held!!.countDown()

        waitFor(text("Sent. Thank you."))
        shot("4-sent")

        // What went: the message, the address, and Moly's report on itself.
        val body = posted.single()
        assertEquals("feedback", body.getString("kind"))
        assertEquals(message, body.getString("message"))
        assertEquals("tester@example.com", body.getString("contact"))
        assertTrue(body.getString("senderId"), Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(body.getString("senderId")))
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        assertEquals(info.versionName, body.getString("appVersion"))
        assertEquals(android.os.Build.VERSION.SDK_INT, body.getInt("androidSdk"))
        assertTrue(body.getString("device").isNotBlank())
        assertEquals(info.firstInstallTime, body.getLong("installedAt"))
        val setup = body.getJSONObject("diagnostics")
        assertEquals(true, setup.getBoolean("accessibility"))
        assertEquals(1, setup.getInt("limited_apps"))
        assertEquals("off", setup.getString("strict"))
        // The app that is limited is counted, never named.
        assertFalse(body.toString(), body.toString().contains(CLOCK))

        // Each message has an id of its own, made on the phone.
        assertTrue(body.getString("id"), Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(body.getString("id")))
        assertFalse(body.getString("id") == body.getString("senderId"))

        // Kept on the phone, and there to read again.
        assertEquals(listOf(body.getString("id") to message), past().map { it.id to it.message })
        waitFor(text("Your past messages (1)")).click()
        waitFor(text("Your past messages"))
        assertTrue(appears(text(message)))
        // With where a reply was asked for.
        assertTrue(appears(text("Reply to tester@example.com")))
        shot("7-past-messages")

        // Back to the thanks, and Done leaves the form for About.
        device.pressBack()
        waitFor(text("Done")).click()
        assertTrue(appears(text("Permissions")))
    }

    @Test
    fun aSupportRequest_needsAnAddressToAnswer_andIsSentAsSupport() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        // It opens as feedback; Support is a tap away, and asks for what it needs.
        assertTrue(waitFor(text("Feedback")).isSelected)
        waitFor(text("Support")).click()
        assertTrue(eventually { waitFor(text("Support")).isSelected && !waitFor(text("Feedback")).isSelected })
        waitFor(By.clazz(EditText::class.java).textContains("What's going wrong?"))
        waitFor(By.clazz(EditText::class.java).textContains("Your email, so we can write back"))
        shot("8-support")

        val message = "Strict mode won't turn off after the cooldown."
        messageBox().text = message
        sendButton().click()
        waitFor(text("Add your email so we can write back."))
        shot("8b-support-needs-email")
        assertEquals(0, posted.size)

        // Half an address is no better.
        contactBox().text = "tester@"
        sendButton().click()
        waitFor(text("That email address doesn't look right."))
        assertEquals(0, posted.size)

        contactBox().text = "tester@example.com"
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        waitFor(text("We'll write back to tester@example.com."))
        shot("8c-support-sent")
        val body = posted.single()
        assertEquals("support", body.getString("kind"))
        assertEquals(message, body.getString("message"))
        assertEquals("tester@example.com", body.getString("contact"))

        // Kept as what it was.
        val kept = past().single()
        assertEquals(Storage.FeedbackKind.SUPPORT, kept.kind)
        assertEquals("tester@example.com", kept.contact)
        waitFor(text("Your past messages (1)")).click()
        assertTrue(appears(textContains("Support")))
        assertTrue(appears(text("Reply to tester@example.com")))
    }

    @Test
    fun switchingKind_keepsWhatWasWritten() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        messageBox().text = "Written before choosing."
        contactBox().text = "tester@example.com"
        waitFor(text("Support")).click()
        assertEquals("Written before choosing.", messageBox().text)
        assertEquals("tester@example.com", contactBox().text)
        waitFor(text("Feedback")).click()
        assertEquals("Written before choosing.", messageBox().text)
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        assertEquals("feedback", posted.single().getString("kind"))
    }

    @Test
    fun aLongPastMessage_isCutToAFewLines_andATapShowsTheRest() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        val long = (1..40).joinToString("\n") { "Line $it of a very long message." }
        val now = System.currentTimeMillis()
        Storage.addSentFeedback(ctx, Storage.SentFeedback("long", now - 60_000, long))
        Storage.addSentFeedback(ctx, Storage.SentFeedback("short", now, "A short one."))
        openForm()
        waitFor(text("Your past messages (2)")).click()
        waitFor(text("Your past messages"))

        // Both on one screen: the long one takes a few lines, not forty.
        val short = waitFor(text("A short one.")).visibleBounds
        val cut = waitFor(By.textStartsWith("Line 1 of a very long message.")).visibleBounds
        // (A line among others is taller than one alone, by the space between lines.)
        assertTrue("the long message ($cut) isn't cut to a few lines of the short one's height (${short.height()})",
            cut.height() > short.height() && cut.height() < short.height() * (FeedbackPages.PAST_LINES + 2))
        // No reply was asked for with these: no such line.
        assertEquals(null, device.findObject(textContains("Reply to")))
        shot("7b-past-long-cut")

        // A tap shows all of it; another folds it away again.
        waitFor(By.textStartsWith("Line 1 of a very long message.")).click()
        assertTrue(eventually {
            waitFor(By.textStartsWith("Line 1 of a very long message.")).visibleBounds.height() > cut.height() * 3
        })
        shot("7c-past-long-open")
        waitFor(By.textStartsWith("Line 1 of a very long message.")).click()
        assertTrue(eventually {
            waitFor(By.textStartsWith("Line 1 of a very long message.")).visibleBounds.height() == cut.height()
        })
    }

    @Test
    fun theSameSender_isTheSameIdEveryTime() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        messageBox().text = "One."
        sendButton().click()
        waitFor(text("Done")).click()
        scrollTo(text("Help & feedback")).click()
        waitFor(By.clazz(EditText::class.java))
        // A new form: empty, with the first message waiting under it.
        assertEquals("", messageBox().text.orEmpty().replace("Your message", ""))
        assertNotNull(device.findObject(text("Your past messages (1)")))
        messageBox().text = "Two."
        sendButton().click()
        waitFor(text("Sent. Thank you."))

        assertEquals(2, posted.size)
        assertEquals(posted[0].getString("senderId"), posted[1].getString("senderId"))
        // Two messages, two ids.
        assertFalse(posted[0].getString("id") == posted[1].getString("id"))
        // No address given: none sent.
        assertFalse(posted[1].has("contact"))
        assertEquals(listOf("Two.", "One."), past().sortedByDescending { it.sentAt }.map { it.message })
    }

    @Test
    fun whenItCantBeSent_whatWasWrittenStays_andTryingAgainSendsIt() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        var online = false
        val tried = mutableListOf<JSONObject>()
        Feedback.post = { body ->
            synchronized(tried) { tried += JSONObject(body) }
            if (!online) throw java.io.IOException("no network")
            synchronized(posted) { posted += JSONObject(body) }
            taken(body)
        }
        openForm()
        val message = "Written on a train, in a tunnel."
        messageBox().text = message
        sendButton().click()

        waitFor(textContains("Couldn't send it"))
        assertEquals(message, messageBox().text)
        assertTrue(messageBox().isEnabled)
        assertNotNull(device.findObject(text("Or email support@trymoly.com")))
        // Not sent means not kept, and not counted towards the day's ten.
        assertEquals(emptyList<Storage.SentFeedback>(), past())
        assertEquals(emptyList<Long>(), Storage.loadFeedbackTimes(ctx))
        shot("5-couldnt-send")

        online = true
        waitFor(text("Try again")).parent.click()
        waitFor(text("Sent. Thank you."))
        assertEquals(message, posted.single().getString("message"))
        assertEquals(listOf(message), past().map { it.message })
        // The second try was the same message, by its id: a server that got
        // the first after all would not keep two.
        assertEquals(2, tried.size)
        assertEquals(tried[0].getString("id"), tried[1].getString("id"))
    }

    @Test
    fun aServerHavingABadMoment_isAFailedSendToo() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        Feedback.post = { _ -> 500 to "<html>Something went wrong</html>" }
        openForm()
        messageBox().text = "Hello?"
        sendButton().click()
        waitFor(textContains("Check your connection and try again"))
        assertEquals(emptyList<Storage.SentFeedback>(), past())
    }

    @Test
    fun aServerThatSaysNo_sendsYouToEmail_notToYourConnection() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        Feedback.post = { _ -> 400 to """{"error":"invalid","field":"message"}""" }
        openForm()
        messageBox().text = "Something the server won't take."
        sendButton().click()
        // Trying again wouldn't help, and saying "check your connection" would be a lie.
        waitFor(textContains("Couldn't send it from here. Email it to support@trymoly.com instead"))
        assertEquals(null, device.findObject(textContains("Check your connection")))
        assertNotNull(device.findObject(text("Or email support@trymoly.com")))
        assertEquals("Something the server won't take.", messageBox().text)
        assertEquals(emptyList<Storage.SentFeedback>(), past())
        shot("5c-server-said-no")
    }

    @Test
    fun whatIsBeingWritten_isStillThere_afterTheSheetClosesAndTheScreenTurns() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        waitFor(text("Support")).click()
        messageBox().text = "Half a thought"
        contactBox().text = "tester@example.com"

        // Closed (a swipe too far, the back button twice), and opened again.
        device.pressBack(); device.pressBack()
        assertTrue(device.wait(Until.gone(By.clazz(EditText::class.java)), E2e.WAIT))
        openForm()
        assertEquals("Half a thought", messageBox().text)
        assertEquals("tester@example.com", contactBox().text)
        assertTrue(waitFor(text("Support")).isSelected)

        // The phone turned: the screen is built again, and the sheet with it goes.
        try {
            device.setOrientationLandscape()
            device.setOrientationNatural()
            device.waitForIdle()
            if (device.findObject(By.clazz(EditText::class.java)) == null) openForm()
            assertEquals("Half a thought", messageBox().text)
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }

        // Sent, it is gone from the form: the next message starts empty.
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        waitFor(text("Done")).click()
        scrollTo(text("Help & feedback")).click()
        waitFor(By.clazz(EditText::class.java))
        assertEquals("", messageBox().text.orEmpty().replace("Your message", ""))
        assertTrue(waitFor(text("Feedback")).isSelected)
    }

    @Test
    fun anAddressThatCouldntBeOne_isSaidSo_andNothingIsSent() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        messageBox().text = "Please write back."
        contactBox().text = "tester@"
        sendButton().click()

        waitFor(textContains("That email address doesn't look right"))
        shot("5b-address-not-right")
        assertEquals(0, posted.size)
        assertEquals("Please write back.", messageBox().text)

        // Emptied, it goes.
        contactBox().text = ""
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        assertFalse(posted.single().has("contact"))
    }

    @Test
    fun afterTenInADay_theFormSendsYouToEmail() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        // Nine already today: the tenth goes.
        sentToday(9)
        openForm()
        messageBox().text = "The tenth today."
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        waitFor(text("Done")).click()

        // The eleventh has nowhere to be written: email instead, and when the form is back.
        scrollTo(text("Help & feedback")).click()
        waitFor(textContains("You've sent 10 messages in the last 24 hours"))
        assertNotNull(device.findObject(text("Email support@trymoly.com")))
        assertTrue(appears(textContains("You can send from here again in 23 hours")))
        assertEquals(emptyList<UiObject2>(), boxes())
        // What was sent is still there to read.
        assertNotNull(device.findObject(text("Your past messages (1)")))
        shot("6-ten-today")
        assertEquals(1, posted.size)
    }

    @Test
    fun aMessageLongerThanAnyoneWrites_stopsAtTheLimit_withoutAWord() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        messageBox().text = "a".repeat(Storage.FEEDBACK_MAX_CHARS + 500)
        assertTrue(eventually { messageBox().text.length == Storage.FEEDBACK_MAX_CHARS })
        // No counter, no warning: nothing on the page speaks of a length.
        for (word in listOf("character", "too long", "maximum", "20,000", "20000"))
            assertEquals(word, null, device.findObject(textContains(word)))
        sendButton().click()
        waitFor(text("Sent. Thank you."))
        assertEquals(Storage.FEEDBACK_MAX_CHARS, posted.single().getString("message").length)
    }

    @Test
    fun closingTheSheetWhileItSends_stillKeepsTheMessageOnceSent() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        held = CountDownLatch(1)
        openForm()
        messageBox().text = "Sent, then closed."
        sendButton().click()
        waitFor(text("Sending…"))
        device.pressBack(); device.pressBack()
        assertTrue(device.wait(Until.gone(text("Sending…")), E2e.WAIT))
        held!!.countDown()
        assertTrue("the message wasn't kept", eventually { past().map { it.message } == listOf("Sent, then closed.") })
    }
}
