package xyz.robinroy.appblock

import java.io.File
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback from the info sheet: what may be sent and how often, what goes
 * with it, and what the phone keeps of it afterwards.
 */
class FeedbackTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    // ---- the message ----

    @Test
    fun `a message is what was typed, without the space around it`() {
        assertEquals("It stopped blocking.", Storage.feedbackMessage("  It stopped blocking.\n\n"))
        assertEquals("one\n\ntwo", Storage.feedbackMessage("one\n\ntwo"))
    }

    @Test
    fun `nothing typed is nothing to send`() {
        for (typed in listOf("", " ", "\n\t  \n")) assertNull(typed, Storage.feedbackMessage(typed))
    }

    @Test
    fun `it can be very long, in any script, and stops at the limit`() {
        val long = "ദ".repeat(Storage.FEEDBACK_MAX_CHARS)
        assertEquals(long, Storage.feedbackMessage(long))
        assertEquals(Storage.FEEDBACK_MAX_CHARS, Storage.feedbackMessage(long + "more")!!.length)
        assertEquals(20_000, Storage.FEEDBACK_MAX_CHARS)
    }

    // ---- where to reply ----

    @Test
    fun `no address is fine`() {
        assertEquals(Storage.Contact(null, true), Storage.feedbackContact(""))
        assertEquals(Storage.Contact(null, true), Storage.feedbackContact("   "))
    }

    @Test
    fun `an address is kept, trimmed`() {
        assertEquals(Storage.Contact("me@example.com", true), Storage.feedbackContact(" me@example.com "))
        assertEquals(Storage.Contact("a.b+c@mail.example.co.in", true), Storage.feedbackContact("a.b+c@mail.example.co.in"))
    }

    @Test
    fun `what couldn't be an address is said so, not sent`() {
        for (typed in listOf("me", "me@", "@example.com", "me@example", "me @example.com", "a@b@c.com",
            "x".repeat(250) + "@example.com"))
            assertEquals(typed, Storage.Contact(null, false), Storage.feedbackContact(typed))
    }

    @Test
    fun `a support request needs an address, as it can only be answered by mail`() {
        val support = Storage.FeedbackKind.SUPPORT
        assertEquals(Storage.Contact(null, false), Storage.feedbackContact("", support))
        assertEquals(Storage.Contact(null, false), Storage.feedbackContact("  ", support))
        assertEquals(Storage.Contact(null, false), Storage.feedbackContact("me@", support))
        assertEquals(Storage.Contact("me@example.com", true), Storage.feedbackContact("me@example.com", support))
        // Feedback is fine without one.
        assertEquals(Storage.Contact(null, true), Storage.feedbackContact("", Storage.FeedbackKind.FEEDBACK))
    }

    // ---- ten in any 24 hours ----

    @Test
    fun `ten can be sent in a day`() {
        assertEquals(10, Storage.feedbackLeft(emptyList(), now))
        assertEquals(7, Storage.feedbackLeft(listOf(now - hour, now - 2 * hour, now - 3 * hour), now))
        assertEquals(0, Storage.feedbackLeft(List(10) { now - it * hour }, now))
        assertEquals(0, Storage.feedbackLeft(List(15) { now - it * 60_000L }, now))
    }

    @Test
    fun `a message from more than a day ago no longer counts`() {
        val sent = List(9) { now - it * hour } + (now - day) + (now - day - 1) + (now - 3 * day)
        assertEquals(1, Storage.feedbackLeft(sent, now))
        // A minute later the one from 23 hours 59 minutes ago still counts.
        assertEquals(0, Storage.feedbackLeft(List(9) { now - it * hour } + (now - day + 60_000), now))
    }

    @Test
    fun `a time from the future (the clock was moved back) doesn't count against anyone`() {
        assertEquals(10, Storage.feedbackLeft(List(10) { now + (it + 1) * hour }, now))
    }

    @Test
    fun `with none left, the wait is until the oldest of the ten is a day old`() {
        val sent = List(10) { now - (it + 1) * hour }   // 1 to 10 hours ago
        assertEquals(14 * hour, Storage.feedbackWaitMs(sent, now))
        // Twelve in the day: the wait is for the third oldest, which brings it back to nine.
        val twelve = List(12) { now - (it + 1) * hour }
        assertEquals(14 * hour, Storage.feedbackWaitMs(twelve, now))
        assertEquals(0L, Storage.feedbackWaitMs(sent.drop(1), now))
        assertEquals(0L, Storage.feedbackWaitMs(emptyList(), now))
    }

    @Test
    fun `only the last day's send times are kept`() {
        val sent = listOf(now - 3 * day, now - day - 1, now - 5 * hour, now - hour, now)
        assertEquals(listOf(now - 5 * hour, now - hour, now), Storage.feedbackTimesKept(sent, now))
        assertEquals(10, Storage.feedbackTimesKept(List(30) { now - it * 1000L }, now).size)
    }

    // ---- the past feedback the phone keeps ----

    private fun sent(n: Int, bytes: Int = 10) = Storage.SentFeedback("id-$n", now + n, "x".repeat(bytes))

    @Test
    fun `past feedback is kept newest first`() {
        val all = listOf(sent(2), sent(5), sent(1))
        assertEquals(listOf("id-5", "id-2", "id-1"), Storage.feedbackKept(all).map { it.id })
    }

    @Test
    fun `past the size kept, the oldest go`() {
        val all = (1..6).map { sent(it, bytes = 100) }
        assertEquals(listOf("id-6", "id-5", "id-4"), Storage.feedbackKept(all, maxBytes = 300).map { it.id })
        assertEquals(listOf("id-6", "id-5"), Storage.feedbackKept(all, maxBytes = 299).map { it.id })
    }

    @Test
    fun `the size is the messages' bytes, not their characters`() {
        // Three bytes a character in UTF-8.
        val all = listOf(Storage.SentFeedback("a", 2, "ദദദ"), Storage.SentFeedback("b", 1, "ദ"))
        assertEquals(listOf("a", "b"), Storage.feedbackKept(all, maxBytes = 12).map { it.id })
        assertEquals(listOf("a"), Storage.feedbackKept(all, maxBytes = 11).map { it.id })
    }

    @Test
    fun `the newest is kept whatever its size`() {
        val all = listOf(sent(1), sent(2, bytes = 1000))
        assertEquals(listOf("id-2"), Storage.feedbackKept(all, maxBytes = 50).map { it.id })
    }

    @Test
    fun `about 5 MB is kept`() {
        assertEquals(5L * 1024 * 1024, Storage.FEEDBACK_KEEP_BYTES)
        // A thousand of the longest messages a person would type don't all fit; the newest do.
        val many = (1..400).map { sent(it, bytes = 20_000) }
        val kept = Storage.feedbackKept(many)
        assertEquals(262, kept.size)
        assertEquals("id-400", kept.first().id)
    }

    @Test
    fun `past feedback survives being stored`() {
        val all = listOf(
            Storage.SentFeedback("d253906d", now, "Line one\n\"quoted\" 🙂 ദ", Storage.FeedbackKind.SUPPORT, "me@example.com"),
            sent(2))
        assertEquals(all, Storage.sentFeedbackFromJson(Storage.sentFeedbackToJson(all)))
        assertEquals(emptyList<Storage.SentFeedback>(), Storage.sentFeedbackFromJson(null))
        assertEquals(emptyList<Storage.SentFeedback>(), Storage.sentFeedbackFromJson("not json"))
    }

    @Test
    fun `a past message remembers its kind and where a reply was asked for`() {
        val back = Storage.sentFeedbackFromJson(Storage.sentFeedbackToJson(listOf(
            Storage.SentFeedback("a", 2, "Help", Storage.FeedbackKind.SUPPORT, "me@example.com"),
            Storage.SentFeedback("b", 1, "Nice app"))))
        assertEquals(Storage.FeedbackKind.SUPPORT, back[0].kind)
        assertEquals("me@example.com", back[0].contact)
        assertEquals(Storage.FeedbackKind.FEEDBACK, back[1].kind)
        assertNull(back[1].contact)
    }

    // ---- since when Moly has been installed ----

    @Test
    fun `the first start keeps what Android says`() {
        val android = Storage.InstallTimes(first = 100, updated = 200)
        assertEquals(android, Storage.installTimes(null, android))
    }

    @Test
    fun `an update moves the update time, not the first install`() {
        val kept = Storage.InstallTimes(first = 100, updated = 200)
        assertEquals(Storage.InstallTimes(100, 300), Storage.installTimes(kept, Storage.InstallTimes(100, 300)))
    }

    @Test
    fun `on a new phone the first install is the one the backup remembers`() {
        val fromBackup = Storage.InstallTimes(first = 100, updated = 200)
        val newPhone = Storage.InstallTimes(first = 5000, updated = 5000)
        assertEquals(Storage.InstallTimes(100, 5000), Storage.installTimes(fromBackup, newPhone))
    }

    @Test
    fun `an older update time on the phone doesn't move the kept one back`() {
        val kept = Storage.InstallTimes(first = 100, updated = 900)
        assertEquals(kept, Storage.installTimes(kept, Storage.InstallTimes(50, 400)))
    }

    // ---- what is sent ----

    private val about = Storage.FeedbackAbout(
        appVersion = "2.7.0", appBuild = 20, androidVersion = "14", androidSdk = 34,
        device = "OnePlus CPH2401", install = Storage.InstallTimes(first = 1_700_000_000_000, updated = 1_790_000_000_000),
        diagnostics = linkedMapOf("accessibility" to true, "usage_access" to false, "limited_apps" to 4,
            "strict" to "off"))

    @Test
    fun `the body has the names the server reads`() {
        val body = JSONObject(Storage.feedbackBody(Storage.FeedbackKind.FEEDBACK, "It stopped blocking.",
            "me@example.com", "3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c", about))
        assertEquals("feedback", body.getString("kind"))
        assertEquals(setOf("kind", "message", "contact", "senderId", "appVersion", "appBuild", "androidVersion",
            "androidSdk", "device", "installedAt", "updatedAt", "diagnostics"), body.keySet())
        assertEquals("It stopped blocking.", body.getString("message"))
        assertEquals("me@example.com", body.getString("contact"))
        assertEquals("3f9a2b6c-1d4e-4f5a-8b7c-9d0e1f2a3b4c", body.getString("senderId"))
        assertEquals("2.7.0", body.getString("appVersion"))
        assertEquals(20, body.getInt("appBuild"))
        assertEquals("14", body.getString("androidVersion"))
        assertEquals(34, body.getInt("androidSdk"))
        assertEquals("OnePlus CPH2401", body.getString("device"))
        assertEquals(1_700_000_000_000, body.getLong("installedAt"))
        assertEquals(1_790_000_000_000, body.getLong("updatedAt"))
        val setup = body.getJSONObject("diagnostics")
        assertEquals(true, setup.getBoolean("accessibility"))
        assertEquals(false, setup.getBoolean("usage_access"))
        assertEquals(4, setup.getInt("limited_apps"))
        assertEquals("off", setup.getString("strict"))
    }

    @Test
    fun `the server's own checks read the same names`() {
        // server/src/feedback.ts is what takes this body apart.
        val server = File("../server/src/feedback.ts").readText()
        val body = JSONObject(Storage.feedbackBody(Storage.FeedbackKind.SUPPORT, "m", "me@example.com", "id", about))
        for (name in body.keySet()) assertTrue("the server doesn't read \"$name\"", "input.$name" in server)
    }

    @Test
    fun `the kind is said in the server's words`() {
        // server/src/feedback.ts takes exactly these two.
        val server = File("../server/src/feedback.ts").readText()
        for (kind in Storage.FeedbackKind.entries) {
            assertEquals(kind.wire, JSONObject(Storage.feedbackBody(kind, "m", "me@example.com", "id", about)).getString("kind"))
            assertTrue("the server doesn't know \"${kind.wire}\"", "\"${kind.wire}\"" in server)
        }
        assertEquals(listOf("Feedback", "Support"), Storage.FeedbackKind.entries.map { it.label })
    }

    @Test
    fun `with no reply address there is no contact at all`() {
        assertFalse(JSONObject(Storage.feedbackBody(Storage.FeedbackKind.FEEDBACK, "m", null, "id", about)).has("contact"))
    }

    @Test
    fun `nothing in the body names an app the user limits`() {
        // What Feedback.about reports is counts and switches; the body is only that and the message.
        val body = Storage.feedbackBody(Storage.FeedbackKind.FEEDBACK, "m", null, "id", about)
        assertFalse(body.contains("com."))
    }

    @Test
    fun `the phone is its maker and model, the maker not said twice`() {
        assertEquals("OnePlus CPH2401", Storage.deviceName("OnePlus", "CPH2401"))
        assertEquals("Samsung SM-S911B", Storage.deviceName("samsung", "SM-S911B"))
        assertEquals("Google Pixel 7", Storage.deviceName("Google", "Google Pixel 7"))
        assertEquals("Pixel 7", Storage.deviceName("", "Pixel 7"))
    }

    // ---- the server's answer ----

    @Test
    fun `a 201 with an id is sent`() {
        assertEquals("d253906d", Storage.feedbackId(201, """{"id":"d253906d","receivedAt":"2026-11-11T12:00:00Z"}"""))
    }

    @Test
    fun `anything else isn't`() {
        assertNull(Storage.feedbackId(200, """{"id":"d253906d"}"""))
        assertNull(Storage.feedbackId(400, """{"error":"invalid","field":"message"}"""))
        assertNull(Storage.feedbackId(500, null))
        assertNull(Storage.feedbackId(201, "<html>a captive portal's page</html>"))
        assertNull(Storage.feedbackId(201, """{"id":""}"""))
        assertNull(Storage.feedbackId(0, null))
    }

    // ---- where it goes, and what is backed up ----

    @Test
    fun `feedback goes to the server the rules come from, over HTTPS`() {
        // But for the "local" build (scripts/local.sh), which is never
        // released: it talks to the developer's own machine, and nowhere else.
        if (BuildConfig.BUILD_TYPE == "local") assertEquals("http://localhost:8787", BuildConfig.API_BASE)
        else assertEquals("https://${Storage.RULES_HOST}", BuildConfig.API_BASE)
    }

    @Test
    fun `plain HTTP is for a developer's own machine, and never in a release`() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2")) {
            assertTrue(Storage.plainHttpAllowed("http", host, "debug"))
            assertTrue(Storage.plainHttpAllowed("http", host, "local"))
            assertFalse("a release talking HTTP to $host", Storage.plainHttpAllowed("http", host, "release"))
        }
        for (host in listOf("trymoly.com", "192.168.1.5", "localhost.example.com", "evil.localhost", ""))
            for (build in listOf("debug", "local", "release"))
                assertFalse("$build talking HTTP to $host", Storage.plainHttpAllowed("http", host, build))
        // HTTPS isn't "plain HTTP allowed": it takes the other, checked, path.
        assertFalse(Storage.plainHttpAllowed("https", "localhost", "debug"))
    }

    @Test
    fun `only builds that are never released may talk plain HTTP, and only to this machine`() {
        val app = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val ns = "http://schemas.android.com/apk/res/android"
        // Every build: no plain HTTP, and no network config that could say otherwise.
        val main = app.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("false", main.getAttributeNS(ns, "usesCleartextTraffic"))
        assertEquals("", main.getAttributeNS(ns, "networkSecurityConfig"))
        // The exception (the local build's, lent to the debug build) names localhost and nothing wider.
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("""sourceSets["debug"].manifest.srcFile("src/local/AndroidManifest.xml")"""))
        assertFalse("the release build was given the exception", Regex("""sourceSets\["(main|release)"\][^\n]*src/local""").containsMatchIn(gradle))
        val config = app.newDocumentBuilder().parse(File("src/local/res/xml/local_network.xml")).documentElement
        val base = config.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domains = config.getElementsByTagName("domain")
        val allowed = (0 until domains.length).map { domains.item(it) as org.w3c.dom.Element }
        assertEquals(setOf("localhost", "127.0.0.1", "10.0.2.2"), allowed.map { it.textContent }.toSet())
        assertTrue(allowed.all { it.getAttribute("includeSubdomains") == "false" })
    }

    @Test
    fun `the past feedback and who is writing go into the backup`() {
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val text = File(rules).readText()
            for (prefs in listOf(Storage.FEEDBACK_PREFS, Storage.IDENTITY_PREFS))
                assertFalse("$rules leaves out $prefs", text.contains("\"$prefs.xml\""))
        }
    }

    @Test
    fun `a sent time reads as a date and a time`() {
        val utc = ZoneId.of("UTC")
        assertEquals("15 Jan 2027, 8:00 am", FeedbackPages.sentWhen(now, utc))
        assertEquals("15 Jan 2027, 1:30 pm", FeedbackPages.sentWhen(now, ZoneId.of("Asia/Kolkata")))
    }
}
