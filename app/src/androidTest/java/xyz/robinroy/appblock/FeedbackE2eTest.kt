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
 * Feedback from the info sheet: the form, what it sends, and every way a
 * send ends. There is no server here: each test says what the server
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

    /** The server takes whatever is sent, giving each message the next id. */
    private fun serverTakesIt() {
        Feedback.post = { body ->
            held?.await(20, TimeUnit.SECONDS)
            synchronized(posted) { posted += JSONObject(body) }
            201 to """{"id":"feedback-${posted.size}","receivedAt":"2026-11-11T12:00:00.000Z"}"""
        }
    }

    private fun openForm() {
        // A rule, so home is the list and not its empty state.
        openHome()
        waitFor(By.desc("About")).click()
        scrollTo(text("Send feedback")).click()
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
        assertEquals(null, device.findObject(textContains("Your past feedback")))
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

        // Kept on the phone, and there to read again.
        assertEquals(listOf("feedback-1" to message), past().map { it.id to it.message })
        waitFor(text("Your past feedback (1)")).click()
        waitFor(text("Your past feedback"))
        assertTrue(appears(text(message)))
        shot("7-past-feedback")

        // Back to the thanks, and Done leaves the form for About.
        device.pressBack()
        waitFor(text("Done")).click()
        assertTrue(appears(text("Permissions")))
    }

    @Test
    fun theSameSender_isTheSameIdEveryTime() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        serverTakesIt()
        openForm()
        messageBox().text = "One."
        sendButton().click()
        waitFor(text("Done")).click()
        scrollTo(text("Send feedback")).click()
        waitFor(By.clazz(EditText::class.java))
        // A new form: empty, with the first message waiting under it.
        assertEquals("", messageBox().text.orEmpty().replace("Your message", ""))
        assertNotNull(device.findObject(text("Your past feedback (1)")))
        messageBox().text = "Two."
        sendButton().click()
        waitFor(text("Sent. Thank you."))

        assertEquals(2, posted.size)
        assertEquals(posted[0].getString("senderId"), posted[1].getString("senderId"))
        // No address given: none sent.
        assertFalse(posted[1].has("contact"))
        assertEquals(listOf("Two.", "One."), past().sortedByDescending { it.sentAt }.map { it.message })
    }

    @Test
    fun whenItCantBeSent_whatWasWrittenStays_andTryingAgainSendsIt() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        var online = false
        Feedback.post = { body ->
            if (!online) throw java.io.IOException("no network")
            synchronized(posted) { posted += JSONObject(body) }
            201 to """{"id":"feedback-1"}"""
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
    }

    @Test
    fun aServerThatRefuses_isAFailedSendToo() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        Feedback.post = { _ -> 500 to "<html>Something went wrong</html>" }
        openForm()
        messageBox().text = "Hello?"
        sendButton().click()
        waitFor(textContains("Couldn't send it"))
        assertEquals(emptyList<Storage.SentFeedback>(), past())
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
        scrollTo(text("Send feedback")).click()
        waitFor(textContains("You've sent 10 messages in the last 24 hours"))
        assertNotNull(device.findObject(text("Email support@trymoly.com")))
        assertTrue(appears(textContains("You can send from here again in 23 hours")))
        assertEquals(emptyList<UiObject2>(), boxes())
        // What was sent is still there to read.
        assertNotNull(device.findObject(text("Your past feedback (1)")))
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
