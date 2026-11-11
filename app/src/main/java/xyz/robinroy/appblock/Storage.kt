package xyz.robinroy.appblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// A blocking rule: "allow `allowMin` minutes of use per rolling `windowMin` minutes".
data class Rule(val allowMin: Int, val windowMin: Int)

object Storage {

    // ---- rolling-window math (pure functions, unit-tested in StorageTest) ----

    /** Milliseconds of usage falling inside the window of `windowMin` minutes ending at `now`. */
    fun usedMs(intervals: List<Pair<Long, Long>>, windowMin: Int, now: Long): Long {
        val windowStart = now - windowMin * 60_000L
        var used = 0L
        for ((start, end) in intervals) {
            val s = maxOf(start, windowStart)
            val e = minOf(end, now)
            if (e > s) used += e - s
        }
        return used
    }

    /**
     * How long until the rolling window frees up enough budget to use the app
     * again (0 if it's usable right now), in whole minutes. With no new use
     * after `now`, usage in the window only falls as time passes, so this
     * binary-searches for the first minute it's under the allowance: a few
     * dozen steps even for a window of weeks.
     */
    fun msUntilUnblocked(intervals: List<Pair<Long, Long>>, rule: Rule, now: Long): Long {
        val allowMs = rule.allowMin * 60_000L
        fun freeAfter(min: Long) = usedMs(intervals, rule.windowMin, now + min * 60_000L) < allowMs
        var lo = 0L
        var hi = rule.windowMin.toLong()
        if (!freeAfter(hi)) return hi * 60_000L
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (freeAfter(mid)) hi = mid else lo = mid + 1
        }
        return lo * 60_000L
    }

    /** Whole minutes for display, any partial minute rounding up. */
    fun ceilMin(ms: Long): Long = (ms + 59_999) / 60_000

    /** Allowance left right now, never negative. */
    fun remainingMs(usedMs: Long, allowMin: Int): Long =
        maxOf(0L, allowMin * 60_000L - usedMs)

    /**
     * Whether the session-timer notification needs re-posting for a freshly
     * computed deadline and warning level. The deadline is recomputed every
     * tick and jitters by a few ms; it only truly moves when old usage ages
     * out of the rolling window and hands time back. Re-posting for jitter
     * would make the countdown flicker. The 50%/90% warnings live in the
     * timer's text, so crossing a threshold (either way) re-posts too.
     */
    fun timerNeedsRepost(shownDeadline: Long?, deadline: Long, shownLevel: Int, level: Int): Boolean =
        shownDeadline == null || Math.abs(deadline - shownDeadline) >= 1_000 || level != shownLevel

    // ---- what's on screen, from the accessibility service's window list ----

    /** A rectangle in screen pixels (Android's Rect doesn't run in unit tests). */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun union(o: Bounds) =
            Bounds(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    }

    /** What a window on screen is: an app's, the system's (status bar, shade, power menu), or other (keyboard, overlays). */
    enum class WindowKind { APP, SYSTEM, OTHER }

    /**
     * One window on screen. `pkg` is only known for app windows. `pip`: it's
     * a picture-in-picture window (the mini player YouTube shrinks into when
     * the user goes home mid-video), moved and closed by the system, not the app.
     */
    data class ScreenWindow(val kind: WindowKind, val bounds: Bounds, val pkg: String? = null,
                            val pip: Boolean = false)

    /**
     * The apps on screen and the area each one covers. Every app window
     * counts, not just the one in front: both halves of a split screen and a
     * floating pop-up window are on screen together. An app with several
     * windows (a dialog over it) gets the area around all of them. Moly's
     * own windows are skipped.
     */
    fun appsOnScreen(windows: List<ScreenWindow>, self: String): Map<String, Bounds> {
        val out = mutableMapOf<String, Bounds>()
        for (w in windows) {
            val pkg = w.pkg ?: continue
            if (w.kind != WindowKind.APP || pkg == self) continue
            out[pkg] = out[pkg]?.union(w.bounds) ?: w.bounds
        }
        return out
    }

    /**
     * Whether a system window is over most of the screen: the notification
     * shade pulled down, or the power menu. The walls step aside for it (it
     * would otherwise be under them), and come back when it closes.
     */
    fun systemOnTop(windows: List<ScreenWindow>, screenHeight: Int): Boolean =
        windows.any { it.kind == WindowKind.SYSTEM && it.bounds.height > screenHeight / 2 }

    /**
     * The apps on screen after a fresh read of the window list. A system
     * window over most of the screen hides the apps under it from the list,
     * but they're still open there (and still counting), so they're kept as
     * they were until it closes.
     */
    fun onScreenAfter(windows: List<ScreenWindow>, self: String, screenHeight: Int,
                      previous: Map<String, Bounds>): Map<String, Bounds> =
        if (systemOnTop(windows, screenHeight)) previous else appsOnScreen(windows, self)

    /**
     * Where the wall goes over an app: its whole area, except where it runs
     * under the status bar (a thin system window along the top). The wall
     * sits above every other window, so covering the status bar would keep
     * the notification shade from being pulled down.
     */
    fun wallBounds(app: Bounds, windows: List<ScreenWindow>, screenHeight: Int): Bounds {
        val statusBar = windows.filter {
            it.kind == WindowKind.SYSTEM && it.bounds.top <= 0 && it.bounds.height in 1 until screenHeight / 8
        }.maxOfOrNull { it.bounds.bottom } ?: 0
        val top = maxOf(app.top, statusBar)
        return if (top < app.bottom) app.copy(top = top) else app
    }

    /**
     * How much the wall says, by the size of what it covers (in dp): all of
     * it (icon, hint, button) given the room, less in a split-screen half,
     * and just a word and the wait in a pop-up a few fingers wide, where
     * the app's name and "is blocked" would break mid-word.
     */
    enum class WallSize { FULL, COMPACT, TINY }

    fun wallSize(widthDp: Int, heightDp: Int): WallSize = when {
        widthDp < 160 || heightDp < 240 -> WallSize.TINY
        heightDp < 560 -> WallSize.COMPACT
        else -> WallSize.FULL
    }

    /**
     * Whether an app's window is a pop-up: short of the screen both ways. A
     * split-screen half still spans the screen one way, and a full-screen
     * app beside a navigation bar the other.
     */
    fun isPopUp(app: Bounds, screenWidth: Int, screenHeight: Int) =
        app.width < screenWidth && app.height < screenHeight

    // ---- short-video feeds (YouTube Shorts, Instagram Reels): blocked outright, always ----
    //
    // The apps whose feed can be blocked are FEEDS, below. How to spot the
    // feed in each comes from a signed rules file (rules/v1/rules.json in
    // the repo): bundled in the APK, and fetched again from
    // trymoly.com so a redesign is fixed without an app update. A
    // rule names the player's view ids, never text, so it works in every
    // language. However the player opened (a tab, a link, a DM, a tap on a
    // thumbnail), the wall goes up while it fills the app's window; a
    // thumbnail or a small preview never does.

    /** An app's short-video feed: what it's called, and its switch's label on the home list. */
    data class Feed(val name: String, val label: String)

    /** The apps with a feed to block. A rule for any other app does nothing. */
    val FEEDS = mapOf(
        "com.google.android.youtube" to Feed("Shorts", "Block YouTube Shorts"),
        "com.instagram.android" to Feed("Reels", "Block Instagram Reels"),
    )

    /** The feed wall's headline: "Reels are blocked.", or just "No Reels." on a wall a few fingers wide. */
    fun feedHeadline(feed: Feed, tiny: Boolean): String =
        if (tiny) "No ${feed.name}." else "${feed.name} are blocked."

    /**
     * One way to recognise a feed's player: a view with one of `ids`, shown,
     * and covering at least `minCover` of the app window's height (the
     * Shorts shelf on YouTube's home tab has a thumbnail, not a player).
     * `minVersion`/`maxVersion` limit it to those app versionCodes.
     */
    data class FeedRule(val ids: List<String>, val minCover: Float,
                        val minVersion: Long? = null, val maxVersion: Long? = null)

    /**
     * The rules file: when it was signed (`updated`, UTC; the newer file
     * wins), and the feeds. Its format is the `v1` in its path: a build
     * fetches only the format it reads.
     */
    data class FeedRules(val updated: Instant, val feeds: Map<String, List<FeedRule>>)

    /**
     * The rules format this build reads: rules/v1/ in the repo, v1/ in the
     * APK's assets and on the site. A new format gets a new path (v2/), and
     * the builds that read it fetch that instead.
     */
    const val RULES_FORMAT = "v1"

    /** Where the rules are fetched from: this repo's server (server/), over HTTPS only. */
    const val RULES_URL = "https://trymoly.com/$RULES_FORMAT/rules.signed.json"

    /** `updated`'s one accepted form: UTC to the second, as `./gradlew signRules` writes it. */
    private val UTC_SECOND = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

    /** The rules in a rules file, or null when it's malformed. */
    fun parseFeedRules(json: String): FeedRules? = try {
        val o = JSONObject(json)
        val updated = o.getString("updated")
        if (!UTC_SECOND.matches(updated)) null
        else {
            val feeds = mutableMapOf<String, List<FeedRule>>()
            val f = o.getJSONObject("feeds")
            for (pkg in f.keys()) {
                val arr = f.getJSONArray(pkg)
                feeds[pkg] = (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    val ids = r.getJSONArray("ids")
                    FeedRule((0 until ids.length()).map { ids.getString(it) },
                        r.getDouble("minCover").toFloat(),
                        if (r.has("minVersion")) r.getLong("minVersion") else null,
                        if (r.has("maxVersion")) r.getLong("maxVersion") else null)
                }
            }
            FeedRules(Instant.parse(updated), feeds)
        }
    } catch (e: Exception) { null }

    /** The rules that apply to `pkg` at `versionCode`. */
    fun feedRulesFor(rules: FeedRules, pkg: String, versionCode: Long): List<FeedRule> =
        rules.feeds[pkg].orEmpty().filter {
            (it.minVersion == null || versionCode >= it.minVersion) &&
                (it.maxVersion == null || versionCode <= it.maxVersion)
        }

    /** A view found by id in an app's window: whether it's shown, and where. */
    data class FoundView(val shown: Boolean, val bounds: Bounds)

    /** Whether any found view is a player: shown, and over `minCover` of the window's height. */
    fun feedShowing(found: List<FoundView>, window: Bounds, minCover: Float): Boolean =
        window.height > 0 && found.any {
            val covered = minOf(it.bounds.bottom, window.bottom) - maxOf(it.bounds.top, window.top)
            it.shown && it.bounds.width > 0 && covered >= minCover * window.height
        }

    /**
     * Whether `json` was signed by the rules key: an ECDSA P-256 signature
     * (base64, DER) over its UTF-8 bytes. The private key stays on the
     * maintainer's machine (`./gradlew signRules`); this is its public half.
     */
    fun rulesSigned(json: String, sig: String, publicKey: String = RULES_PUBLIC_KEY): Boolean = try {
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(publicKey)))
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(json.toByteArray(Charsets.UTF_8))
            verify(java.util.Base64.getDecoder().decode(sig.trim()))
        }
    } catch (e: Exception) { false }

    const val RULES_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXhto9YHeHzV8cvmiVrjSRBv4v9HE/2MRvMGIzsTYvdsSTNIsBkgV2T9yWMwV0MIGBXi85yjBx82gxMZRH6WrsA=="

    /**
     * The rules inside a signed file (rules/v1/rules.signed.json: the rules
     * file as a string, and its signature), or null when the signature
     * doesn't check out or the rules don't parse.
     */
    fun openSignedRules(envelope: String, publicKey: String = RULES_PUBLIC_KEY): FeedRules? = try {
        val o = JSONObject(envelope)
        val json = o.getString("rules")
        if (rulesSigned(json, o.getString("sig"), publicKey)) parseFeedRules(json) else null
    } catch (e: Exception) { null }

    /** Which rules to use: the newest. A fetched file never rolls back what the APK ships. */
    fun newerRules(current: FeedRules?, candidate: FeedRules?): FeedRules? = when {
        candidate == null -> current
        current == null || candidate.updated > current.updated -> candidate
        else -> current
    }

    /**
     * Whether to fetch the rules again, as a feed's app comes on screen:
     * when the app updated since the last fetch (a new version may need new
     * rules), or 6 hours on. Never twice in a minute, whatever happens.
     */
    fun shouldFetchRules(lastAttempt: Long, now: Long, versionAtLastAttempt: Long, versionNow: Long): Boolean =
        now - lastAttempt >= 60_000 &&
            (versionNow != versionAtLastAttempt || now - lastAttempt >= 6 * 3_600_000L)

    /** What to do about the apps on screen: wall the ones whose budget is spent, time the other limited ones. */
    data class Plan(val walled: Set<String>, val timed: Set<String>)

    fun plan(onScreen: Collection<String>, rules: Map<String, Rule>, spent: (String, Rule) -> Boolean): Plan {
        val walled = mutableSetOf<String>()
        val timed = mutableSetOf<String>()
        for (pkg in onScreen) {
            val rule = rules[pkg] ?: continue
            if (spent(pkg, rule)) walled += pkg else timed += pkg
        }
        return Plan(walled, timed)
    }

    /**
     * Why a wall is up: the app's time is spent, its feed (Shorts, Reels) is
     * on screen, or it's a system app showing a way around strict mode.
     */
    enum class WallKind { SPENT, FEED, STRICT }

    /** The walls to put up. A spent app is walled whole, whatever it's showing. */
    fun wallKinds(spent: Set<String>, feedShowing: Set<String>, strict: Set<String> = emptySet()): Map<String, WallKind> =
        feedShowing.associateWith { WallKind.FEED } + spent.associateWith { WallKind.SPENT } +
            strict.associateWith { WallKind.STRICT }

    // ---- picture-in-picture: the mini player YouTube shrinks into when the user goes home mid-video ----

    /** The apps with a picture-in-picture window on screen. */
    fun pipApps(windows: List<ScreenWindow>): Set<String> =
        windows.filter { it.kind == WindowKind.APP && it.pip }.mapNotNull { it.pkg }.toSet()

    /**
     * Whether a wall lets touches through to the window under it: only over
     * a spent app in picture-in-picture. The system handles every touch
     * there (dragging it, flinging it away, its close button) and the app
     * none, so the user can close it without being able to use it. Every
     * other wall takes its touches: a pop-up's app is usable inside it.
     */
    fun wallPassesTouches(kind: WallKind, pip: Boolean) = kind == WallKind.SPENT && pip

    /**
     * Whether to pause what's playing as a wall goes up: a spent app's
     * first wall (not the same wall moved or redrawn), while something
     * plays. Paused, a video doesn't play on behind the wall, and going
     * home doesn't shrink it into picture-in-picture (YouTube only does that
     * mid-video). The pause goes to whichever app played last, so it's not
     * sent when nothing is playing, and a feed wall leaves the rest of the
     * app (and what it plays) alone.
     */
    fun pauseOnWall(kind: WallKind, alreadyWalled: Boolean, playing: Boolean) =
        kind == WallKind.SPENT && !alreadyWalled && playing

    /**
     * The spent apps whose picture-in-picture window to close now: those
     * walled in one that weren't asked to close it while it's been up
     * (`asked`). Asked once: if the system ignores it, the user drags it
     * away through the wall instead.
     */
    fun pipsToClose(kinds: Map<String, WallKind>, pip: Set<String>, asked: Set<String>): Set<String> =
        kinds.filterValues { it == WallKind.SPENT }.keys.filter { it in pip && it !in asked }.toSet()

    /** The apps asked to close their picture-in-picture window, after `closing` were: one that's gone may be asked again. */
    fun pipsAsked(asked: Set<String>, pip: Set<String>, closing: Set<String>): Set<String> =
        asked.intersect(pip) + closing

    /**
     * Whether Settings' list of enabled accessibility services (the
     * colon-separated `enabled_accessibility_services` value) has ours in it.
     * Entries name the class in full or relative to the package
     * ("xyz.robinroy.appblock/.BlockerService").
     */
    fun serviceEnabled(setting: String?, pkg: String, cls: String): Boolean =
        !setting.isNullOrEmpty() && setting.split(':').any { entry ->
            val name = entry.trim()
            name.equals("$pkg/$cls", ignoreCase = true) ||
                name.equals("$pkg/" + cls.removePrefix(pkg), ignoreCase = true)
        }

    // Usage-warning thresholds, as percent of the allowance.
    private val WARN_THRESHOLDS = listOf(50, 90)

    /** What the home screen's list area shows. */
    enum class HomeList { CARDS, EMPTY_HINT, PAUSED_NOTE }

    /**
     * CARDS: every required setting on, apps blocked. EMPTY_HINT ("no apps
     * yet"): nothing blocked — on a fresh install the "Finish setup"
     * checklist above it covers the required settings, so the list area
     * needs no setup hint of its own. PAUSED_NOTE: a requirement is off but
     * rules exist — they're kept, blocking just isn't enforced.
     */
    fun homeList(blockingOn: Boolean, ruleCount: Int): HomeList = when {
        ruleCount == 0 -> HomeList.EMPTY_HINT
        blockingOn -> HomeList.CARDS
        else -> HomeList.PAUSED_NOTE
    }


    /** Percent of the allowance used, for display, capped at 100. */
    fun usedPct(usedMs: Long, allowMin: Int): Int =
        if (allowMin <= 0) 100
        else minOf(100L, usedMs * 100 / (allowMin * 60_000L)).toInt()

    /**
     * Highest warning threshold (50/90) that current usage has reached, or 0.
     * It follows usage back DOWN as old intervals age out of the rolling
     * window, so the timer's warning text does too.
     */
    fun crossedWarnLevel(usedMs: Long, allowMin: Int): Int {
        if (allowMin <= 0) return 0
        val pct = usedMs * 100 / (allowMin * 60_000L)
        return WARN_THRESHOLDS.lastOrNull { it <= pct } ?: 0
    }

    /**
     * Everything Moly can't work without, as data. Each entry carries its
     * own user-facing copy, and every surface generates itself from this
     * list: the home screen's "Finish setup" checklist, the "Block an app"
     * guard sheet, the About permissions rows. Declaration
     * order is fix-first priority (the blocker itself first). A future
     * requirement is one entry here plus a branch in requirementMet() (Perms.kt) and MainActivity's
     * requirementFix(); those `when`s are exhaustive, so forgetting one is a
     * compile error, not a stale screen.
     */
    enum class Requirement(
        val title: String,     // row title in the setup checklist and About permissions
        val why: String,       // friendly explanation in the "Block an app" guard sheet
        val permsNote: String, // one-liner under the About permissions row
    ) {
        ACCESSIBILITY(
            "Accessibility",
            "That's how Moly sees which apps are on screen, split " +
                "screen and pop-up windows included, and how it puts the " +
                "wall over one whose time is up. It only looks at which app " +
                "each window belongs to, never at what's inside it (except " +
                "for YouTube's Shorts player or Instagram's Reels player, if " +
                "you block them, and in strict mode its own name on Settings' " +
                "screens).",
            "Required. Sees which apps are on screen and draws the wall."),
        BATTERY(
            "Unrestricted battery",
            "Your phone's battery saver puts background apps to sleep, and " +
                "a sleeping blocker can't block. This lets Moly stay " +
                "awake, at a tiny battery cost. So if your phone ever " +
                "flags Moly for using battery in the background, " +
                "that's not a bug. That's the watchman staying on duty.",
            "Required. Stops the phone's battery saver from killing the blocker."),
    }

    /**
     * Body of the "Block an app" guard sheet, composed on the fly: one
     * segment per missing requirement, so the sheet always matches exactly
     * what's off — one requirement or several, always a single sheet.
     */
    fun requirementsMessage(missing: List<Requirement>): String =
        missing.joinToString("\n\n") { "${it.title}: ${it.why}" } +
            "\n\nThe checklist on the home screen will get you set up."

    /**
     * Phrases joined with " · ", each kept on one line: a line that's too
     * long breaks between them, never inside one ("12 minutes / 34 seconds").
     */
    fun phrases(vararg parts: String): String = parts.joinToString(" · ") { it.replace(' ', '\u00A0') }

    /** The countdown notification before any warning: "Allowance for the next 2 hours." */
    fun allowanceNote(windowMin: Int) = "Allowance for the next ${fmtSpan(windowMin)}."

    /** Strict mode's cooldown, before it's started: "...after a cooldown of 15 minutes you can..." */
    fun cooldownNote(cooldownMin: Int) =
        "Tap Stop, and after a cooldown of ${fmtSpan(cooldownMin)} you can turn strict mode off. " +
            "Everything stays locked until then."

    /** An app's line in the picker: "22 minutes today · 2 hours 5 minutes last 7 days", like the home list's "today". */
    fun pickerUsage(todayMs: Long, weekMs: Long): String =
        if (todayMs == 0L && weekMs == 0L) "Not used in the last 7 days"
        else phrases(fmtToday(todayMs), "${fmtScreenTime(weekMs)} last 7 days")

    /** "1 minute", "5 minutes": a count and its unit, in full words. */
    private fun count(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /**
     * A span in words, for the home list, the limit dialog and the block
     * wall: "45 minutes", "1 hour", "2 hours", "1 hour 30 minutes", "7 days",
     * "1 day 6 hours". Full words: "min" and "hrs" read as jargon.
     */
    fun fmtSpan(min: Int): String {
        val d = min / 1440
        val h = min / 60 % 24
        val m = min % 60
        val parts = listOfNotNull(
            if (d == 0) null else count(d.toLong(), "day"),
            if (h == 0) null else count(h.toLong(), "hour"),
            if (m == 0) null else count(m.toLong(), "minute"))
        return if (parts.isEmpty()) "0 minutes" else parts.joinToString(" ")
    }

    /**
     * Time, exactly, to the second (cut, never rounded): "45 seconds",
     * "1 minute 23 seconds", "1 hour 2 minutes 3 seconds", "1 day 2 hours",
     * "0 seconds".
     */
    fun fmtExact(ms: Long): String {
        val total = maxOf(0L, ms) / 1000
        val parts = listOfNotNull(
            (total / 86_400).takeIf { it > 0 }?.let { count(it, "day") },
            (total / 3600 % 24).takeIf { it > 0 }?.let { count(it, "hour") },
            (total / 60 % 60).takeIf { it > 0 }?.let { count(it, "minute") },
            (total % 60).takeIf { it > 0 }?.let { count(it, "second") })
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }

    /** A countdown's face: "4:05", "1:02:03", "3d 4:05:06"; seconds round up, so it never shows 0:00 early. */
    fun fmtClock(ms: Long): String {
        val total = (maxOf(0L, ms) + 999) / 1000
        val d = total / 86_400
        val h = total / 3600 % 24
        val m = total / 60 % 60
        val s = total % 60
        val ms2 = "%02d:%02d".format(m, s)
        return when {
            d > 0 -> "${d}d $h:$ms2"
            h > 0 -> "$h:$ms2"
            else -> "$m:%02d".format(s)
        }
    }

    /** Today's screen time under an app's name on the home list. */
    fun fmtToday(ms: Long): String = when {
        ms == 0L -> "Not used today"
        ms < 60_000 -> "Less than a minute today"
        else -> fmtSpan((ms / 60_000).toInt()) + " today"
    }

    // ---- the limit sheet: "allow X in any Y", each a number and a unit ----

    /** Units the limit sheet offers; the number beside a unit is what you type or step. */
    enum class SpanUnit(val minutes: Int, val label: String) {
        MIN(1, "minutes"), HR(60, "hours"), DAY(1440, "days")
    }

    /** One-tap starting points. The sheet highlights whichever one the fields currently match. */
    val PRESETS = listOf(Rule(5, 120), Rule(10, 60), Rule(15, 120), Rule(30, 240), Rule(60, 1440))

    /** Typed numbers stop here: far past a week in any unit, and small enough never to overflow an Int of minutes. */
    private const val MAX_AMOUNT = 99_999

    /**
     * Whether a rule can actually limit anything: some allowance, shorter
     * than its window (allowance >= window would never block). Gates the
     * limit sheet's OK button.
     */
    fun validRule(rule: Rule): Boolean =
        rule.allowMin >= 1 && rule.allowMin < rule.windowMin

    /** The largest of `units` that divides `minutes` evenly: 120 -> HR, 90 -> MIN, 1440 -> DAY. */
    fun unitFor(minutes: Int, units: List<SpanUnit>): SpanUnit =
        units.lastOrNull { minutes % it.minutes == 0 } ?: units.first()

    /** A typed number: digits only, 1 up to the cap. Null while blank or nonsense. */
    fun parseAmount(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_AMOUNT }

    /**
     * The rule in the limit sheet's two fields. Null until both hold numbers
     * and the rule is valid (which is what keeps OK disabled).
     */
    fun limitRule(allow: String, allowUnit: SpanUnit, window: String, windowUnit: SpanUnit): Rule? {
        val a = parseAmount(allow) ?: return null
        val w = parseAmount(window) ?: return null
        return Rule(a * allowUnit.minutes, w * windowUnit.minutes).takeIf(::validRule)
    }

    /**
     * What's wrong with the fields, in words, or null. Only judges once both
     * are numbers, so half-typed input isn't flagged.
     */
    fun limitError(allow: String, allowUnit: SpanUnit, window: String, windowUnit: SpanUnit): String? {
        val a = parseAmount(allow) ?: return null
        val w = parseAmount(window) ?: return null
        return if (a * allowUnit.minutes >= w * windowUnit.minutes)
            "The allowance has to be shorter than the window." else null
    }

    /**
     * One press of a stepper button. Small minute counts move by 1; from 10
     * up, minutes move in fives and land on multiples of five (12 -> 15,
     * 12 down -> 10). Hours and days always move by 1. Never below 1.
     */
    fun stepAmount(value: Int, unit: SpanUnit, up: Boolean): Int = when {
        up -> if (unit == SpanUnit.MIN && value >= 10) (value / 5 + 1) * 5 else value + 1
        else -> if (unit == SpanUnit.MIN && value > 10) (value - 1) / 5 * 5 else value - 1
    }.coerceIn(1, MAX_AMOUNT)

    /**
     * "1 minute 23 seconds used" on a home-list card: exact to the second,
     * capped at the allowance so the blocker's last moment before the wall
     * never shows as more than the limit.
     */
    fun fmtUsed(usedMs: Long, allowMin: Int): String =
        fmtExact(minOf(usedMs, allowMin * 60_000L)) + " used"

    /**
     * "2 minutes 55 seconds left": exact, the partial second counted (so it
     * and the time used, cut, always add up to the allowance).
     */
    fun fmtLeft(usedMs: Long, allowMin: Int): String =
        fmtExact(remainingMs(usedMs, allowMin) + 999) + " left"

    // ---- the clock: everything time-stamped moves with it ----
    //
    // The usage logs and strict mode's times are wall-clock times, and the
    // user can set the wall clock to anything: forward, and the rolling
    // window no longer holds the time they used; back, and it lies in the
    // future. So Moly watches for the clock being moved and shifts every
    // stored time by as much, so past use keeps its age (issue #7).

    /**
     * Where the phone's two clocks stood together, on boot number `boot`:
     * the wall clock (which the user can set) and the time since boot (which
     * they can't). Kept to catch the wall clock being moved.
     */
    data class ClockAnchor(val wall: Long, val elapsed: Long, val boot: Int)

    /** The wall clock may drift this much from the time since boot before it counts as moved. */
    const val CLOCK_SLACK_MS = 60_000L

    /**
     * How far the wall clock was moved since `anchor` (positive: forward),
     * or 0 when it wasn't: the two clocks still agree within a minute (the
     * network's small corrections), or the phone restarted since (the time
     * since boot starts again, so the two can't be compared). A time since
     * boot that went backwards is a restart too, whatever the boot count says.
     */
    fun clockMoved(anchor: ClockAnchor?, wall: Long, elapsed: Long, boot: Int): Long {
        if (anchor == null || anchor.boot != boot || elapsed < anchor.elapsed) return 0
        val drift = (wall - anchor.wall) - (elapsed - anchor.elapsed)
        return if (Math.abs(drift) > CLOCK_SLACK_MS) drift else 0
    }

    /**
     * The anchor after a look at the clocks: set again after a move, a
     * restart or on the first look; otherwise the old one stays, so small
     * moves add up.
     */
    fun nextAnchor(anchor: ClockAnchor?, wall: Long, elapsed: Long, boot: Int): ClockAnchor =
        if (anchor != null && anchor.boot == boot && elapsed >= anchor.elapsed &&
            clockMoved(anchor, wall, elapsed, boot) == 0L) anchor
        else ClockAnchor(wall, elapsed, boot)

    /**
     * The span to log for a session on screen since `since` (where both
     * clocks stood when it started or was last logged), the clocks now
     * reading `wall` and `elapsed`. It starts where the last log ended, so a
     * running session stays one entry in the log (appendUsage merges it);
     * but if the clock moved meanwhile, it's as long as the app was really
     * on screen, ending now.
     */
    fun sessionSpan(since: ClockAnchor, wall: Long, elapsed: Long): Pair<Long, Long> =
        if (clockMoved(since, wall, elapsed, since.boot) == 0L) since.wall to wall
        else wall - (elapsed - since.elapsed) to wall

    /** A usage log with the clock moved `by` ms: every entry keeps its age. */
    fun shiftLog(log: List<Pair<Long, Long>>, by: Long): List<Pair<Long, Long>> =
        log.map { (start, end) -> start + by to end + by }

    // ---- strict mode: Moly locks itself in until the user's chosen way out ----
    //
    // While it's on, Moly is a device admin (so Android refuses to
    // uninstall it), the blocker walls the system's pages that could switch
    // it off, and the rules can only get stricter. It ends only through the
    // way out chosen when it went on, safe mode (Moly finds its admin
    // switched off), or adb.

    /** How strict mode is turned off: only while the phone is plugged in, or after a cooldown. */
    enum class WayOut { PLUG, COOLDOWN }

    /**
     * Strict mode's state. `pending`: chosen, waiting for the user to make
     * Moly a device admin. `cooldownMin` only means something for
     * COOLDOWN; `pass` is the emergency-pass budget, null without passes.
     * `cooldownEnds` and `passStarted` are wall-clock times, 0 when there's
     * no cooldown counting or no pass open; `passUses` is the pass's own
     * usage log, like an app's. An open pass only counts while a blocked app
     * is on screen: `passInUse` is when one came on screen (the time since
     * not logged yet), 0 while none is.
     */
    data class Strict(
        val on: Boolean = false,
        val pending: Boolean = false,
        val wayOut: WayOut = WayOut.COOLDOWN,
        val cooldownMin: Int = 15,
        val pass: Rule? = null,
        val cooldownEnds: Long = 0,
        val passUses: List<Pair<Long, Long>> = emptyList(),
        val passStarted: Long = 0,
        val passInUse: Long = 0,
    )

    /** The cooldown's one-tap lengths, in minutes. */
    val COOLDOWN_PRESETS = listOf(5, 15, 30, 60)

    /** The emergency pass's one-tap budgets. */
    val PASS_PRESETS = listOf(Rule(5, 1440), Rule(15, 1440), Rule(30, 10080))

    /**
     * The most minutes `rule` lets anyone use in `spanMin` straight minutes:
     * the whole allowance, a wait, the whole allowance again. Longs: a rule
     * of 99,999 days over a span as long overflows an Int.
     */
    fun maxUseMin(rule: Rule, spanMin: Long): Long =
        rule.allowMin.toLong() * (spanMin / rule.windowMin) + minOf(rule.allowMin.toLong(), spanMin % rule.windowMin)

    /**
     * Whether changing an app's rule from `old` to `new` gives more time
     * anywhere: strict mode allows only changes that don't. A new app
     * (`old` null) gives none; removing one (`new` null) gives it all back.
     *
     * A longer window is stricter ("10 minutes in any day" is tighter than "10
     * minutes in any hour"), and a shorter one can be too, with a small enough
     * allowance ("1 minute in any 30" is tighter than "10 in any 60"). It comes
     * down to one number: the new rule gives more time if it allows more
     * than `old.allowMin` within one of `old`'s windows. (Within that window
     * it's the only thing that counts, and over any longer span the old
     * rule's window repeats while the new rule can do no better per window.)
     */
    fun loosens(old: Rule?, new: Rule?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> maxUseMin(new, old.windowMin.toLong()) > old.allowMin
    }

    /** Whether a cooldown was started: counting down, or run out and waiting for Turn off. */
    fun coolingDown(s: Strict) = s.on && s.cooldownEnds != 0L

    /**
     * Whether the cooldown has run out. Strict mode stays on until the user
     * taps Turn off (or Keep it on, which locks it again).
     */
    fun cooldownDone(s: Strict, now: Long) = coolingDown(s) && now >= s.cooldownEnds

    /** Whether Turn off works now: plugged in, or after the cooldown ran out. */
    fun canTurnOff(s: Strict, now: Long, pluggedIn: Boolean) = s.on && when (s.wayOut) {
        WayOut.PLUG -> pluggedIn
        WayOut.COOLDOWN -> cooldownDone(s, now)
    }

    fun cooldownLeftMs(s: Strict, now: Long): Long =
        if (coolingDown(s)) maxOf(0L, s.cooldownEnds - now) else 0L

    fun startCooldown(s: Strict, now: Long) = s.copy(cooldownEnds = now + s.cooldownMin * 60_000L)

    fun cancelCooldown(s: Strict) = s.copy(cooldownEnds = 0)

    /**
     * Strict mode after the clock was moved by `moved` (clockMoved), now
     * reading `wall`. A counting cooldown starts over from its full length
     * (so moving the clock never shortens the wait), but one that had
     * already run out stays run out. The emergency pass's log shifts by as
     * much (so moving the clock never refills the pass).
     */
    fun afterClockMove(s: Strict, moved: Long, wall: Long): Strict {
        if (!s.on || moved == 0L) return s
        return s.copy(
            cooldownEnds = when {
                s.cooldownEnds == 0L -> 0
                // Ran out before the move: shifted with the clock, so it stays run out.
                wall - moved >= s.cooldownEnds -> maxOf(1L, s.cooldownEnds + moved)
                else -> wall + s.cooldownMin * 60_000L
            },
            passUses = shiftLog(s.passUses, moved),
            passStarted = if (s.passStarted != 0L) s.passStarted + moved else 0,
            passInUse = if (s.passInUse != 0L) s.passInUse + moved else 0)
    }

    /** The pass's log, with the time counting now (a blocked app on screen under it) added. */
    private fun passLog(s: Strict, now: Long): List<Pair<Long, Long>> =
        if (s.passInUse != 0L && now > s.passInUse) s.passUses + (s.passInUse to now) else s.passUses

    /** The pass time left to use, open or not: it refills as old use leaves its window. */
    fun passSavedMs(s: Strict, now: Long): Long {
        val pass = s.pass ?: return 0
        return remainingMs(usedMs(passLog(s, now), pass.windowMin, now), pass.allowMin)
    }

    /**
     * With the pass used up, how long until some of it comes back (old use
     * leaving its window), in whole minutes; 0 while there's time left.
     */
    fun passBackInMs(s: Strict, now: Long): Long {
        val pass = s.pass ?: return 0
        if (passSavedMs(s, now) > 0) return 0
        return msUntilUnblocked(passLog(s, now), pass, now)
    }

    /** How long until the pass is whole again, its last use out of the window; 0 if it's whole now. */
    fun passFullInMs(s: Strict, now: Long): Long {
        val pass = s.pass ?: return 0
        val last = passLog(s, now).maxOfOrNull { it.second } ?: return 0
        return maxOf(0L, last + pass.windowMin * 60_000L - now)
    }

    /**
     * The line under the pass's button saying when its time comes back:
     * "Back in 1 hour 20 minutes" used up, "Full again in 2 hours" partly used;
     * null when it's whole, there's no pass, or it's counting (then it
     * keeps moving).
     */
    fun passRefillNote(s: Strict, now: Long): String? {
        if (s.pass == null || passCounting(s)) return null
        val backIn = passBackInMs(s, now)
        if (backIn > 0) return "Back in ${fmtSpan(ceilMin(backIn).toInt())}"
        val full = passFullInMs(s, now)
        return if (full > 0) "Full again in ${fmtSpan(ceilMin(full).toInt())}" else null
    }

    /** Whether the user opened a pass (and hasn't stopped it, and it hasn't run out). */
    fun passOpen(s: Strict) = s.on && s.passStarted != 0L

    /** Whether a pass is opening the blocked apps right now: open, with time left. */
    fun passRunning(s: Strict, now: Long) = passOpen(s) && passSavedMs(s, now) > 0

    /** Whether the open pass is counting down: a blocked app is on screen under it. */
    fun passCounting(s: Strict) = passOpen(s) && s.passInUse != 0L

    fun passLeftMs(s: Strict, now: Long): Long = if (passOpen(s)) passSavedMs(s, now) else 0

    /**
     * When the counting pass runs out if the blocked app stays on screen (a
     * little later if old use leaves the window first, so it's checked again
     * then); 0 while it isn't counting.
     */
    fun passEnds(s: Strict, now: Long): Long = if (passCounting(s)) now + passSavedMs(s, now) else 0

    /** A pass opened now, if there's any pass time left (and none open). It counts nothing until a blocked app is on screen. */
    fun startPass(s: Strict, now: Long): Strict =
        if (!s.on || s.passStarted != 0L || passSavedMs(s, now) == 0L) s else s.copy(passStarted = now, passInUse = 0)

    /** How often a counting pass's time is logged: a crash or restart loses at most this much. */
    const val PASS_CHECKPOINT_MS = 5_000L

    /**
     * The open pass, told whether a blocked app is on screen (`inBlocked`)
     * at `now`. It starts counting when one comes on screen, and stops, its
     * time logged, when none is left; its time is not spent while you're
     * anywhere else. While it counts, its time so far is logged every
     * PASS_CHECKPOINT_MS.
     */
    fun meterPass(s: Strict, inBlocked: Boolean, now: Long): Strict = when {
        !passOpen(s) -> if (s.passInUse != 0L) s.copy(passInUse = 0) else s
        inBlocked && s.passInUse == 0L -> s.copy(passInUse = now)
        inBlocked && now - s.passInUse >= PASS_CHECKPOINT_MS -> logPassUse(s, now).copy(passInUse = now)
        !inBlocked && s.passInUse != 0L -> logPassUse(s, now)
        else -> s
    }

    /**
     * The time counted since `passInUse` logged, up to `now` or to when the
     * pass ran out if that was sooner, and nothing counting any more.
     */
    private fun logPassUse(s: Strict, now: Long): Strict {
        val pass = s.pass
        if (s.passInUse == 0L || pass == null || now <= s.passInUse) return s.copy(passInUse = 0)
        val over = maxOf(0L, usedMs(passLog(s, now), pass.windowMin, now) - pass.allowMin * 60_000L)
        val end = maxOf(s.passInUse, now - over)
        val uses = if (end > s.passInUse)
            appendUsage(s.passUses, s.passInUse, end, now - pass.windowMin * 60_000L) else s.passUses
        return s.copy(passUses = uses, passInUse = 0)
    }

    /**
     * The pass closed at `now` (by the user, or run out): the time it
     * counted goes into its log, and what's left stays for later.
     */
    fun stopPass(s: Strict, now: Long): Strict = logPassUse(s, now).copy(passStarted = 0)

    /**
     * The blocker started again (after a crash, or the phone restarting):
     * the time counting since the last log is dropped, not charged, since
     * the phone may have been off for most of it.
     */
    fun passAfterRestart(s: Strict): Strict = if (s.passInUse != 0L) s.copy(passInUse = 0) else s

    /** Strict mode over: the choices kept for next time, everything running dropped. */
    fun strictEnded(s: Strict) =
        s.copy(on = false, pending = false, cooldownEnds = 0, passStarted = 0, passInUse = 0, passUses = emptyList())

    /**
     * Whether a system screen names the app (any of its texts holds `label`,
     * "Moly"): its App info, its Accessibility switch and the "Stop
     * Moly?" dialog, its storage, its device admin page, the uninstall
     * dialog, Safety Center's "Remove access" card, and the lists that lead
     * to them.
     */
    fun namesApp(texts: List<String>, label: String) = texts.any { it.contains(label, ignoreCase = true) }

    /**
     * The system apps to wall in strict mode: those on screen naming
     * Moly (`naming`), and those walled before that are still on screen.
     * A wall stays until its app leaves the screen, so a page doesn't come
     * free by scrolling Moly's name away, and a dialog over it ("Force
     * stop?", "Delete app data?", which don't name it) stays covered.
     */
    fun strictWalls(walledBefore: Set<String>, onScreen: Set<String>, naming: Set<String>): Set<String> =
        walledBefore.intersect(onScreen) + naming

    // ---- screen time: Moly's own history of what was on screen, from Android's usage events ----
    //
    // Android keeps its usage events for a week or so. Moly copies them,
    // as sessions (an app, from when it came on screen to when it left),
    // into a database of its own (History.kt) and keeps them for good, so the
    // home screen can show any day's screen time. These are the decisions;
    // the database and the syncing are in History.kt.

    /** One of Android's usage events, as plain values (UsageEvents.Event, which the JVM tests can't make). */
    data class UsageEv(val time: Long, val type: Int, val pkg: String, val cls: String? = null)

    /** An app on screen from `start` to `end` (wall-clock ms). */
    data class Session(val pkg: String, val start: Long, val end: Long)

    /**
     * Sessions read from a run of events: the `closed` ones, and the `open`
     * ones (still on screen at the end, shown but not stored). `safePoint`
     * is where the next read must start from to see the open ones begin.
     */
    data class Derived(val closed: List<Session>, val open: List<Session>, val safePoint: Long)

    // UsageEvents.Event's codes (ScreenTimeTest checks them against Android's).
    const val EV_RESUMED = 1          // ACTIVITY_RESUMED, was MOVE_TO_FOREGROUND
    const val EV_PAUSED = 2           // ACTIVITY_PAUSED, was MOVE_TO_BACKGROUND
    const val EV_SCREEN_OFF = 16      // SCREEN_NON_INTERACTIVE (Android 9+)
    const val EV_STOPPED = 23         // ACTIVITY_STOPPED (Android 10+)
    const val EV_SHUTDOWN = 26        // DEVICE_SHUTDOWN (Android 10+)
    const val EV_STARTUP = 27         // DEVICE_STARTUP (Android 10+)

    /** An app back on screen within this long counts as one session (moving between its own screens). */
    const val MERGE_GAP_MS = 2_000L

    /** Sessions shorter than this are dropped: splash screens and apps passing straight through. */
    const val MIN_SESSION_MS = 1_000L

    /** No session is longer than this: a lost "left the screen" event can't make one run forever. */
    const val MAX_SESSION_MS = 24 * 3_600_000L

    /**
     * The sessions in a run of usage events (sorted or not), read up to
     * `until`. An app is on screen from when one of its screens is resumed
     * until none is; back within MERGE_GAP_MS, it's one session. The screen
     * going off or the phone shutting down ends everything; a startup with
     * no shutdown before it ends everything at the last event seen. Before
     * Android 10 (`multiResume` false) only one app can be resumed, so
     * another one coming on screen ends it even if its pause got lost.
     * `ignore`d apps (the launcher, the system UI) make no sessions. A pause
     * with nothing resumed (its start was before this run) is ignored: that
     * session was read last time.
     */
    fun sessionsFrom(events: List<UsageEv>, until: Long, ignore: Set<String>, multiResume: Boolean): Derived {
        class Live(val start: Long) {
            val screens = mutableSetOf<String>()
            var left = 0L   // when its last screen left, 0 while one is on
        }
        val live = linkedMapOf<String, Live>()
        val closed = mutableListOf<Session>()

        fun close(pkg: String, at: Long) {
            val l = live.remove(pkg) ?: return
            val end = if (l.left != 0L) minOf(l.left, at) else at
            closed += Session(pkg, l.start, end)
        }
        fun closeAll(at: Long) { for (pkg in live.keys.toList()) close(pkg, at) }

        var last = Long.MIN_VALUE
        for (e in events.sortedBy { it.time }) {
            if (e.time > until) break
            when (e.type) {
                EV_RESUMED -> {
                    if (!multiResume) for (pkg in live.keys.toList()) if (pkg != e.pkg) close(pkg, e.time)
                    if (e.pkg !in ignore) {
                        var l = live[e.pkg]
                        // Gone longer than the gap: that session's over, a new one starts.
                        if (l != null && l.left != 0L && e.time - l.left > MERGE_GAP_MS) { close(e.pkg, l.left); l = null }
                        if (l == null) { l = Live(e.time); live[e.pkg] = l }
                        l.screens += e.cls ?: ""
                        l.left = 0
                    }
                }
                EV_PAUSED, EV_STOPPED -> {
                    val l = live[e.pkg]
                    if (l != null && l.screens.remove(e.cls ?: "") && l.screens.isEmpty()) l.left = e.time
                }
                EV_SCREEN_OFF, EV_SHUTDOWN -> closeAll(e.time)
                EV_STARTUP -> if (last != Long.MIN_VALUE) closeAll(last)
            }
            last = e.time
        }
        // Left the screen by now: closed. Still on it: open.
        for ((pkg, l) in live.toList()) if (l.left != 0L) close(pkg, l.left)
        val open = live.map { (pkg, l) -> Session(pkg, l.start, until) }
        val safePoint = (open.map { it.start } + until).min()
        return Derived(tidy(closed), open, safePoint)
    }

    /** Sessions too short dropped, too long cut. */
    private fun tidy(sessions: List<Session>): List<Session> =
        sessions.filter { it.end - it.start >= MIN_SESSION_MS }
            .map { if (it.end - it.start > MAX_SESSION_MS) it.copy(end = it.start + MAX_SESSION_MS) else it }

    /**
     * Each app's sessions joined where they overlap or are within `gapMs`
     * of each other; different apps never join. Storing the same time
     * twice changes nothing, so reading events again is harmless. None
     * grows past MAX_SESSION_MS (reading a day relies on that): past it, the
     * rest is a session of its own.
     */
    fun mergeSessions(sessions: List<Session>, gapMs: Long = MERGE_GAP_MS): List<Session> =
        sessions.groupBy { it.pkg }.flatMap { (_, own) ->
            val out = mutableListOf<Session>()
            for (s in own.sortedBy { it.start }) {
                val prev = out.lastOrNull()
                when {
                    prev == null || s.start - prev.end > gapMs -> out += s
                    s.end <= prev.end -> {}   // inside it already
                    maxOf(prev.end, s.end) - prev.start <= MAX_SESSION_MS -> out[out.size - 1] = prev.copy(end = s.end)
                    else -> out += Session(s.pkg, maxOf(s.start, prev.end), s.end)
                }
            }
            out
        }.sortedWith(compareBy({ it.start }, { it.pkg }))

    /** How far back a first sync reads: as far as Android might still have. */
    const val HISTORY_BACKFILL_MS = 14 * 86_400_000L

    /** A sync reads from a little before where the last one left off, for events written late. */
    const val HISTORY_OVERLAP_MS = 5 * 60_000L

    /**
     * Where a sync reads events from: a little before the last one's safe
     * point, or as far back as Android keeps them on the first; never
     * further back than that.
     */
    fun syncFrom(safePoint: Long?, now: Long): Long {
        val floor = now - HISTORY_BACKFILL_MS
        return if (safePoint == null) floor else maxOf(floor, safePoint - HISTORY_OVERLAP_MS)
    }

    /** Whether a sync is due: none yet, or at least `gapMs` since the last (by time since boot, or a restart since). */
    fun syncDue(lastElapsed: Long, lastBoot: Int, nowElapsed: Long, boot: Int, gapMs: Long): Boolean =
        lastElapsed == 0L || lastBoot != boot || nowElapsed < lastElapsed || nowElapsed - lastElapsed >= gapMs

    /**
     * The wall clock was moved: Android moves its stored events with it, so
     * the history recorded since this boot (the only part a move within it
     * can be measured for) moves too. Returns (from, by): sessions starting
     * at or after `from` shift `by` ms; null when the clock didn't move.
     */
    fun historyShift(anchor: ClockAnchor?, wall: Long, elapsed: Long, boot: Int): Pair<Long, Long>? {
        val moved = clockMoved(anchor, wall, elapsed, boot)
        if (moved == 0L || anchor == null) return null
        return (anchor.wall - anchor.elapsed) to moved
    }

    /** A local day's start and end (ms): not always 24 hours apart (daylight saving), nor always at 00:00. */
    fun dayBounds(day: java.time.LocalDate, zone: java.time.ZoneId): Pair<Long, Long> =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The sessions cut to `from`..`to`, those outside it dropped. */
    fun clip(sessions: List<Session>, from: Long, to: Long): List<Session> =
        sessions.mapNotNull { s ->
            val a = maxOf(s.start, from)
            val b = minOf(s.end, to)
            if (b > a) Session(s.pkg, a, b) else null
        }

    /** Spans covered by any of the sessions, joined: two apps side by side count once. */
    private fun union(spans: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for ((a, b) in spans.sortedBy { it.first }) {
            val prev = out.lastOrNull()
            if (prev != null && a <= prev.second) out[out.size - 1] = prev.first to maxOf(prev.second, b)
            else out += a to b
        }
        return out
    }

    /** Screen time between `from` and `to`: time with any app on screen (split screen counts once). */
    fun screenTimeMs(sessions: List<Session>, from: Long, to: Long): Long =
        union(clip(sessions, from, to).map { it.start to it.end }).sumOf { it.second - it.first }

    /**
     * A day cut into buckets of `bucketMs`, for the chart: per bucket, the
     * share of it (0..1) spent in `limited` apps, and the share spent in
     * others while no limited one was on screen, so the two never add up to
     * more than the bucket. `known` buckets end where the data does (`upTo`,
     * now for today): the rest of the day hasn't happened, and the slice
     * going on is a share of its part so far.
     */
    data class Buckets(val limited: FloatArray, val other: FloatArray, val known: Int)

    fun buckets(sessions: List<Session>, from: Long, to: Long, bucketMs: Long, limited: Set<String>, upTo: Long): Buckets {
        val n = ((to - from + bucketMs - 1) / bucketMs).toInt()
        val lim = FloatArray(n)
        val oth = FloatArray(n)
        val day = clip(sessions, from, to)
        val limSpans = union(day.filter { it.pkg in limited }.map { it.start to it.end })
        val allSpans = union(day.map { it.start to it.end })
        fun spread(spans: List<Pair<Long, Long>>, into: FloatArray, sign: Float) {
            for ((a, b) in spans) {
                var i = ((a - from) / bucketMs).toInt()
                while (i < n) {
                    val bs = from + i * bucketMs
                    val be = minOf(bs + bucketMs, to)
                    if (bs >= b) break
                    val overlap = minOf(b, be) - maxOf(a, bs)
                    // The slice going on now is a share of what's happened of it so far.
                    val lived = if (upTo > bs && upTo < be) upTo - bs else be - bs
                    if (overlap > 0) into[i] = minOf(1f, into[i] + sign * overlap / lived.toFloat())
                    i++
                }
            }
        }
        spread(limSpans, lim, 1f)
        // Others: all screen time, less the limited apps' (which is inside it).
        spread(allSpans, oth, 1f)
        for (i in 0 until n) oth[i] = maxOf(0f, oth[i] - lim[i])
        val known = when {
            upTo >= to -> n
            upTo <= from -> 0
            else -> ((upTo - from + bucketMs - 1) / bucketMs).toInt()
        }
        return Buckets(lim, oth, known)
    }

    /** Each app's time between `from` and `to`, most first (apps side by side each count in full). */
    fun appTotals(sessions: List<Session>, from: Long, to: Long): List<Pair<String, Long>> =
        clip(sessions, from, to).groupBy { it.pkg }
            .map { (pkg, own) -> pkg to union(own.map { it.start to it.end }).sumOf { it.second - it.first } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })

    /** The busiest hour of a day: when it starts, and the screen time in it. */
    data class Peak(val start: Long, val ms: Long)

    /**
     * The hour of the day (on the clock: 9 to 10 PM) with the most screen
     * time, the earliest on a tie; null for a day with none. On a day that
     * goes back an hour, the repeated hour is two hours here.
     */
    fun peakHour(sessions: List<Session>, day: java.time.LocalDate, zone: java.time.ZoneId): Peak? {
        val (from, to) = dayBounds(day, zone)
        var best: Peak? = null
        var t = day.atStartOfDay(zone)
        while (t.toInstant().toEpochMilli() < to) {
            val next = t.plusHours(1)
            val a = maxOf(from, t.toInstant().toEpochMilli())
            val b = minOf(to, next.toInstant().toEpochMilli())
            val ms = screenTimeMs(sessions, a, b)
            if (ms > 0 && (best == null || ms > best.ms)) best = Peak(a, ms)
            t = next
        }
        return best
    }

    /** "Today", "Yesterday", or "Sun, 8 Nov" (with the year when it isn't this one). */
    fun dayTitle(day: java.time.LocalDate, today: java.time.LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern(
            if (day.year == today.year) "EEE, d MMM" else "EEE, d MMM yyyy", java.util.Locale.ENGLISH))
    }

    /**
     * Screen time in words, to the minute (cut: never more than was used):
     * "2 hours 5 minutes", "45 minutes", "Less than a minute", "0 minutes".
     * The screen-time card, the Usage page and the app picker.
     */
    fun fmtScreenTime(ms: Long): String {
        if (ms <= 0) return "0 minutes"
        if (ms < 60_000) return "Less than a minute"
        val min = ms / 60_000
        return listOfNotNull((min / 60).takeIf { it > 0 }?.let { count(it, "hour") },
            (min % 60).takeIf { it > 0 }?.let { count(it, "minute") }).joinToString(" ")
    }

    /** "9 PM" for the hour starting at `start`. */
    fun fmtHour(start: Long, zone: java.time.ZoneId): String =
        java.time.Instant.ofEpochMilli(start).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("h a", java.util.Locale.ENGLISH))

    /** What the home screen's top card shows: the stats, a card asking for usage access, or nothing (asked, and dismissed). */
    enum class UsageCard { STATS, ASK, NONE }

    fun usageCard(granted: Boolean, dismissed: Boolean): UsageCard = when {
        granted -> UsageCard.STATS
        dismissed -> UsageCard.NONE
        else -> UsageCard.ASK
    }

    /** The file the history lives in, in the app's databases; left out of Auto Backup (single device, and it grows past the quota). */
    const val HISTORY_DB = "history.db"

    /**
     * Screen time leaves out the apps the user said not to count (maps while
     * driving, an app they study with): their time stays in the history and
     * on their own page, but not in the day's total, chart or busiest hour.
     */
    fun counted(sessions: List<Session>, ignored: Set<String>): List<Session> =
        if (ignored.isEmpty()) sessions else sessions.filter { it.pkg !in ignored }

    // ---- the Usage page's list: search, sort, and the apps with no use ----

    /** An app on the Usage page: its name and its time on the day showing. */
    data class AppUse(val pkg: String, val label: String, val ms: Long)

    enum class UsageSort { TIME, NAME }

    /** An app whose name has the search in it, any case; an empty search finds all. */
    fun matches(label: String, query: String): Boolean =
        query.isBlank() || label.lowercase().contains(query.trim().lowercase())

    /** By time (ties by name) or by name (ties by package), either way round. */
    fun sortApps(apps: List<AppUse>, sort: UsageSort, descending: Boolean): List<AppUse> {
        val byName = compareBy<AppUse>({ it.label.lowercase() }, { it.pkg })
        val key = when (sort) {
            UsageSort.TIME -> if (descending) compareByDescending<AppUse> { it.ms } else compareBy { it.ms }
            UsageSort.NAME -> if (descending) byName.reversed() else byName
        }
        return apps.sortedWith(if (sort == UsageSort.TIME) key.then(byName) else key)
    }

    /** The installed apps (package to name) with no time on the day: by name, each once. */
    fun noUsage(installed: Map<String, String>, used: Set<String>): List<AppUse> =
        installed.filterKeys { it !in used }.map { (pkg, label) -> AppUse(pkg, label, 0) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))

    /**
     * A chart's minute marks for a peak of `peakMin` minutes: round steps
     * (1, 2, 5, 10, 15, 20 or 30), the fewest that still give at most three
     * marks, the last at or above the peak and never past `capMin` (a
     * slice can't hold more than its own length). Empty for an empty chart.
     */
    fun niceTicks(peakMin: Float, capMin: Int): List<Int> {
        if (peakMin <= 0f) return emptyList()
        val peak = minOf(peakMin, capMin.toFloat())
        for (step in listOf(1, 2, 5, 10, 15, 20, 30)) {
            val top = (kotlin.math.ceil(peak / step).toInt() * step).coerceAtLeast(step)
            if (top / step <= 3 && top <= capMin) return (step..top step step).toList()
        }
        return listOf(capMin)
    }

    /**
     * A slice of the day: "9:15 to 9:30 PM", or "11:45 AM to 12:00 PM" across
     * noon; the one going on now, "11:15 PM to now".
     */
    fun sliceRange(start: Long, end: Long, zone: java.time.ZoneId, ongoing: Boolean = false): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)
        val a = java.time.Instant.ofEpochMilli(start).atZone(zone).format(f)
        if (ongoing) return "$a to now"
        val b = java.time.Instant.ofEpochMilli(end).atZone(zone).format(f)
        return if (a.takeLast(2) == b.takeLast(2)) "${a.dropLast(3)} to $b" else "$a to $b"
    }

    /** A minute mark: "5 min". */
    fun fmtTick(min: Int): String = "$min min"

    // ---- the calendar: screen time's date picker ----

    /**
     * A month as calendar rows of seven days, weeks starting on `firstDow`:
     * null for the cells before the 1st and after the last day.
     */
    fun monthGrid(month: java.time.YearMonth, firstDow: java.time.DayOfWeek): List<List<java.time.LocalDate?>> {
        val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
        val cells = List<java.time.LocalDate?>(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        return cells.chunked(7).map { it + List(7 - it.size) { null } }
    }

    /** A day the calendar offers: from the history's first day (today, with no history yet) to today. */
    fun pickable(day: java.time.LocalDate, firstDay: java.time.LocalDate?, today: java.time.LocalDate): Boolean =
        !day.isAfter(today) && !day.isBefore(minOf(firstDay ?: today, today))

    /** A month the calendar can step to: one with a day to pick. */
    fun monthPickable(month: java.time.YearMonth, firstDay: java.time.LocalDate?, today: java.time.LocalDate): Boolean =
        !month.isAfter(java.time.YearMonth.from(today)) &&
            !month.isBefore(java.time.YearMonth.from(minOf(firstDay ?: today, today)))

    // ---- one app's patterns: its own page ----

    /** How far back an app's stats look: the last week, the last 30 days, or the whole history. */
    enum class StatsRange(val days: Long?, val label: String) {
        WEEK(7, "7 days"), MONTH(30, "30 days"), ALL(null, "All time")
    }

    /**
     * The days a range covers, ending today: its last `days` days, but never
     * before the history starts (a week-old history's "30 days" is a week).
     */
    fun rangeDays(range: StatsRange, firstDay: java.time.LocalDate?, today: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
        val start = minOf(firstDay ?: today, today)
        val want = range.days?.let { today.minusDays(it - 1) } ?: start
        return maxOf(want, start) to today
    }

    /**
     * An app over a range of days. `avgMs`: its time per day, today (so far)
     * counted as a day. `topDay`: the day it was used most, the latest on a
     * tie (null if never used). `hours`: its time in each hour of the clock,
     * 0 (midnight) to 23, all days together; `peakHour` the biggest of them
     * (the earliest on a tie).
     * `opens`: its sessions that began in the range, `avgSessionMs` their
     * average; `longestMs` the longest session in it.
     */
    data class AppStats(
        val days: Int, val totalMs: Long, val avgMs: Long,
        val topDay: java.time.LocalDate?, val topDayMs: Long,
        val hours: LongArray, val peakHour: Int?,
        val opens: Int, val avgSessionMs: Long, val longestMs: Long,
    )

    fun appStats(
        sessions: List<Session>, first: java.time.LocalDate, last: java.time.LocalDate,
        zone: java.time.ZoneId, upTo: Long,
    ): AppStats {
        val from = dayBounds(first, zone).first
        val to = minOf(dayBounds(last, zone).second, upTo)
        val spans = union(clip(sessions, from, to).map { it.start to it.end })
        val perDay = sortedMapOf<java.time.LocalDate, Long>()
        val hours = LongArray(24)
        for ((a, b) in spans) {
            // Walk the span an hour of the clock at a time (a day that skips or repeats an hour included).
            var t = a
            while (t < b) {
                val at = java.time.Instant.ofEpochMilli(t).atZone(zone)
                val next = minOf(b, at.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli())
                hours[at.hour] += next - t
                perDay.merge(at.toLocalDate(), next - t, Long::plus)
                t = next
            }
        }
        val total = spans.sumOf { it.second - it.first }
        val days = (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1).toInt().coerceAtLeast(1)
        val top = perDay.entries.filter { it.value > 0 }.maxWithOrNull(compareBy({ it.value }, { it.key }))
        val peak = (0..23).filter { hours[it] > 0 }.maxWithOrNull(compareBy({ hours[it] }, { -it }))
        val own = clip(sessions, from, to)
        // Opened in the range: a session going on at its start was opened before it,
        // and isn't in the time per open either.
        val opened = sessions.filter { it.start in from until to }
        val openedMs = clip(opened, from, to).sumOf { it.end - it.start }
        return AppStats(
            days, total, total / days, top?.key, top?.value ?: 0, hours, peak,
            opened.size, if (opened.isNotEmpty()) openedMs / opened.size else 0, own.maxOfOrNull { it.end - it.start } ?: 0,
        )
    }

    /** "9 PM to 10 PM": the clock hour starting at `hour` (0 to 23). */
    fun hourRange(hour: Int): String {
        fun h(x: Int) = when (val m = x % 24) { 0 -> "12 AM"; 12 -> "12 PM"; else -> if (m < 12) "$m AM" else "${m - 12} PM" }
        return "${h(hour)} to ${h(hour + 1)}"
    }

    /** How long an app stays open, on average: "About 4 minutes each", "Under a minute each". */
    fun perOpenText(ms: Long): String =
        if (ms < 60_000) "Under a minute each" else "About ${fmtScreenTime(ms)} each"

    // ---- persistence: JSON blobs in SharedPreferences ----
    //
    //   rules:      {"com.instagram.android": {"allow": 5, "window": 120}, ...}
    //   usage:      {"com.instagram.android": [[startMs, endMs], ...], ...}
    //   feedBlocks: ["com.google.android.youtube"]  (apps whose feed is blocked)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("appblock", Context.MODE_PRIVATE)

    fun onboardingSeen(ctx: Context) = prefs(ctx).getBoolean("onboarded", false)

    // User tapped ✕ on the "notifications are off" reminder: never show it again.
    fun notifReminderDismissed(ctx: Context) =
        prefs(ctx).getBoolean("notifReminderDismissed", false)

    fun setNotifReminderDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("notifReminderDismissed", true).apply()
    }

    // Same, for the app picker's "grant usage access" hint.
    fun usageReminderDismissed(ctx: Context) =
        prefs(ctx).getBoolean("usageReminderDismissed", false)

    fun setUsageReminderDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("usageReminderDismissed", true).apply()
    }

    // Same, for the home screen's "allow usage access for your screen time" card.
    fun usageCardDismissed(ctx: Context) =
        prefs(ctx).getBoolean("usageCardDismissed", false)

    fun setUsageCardDismissed(ctx: Context) {
        prefs(ctx).edit().putBoolean("usageCardDismissed", true).apply()
    }

    // Apps left out of screen time (counted()): the user's choice, on each app's page.
    fun screenTimeIgnored(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("screenTimeIgnored", null)?.toSet() ?: emptySet()

    fun setCountsInScreenTime(ctx: Context, pkg: String, counts: Boolean) {
        val now = screenTimeIgnored(ctx)
        prefs(ctx).edit().putStringSet("screenTimeIgnored", if (counts) now - pkg else now + pkg).apply()
    }

    fun setOnboardingSeen(ctx: Context) {
        prefs(ctx).edit().putBoolean("onboarded", true).apply()
    }

    fun loadRules(ctx: Context): Map<String, Rule> {
        val json = JSONObject(prefs(ctx).getString("rules", "{}")!!)
        val out = mutableMapOf<String, Rule>()
        for (pkg in json.keys()) {
            val r = json.getJSONObject(pkg)
            out[pkg] = Rule(r.getInt("allow"), r.getInt("window"))
        }
        return out
    }

    fun saveRules(ctx: Context, rules: Map<String, Rule>) {
        val json = JSONObject()
        for ((pkg, r) in rules) {
            json.put(pkg, JSONObject().put("allow", r.allowMin).put("window", r.windowMin))
        }
        prefs(ctx).edit().putString("rules", json.toString()).apply()
    }

    private fun loadUsage(ctx: Context) =
        JSONObject(prefs(ctx).getString("usage", "{}")!!)

    /** The recorded usage intervals for `pkg`, as [start, end] millisecond pairs. */
    fun loadIntervals(ctx: Context, pkg: String): List<Pair<Long, Long>> {
        val arr = loadUsage(ctx).optJSONArray(pkg) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val iv = arr.getJSONArray(i)
            iv.getLong(0) to iv.getLong(1)
        }
    }

    /**
     * The usage log after recording [start, end]: entries that ended before
     * `cutoff` dropped, and the new one merged into the last when it picks up
     * where that one stopped (sessionSpan). The service flushes a running
     * session every few seconds, so without merging a week-long window would hold
     * thousands of entries. A session that starts before the last entry
     * (the clock set back without Moly seeing it) is an entry of its
     * own, never swallowed by that one.
     */
    fun appendUsage(log: List<Pair<Long, Long>>, start: Long, end: Long, cutoff: Long):
        List<Pair<Long, Long>> {
        val kept = log.filter { it.second > cutoff }.toMutableList()
        val last = kept.lastOrNull()
        if (last != null && start >= last.first && start <= last.second)
            kept[kept.lastIndex] = last.first to maxOf(last.second, end)
        else kept += start to end
        return kept
    }

    private fun putIntervals(usage: JSONObject, pkg: String, log: List<Pair<Long, Long>>) {
        usage.put(pkg, JSONArray().apply {
            for ((s, e) in log) put(JSONArray().put(s).put(e))
        })
    }

    /** Record that `pkg` was used from `start` to `end`, dropping entries too old to matter. */
    fun addUsage(ctx: Context, pkg: String, start: Long, end: Long, windowMin: Int) {
        if (end <= start) return
        val all = loadUsage(ctx)
        val cutoff = System.currentTimeMillis() - windowMin * 60_000L
        putIntervals(all, pkg, appendUsage(loadIntervals(ctx, pkg), start, end, cutoff))
        prefs(ctx).edit().putString("usage", all.toString()).apply()
    }

    /** Every app's usage log shifted `by` ms, the clock having moved as much (shiftLog). */
    fun shiftUsage(ctx: Context, by: Long) {
        val all = loadUsage(ctx)
        for (pkg in all.keys().asSequence().toList()) putIntervals(all, pkg, shiftLog(loadIntervals(ctx, pkg), by))
        prefs(ctx).edit().putString("usage", all.toString()).commit()
    }

    // The clock anchor (ClockAnchor) lives in a file of its own ("clock"),
    // which Auto Backup leaves out like strict mode's: it describes this
    // phone's clocks since its last boot, meaningless on another phone.
    //
    //   {"wall": ms, "elapsed": ms, "boot": n}

    /** The file name Auto Backup is told to leave out ("clock.xml" on disk). */
    const val CLOCK_PREFS = "clock"

    fun loadClockAnchor(ctx: Context): ClockAnchor? = try {
        ctx.getSharedPreferences(CLOCK_PREFS, Context.MODE_PRIVATE).getString("anchor", null)
            ?.let { JSONObject(it) }?.let { ClockAnchor(it.getLong("wall"), it.getLong("elapsed"), it.getInt("boot")) }
    } catch (e: Exception) { null }

    /** Written at once (commit): the logs are shifted right after, and must never be shifted twice. */
    fun saveClockAnchor(ctx: Context, a: ClockAnchor) {
        ctx.getSharedPreferences(CLOCK_PREFS, Context.MODE_PRIVATE).edit().putString("anchor",
            JSONObject().put("wall", a.wall).put("elapsed", a.elapsed).put("boot", a.boot).toString()).commit()
    }

    /** The apps whose short-video feed the user blocked: `"feedBlocks": ["com.google.android.youtube"]`. */
    fun loadFeedBlocks(ctx: Context): Set<String> {
        val arr = JSONArray(prefs(ctx).getString("feedBlocks", "[]")!!)
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun setFeedBlocked(ctx: Context, pkg: String, blocked: Boolean) {
        val now = loadFeedBlocks(ctx).let { if (blocked) it + pkg else it - pkg }
        prefs(ctx).edit().putString("feedBlocks", JSONArray(now.sorted()).toString()).apply()
    }

    /** Remove an app's rule AND its usage log, so nothing orphaned stays behind. */
    fun removeApp(ctx: Context, pkg: String) {
        val rules = loadRules(ctx).toMutableMap()
        rules.remove(pkg)
        saveRules(ctx, rules)
        setFeedBlocked(ctx, pkg, false)
        val usage = loadUsage(ctx)
        usage.remove(pkg)
        prefs(ctx).edit().putString("usage", usage.toString()).apply()
    }

    // Strict mode lives in a file of its own ("strict"), which Auto Backup
    // leaves out (res/xml/backup_rules.xml, data_extraction_rules.xml): on
    // a new phone Moly isn't a device admin, so a restored "on" would be
    // a lie. One JSON blob:
    //
    //   {"on": true, "pending": false, "wayOut": "cooldown", "cooldownMin": 15,
    //    "pass": {"allow": 5, "window": 1440}, "cooldownEnds": 0,
    //    "passUses": [[startMs, endMs], ...], "passStarted": 0, "passInUse": 0}
    //
    // (Up to 2.4.0 it held a "clock" anchor of its own too, now the app's one: CLOCK_PREFS.)

    fun strictToJson(s: Strict): String = JSONObject().apply {
        put("on", s.on)
        put("pending", s.pending)
        put("wayOut", s.wayOut.name.lowercase())
        put("cooldownMin", s.cooldownMin)
        s.pass?.let { put("pass", JSONObject().put("allow", it.allowMin).put("window", it.windowMin)) }
        put("cooldownEnds", s.cooldownEnds)
        put("passUses", JSONArray().apply { for ((a, b) in s.passUses) put(JSONArray().put(a).put(b)) })
        put("passStarted", s.passStarted)
        put("passInUse", s.passInUse)
    }.toString()

    /** Strict mode from its JSON; off (the default) when there's none or it's unreadable. */
    fun strictFromJson(json: String?): Strict = try {
        val o = JSONObject(json!!)
        val uses = o.optJSONArray("passUses") ?: JSONArray()
        Strict(
            on = o.optBoolean("on"),
            pending = o.optBoolean("pending"),
            wayOut = if (o.optString("wayOut") == "plug") WayOut.PLUG else WayOut.COOLDOWN,
            cooldownMin = o.optInt("cooldownMin", 15),
            pass = o.optJSONObject("pass")?.let { Rule(it.getInt("allow"), it.getInt("window")) },
            cooldownEnds = o.optLong("cooldownEnds"),
            passUses = (0 until uses.length()).map { uses.getJSONArray(it).let { iv -> iv.getLong(0) to iv.getLong(1) } },
            passStarted = o.optLong("passStarted"),
            passInUse = o.optLong("passInUse"))
    } catch (e: Exception) { Strict() }

    /** The file name Auto Backup is told to leave out ("strict.xml" on disk). */
    const val STRICT_PREFS = "strict"

    fun loadStrict(ctx: Context): Strict =
        strictFromJson(ctx.getSharedPreferences(STRICT_PREFS, Context.MODE_PRIVATE).getString("state", null))

    /** Written at once (commit): a receiver or the service may read it straight after. */
    fun saveStrict(ctx: Context, s: Strict) {
        ctx.getSharedPreferences(STRICT_PREFS, Context.MODE_PRIVATE).edit().putString("state", strictToJson(s)).commit()
    }

    // Convenience wrappers joining persistence with the pure math above.

    fun usedMsInWindow(ctx: Context, pkg: String, windowMin: Int): Long =
        usedMs(loadIntervals(ctx, pkg), windowMin, System.currentTimeMillis())

    fun msUntilUnblocked(ctx: Context, pkg: String, rule: Rule): Long =
        msUntilUnblocked(loadIntervals(ctx, pkg), rule, System.currentTimeMillis())
}
