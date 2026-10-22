package xyz.robinroy.appblock

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Blocking a feed (YouTube's Shorts): reading and trusting the rules file,
 * and deciding from the views found whether the feed is on screen.
 * (Unit tests run with the module dir, app/, as cwd.)
 */
class FeedsTest {

    private val yt = "com.google.android.youtube"
    private val window = Storage.Bounds(0, 0, 1080, 2400)

    private fun rules(updated: String = "2026-10-22T17:30:00Z",
                      feeds: String = """{"$yt": [{"ids": ["reel_watch_player"], "minCover": 0.7}]}""") =
        """{"updated": "$updated", "feeds": $feeds}"""

    // A throwaway key pair, standing in for the maintainer's.
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(json: String) = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(json.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    private fun envelope(json: String, sig: String = sign(json)) =
        JSONObject().put("rules", json).put("sig", sig).toString()

    // ---- the rules file ----

    @Test
    fun `rules file - parsed`() {
        val r = Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00Z"))!!
        assertEquals(Instant.parse("2026-10-22T17:30:00Z"), r.updated)
        assertEquals(listOf(Storage.FeedRule(listOf("reel_watch_player"), 0.7f)), r.feeds[yt])
    }

    @Test
    fun `rules file - malformed is ignored`() {
        assertNull(Storage.parseFeedRules("not json"))
        assertNull("no feeds", Storage.parseFeedRules("""{"updated": "2026-10-22T17:30:00Z"}"""))
        assertNull("no updated", Storage.parseFeedRules("""{"feeds": {}}"""))
    }

    @Test
    fun `rules file - updated is UTC to the second, nothing else`() {
        assertNull("not a time", Storage.parseFeedRules(rules(updated = "yesterday")))
        assertNull("no time of day", Storage.parseFeedRules(rules(updated = "2026-10-22")))
        assertNull("not UTC", Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00+05:30")))
        assertNull("no zone", Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00")))
        assertNull("fractions", Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00.5Z")))
        assertNull("no such day", Storage.parseFeedRules(rules(updated = "2026-02-30T17:30:00Z")))
        assertNull("no such hour", Storage.parseFeedRules(rules(updated = "2026-10-22T25:00:00Z")))
    }

    @Test
    fun `rules for a version - ranges limit them, no range is every version`() {
        val r = Storage.parseFeedRules(rules(feeds = """{"$yt": [
            {"ids": ["old"], "minCover": 0.7, "maxVersion": 100},
            {"ids": ["new"], "minCover": 0.7, "minVersion": 101},
            {"ids": ["any"], "minCover": 0.7}]}"""))!!
        assertEquals(listOf("old", "any"), Storage.feedRulesFor(r, yt, 100).flatMap { it.ids })
        assertEquals(listOf("new", "any"), Storage.feedRulesFor(r, yt, 101).flatMap { it.ids })
        assertEquals(emptyList<Storage.FeedRule>(), Storage.feedRulesFor(r, "com.other", 1))
    }

    @Test
    fun `signature - checks out only for the signed text and the right key`() {
        val json = rules()
        assertTrue(Storage.rulesSigned(json, sign(json), publicKey))
        assertFalse("tampered", Storage.rulesSigned(json.replace("0.7", "0.1"), sign(json), publicKey))
        assertFalse("other key", Storage.rulesSigned(json, sign(json)))
        assertFalse("garbage", Storage.rulesSigned(json, "nope", publicKey))
    }

    @Test
    fun `signed file - opened only when the signature checks out`() {
        val json = rules(updated = "2026-10-22T17:30:00Z")
        assertEquals(Instant.parse("2026-10-22T17:30:00Z"), Storage.openSignedRules(envelope(json), publicKey)?.updated)
        assertNull("another file's signature",
            Storage.openSignedRules(envelope(json, sign(rules(updated = "2026-10-23T17:30:00Z"))), publicKey))
        assertNull(Storage.openSignedRules("{}", publicKey))
    }

    @Test
    fun `newest rules win - a fetch never rolls back the bundled ones`() {
        val older = Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00Z"))
        val newer = Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:01Z"))
        assertSame(newer, Storage.newerRules(older, newer))
        assertSame(newer, Storage.newerRules(newer, older))
        assertSame(older, Storage.newerRules(older, null))
        assertSame(older, Storage.newerRules(null, older))
        assertSame("a year on is newer, not just a bigger month", newer,
            Storage.newerRules(Storage.parseFeedRules(rules(updated = "2025-12-31T23:59:59Z")), newer))
        assertSame("the same time keeps what's there", older,
            Storage.newerRules(older, Storage.parseFeedRules(rules(updated = "2026-10-22T17:30:00Z"))))
    }

    @Test
    fun `the shipped rules are signed by the real key and match rules json`() {
        // Catches an edit to rules/v1/rules.json without ./gradlew signRules.
        val dir = "../rules/${Storage.RULES_FORMAT}"
        val shipped = File("$dir/rules.signed.json").readText()
        val opened = Storage.openSignedRules(shipped)
        assertNotNull("rules.signed.json isn't signed by the rules key", opened)
        assertEquals("rules.signed.json is out of date: run ./gradlew signRules",
            File("$dir/rules.json").readText(), JSONObject(shipped).getString("rules"))
        assertTrue(opened!!.feeds.keys.all { it in Storage.FEEDS })
        // Signed on a clock ahead: every later signing would lose to it until then.
        assertTrue("signed in the future", opened.updated <= Instant.now())
    }

    @Test
    fun `the rules come over HTTPS only, never plain HTTP`() {
        assertTrue(Storage.RULES_URL.startsWith("https://"))
        assertTrue("fetched as the format this build reads",
            Storage.RULES_URL.endsWith("/${Storage.RULES_FORMAT}/rules.signed.json"))
        // And the system refuses plain HTTP for the whole app.
        val app = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS("http://schemas.android.com/apk/res/android", "usesCleartextTraffic"))
    }

    // ---- when to fetch ----

    private val hour = 3_600_000L
    private val now = 1_000_000_000_000L

    @Test
    fun `fetch - every 6 hours, or as soon as the app updated`() {
        assertFalse(Storage.shouldFetchRules(now - 5 * hour, now, 10, 10))
        assertTrue(Storage.shouldFetchRules(now - 6 * hour, now, 10, 10))
        assertTrue("app updated", Storage.shouldFetchRules(now - 2 * 60_000, now, 10, 11))
        assertTrue("never fetched", Storage.shouldFetchRules(0, now, -1, 10))
    }

    @Test
    fun `fetch - never twice in a minute`() {
        assertFalse(Storage.shouldFetchRules(now - 30_000, now, 10, 11))
    }

    // ---- is the feed on screen ----

    private fun found(top: Int, bottom: Int, shown: Boolean = true, left: Int = 0, right: Int = 1080) =
        Storage.FoundView(shown, Storage.Bounds(left, top, right, bottom))

    @Test
    fun `feed - a shown player filling the window`() {
        assertTrue(Storage.feedShowing(listOf(found(0, 2400)), window, 0.7f))
        assertTrue("just enough", Storage.feedShowing(listOf(found(0, 1680)), window, 0.7f))
    }

    @Test
    fun `feed - a small player (a shelf on the home tab) isn't the feed`() {
        assertFalse(Storage.feedShowing(listOf(found(600, 1400)), window, 0.7f))
    }

    @Test
    fun `feed - a hidden or empty player isn't the feed`() {
        assertFalse("kept in the background", Storage.feedShowing(listOf(found(0, 2400, shown = false)), window, 0.7f))
        assertFalse("no width", Storage.feedShowing(listOf(found(0, 2400, left = 5, right = 5)), window, 0.7f))
        assertFalse("nothing found", Storage.feedShowing(emptyList(), window, 0.7f))
    }

    @Test
    fun `feed - only the part inside the window counts`() {
        // Scrolled mostly off the top: 2400 tall, but 600 px of it on screen.
        assertFalse(Storage.feedShowing(listOf(found(-1800, 600)), window, 0.7f))
    }

    @Test
    fun `feed - in a pop-up, measured against the pop-up`() {
        val popUp = Storage.Bounds(500, 300, 900, 1100)
        assertTrue(Storage.feedShowing(listOf(found(320, 1100, left = 500, right = 900)), popUp, 0.7f))
        assertFalse(Storage.feedShowing(listOf(found(900, 1100, left = 500, right = 900)), popUp, 0.7f))
    }

    // ---- which wall ----

    @Test
    fun `walls - a spent app is walled whole, even showing its feed`() {
        assertEquals(mapOf(yt to Storage.WallKind.SPENT), Storage.wallKinds(setOf(yt), setOf(yt)))
        assertEquals(mapOf(yt to Storage.WallKind.FEED, "a" to Storage.WallKind.SPENT),
            Storage.wallKinds(setOf("a"), setOf(yt)))
        assertEquals(emptyMap<String, Storage.WallKind>(), Storage.wallKinds(emptySet(), emptySet()))
    }
}
