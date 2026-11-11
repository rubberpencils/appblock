package xyz.robinroy.appblock

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.regex.Pattern

/**
 * Shared setup for the e2e tests: a clean app state with every required
 * setting granted (and the optional usage access), ways to open apps and find things on screen, and seeding
 * of the usage log so a test can start "5 minutes into" a budget instead of
 * waiting it out.
 *
 * The tests run inside Moly's own process, so they read and write its
 * storage directly; everything on screen goes through UI Automator, which
 * also sees other apps and the block wall drawn over them.
 */
object E2e {
    init {
        // UI Automator is an accessibility client too, and by default
        // switches every other accessibility service off while it runs:
        // the blocker included.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    }

    // Stock apps on the emulator images (local and CI) used as "blocked apps".
    const val CLOCK = "com.google.android.deskclock"
    const val PHONE = "com.google.android.dialer"   // opens without a Google sign-in (Calendar on 16 doesn't)
    // On the Google images too, and the app whose Shorts can be blocked.
    const val YOUTUBE = "com.google.android.youtube"
    // Not on the emulators (and it needs an account): the fixture plays it.
    const val INSTAGRAM = "com.instagram.android"
    // The e2e tests' stand-in for YouTube and Instagram (fixture/), with their players' view ids.
    const val FIXTURE = "xyz.robinroy.appblock.fixture"

    const val WAIT = 10_000L

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val Int.min get() = this * 60_000L

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

    /**
     * Keeps a picture of the screen as `name`.png, for showing a change's
     * screens to a person. Only when asked: run the tests with
     * `-Pandroid.testInstrumentationRunnerArguments.shots=1`, then
     * `adb pull /sdcard/Download/moly-shots`. A normal run takes none.
     */
    fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") == null) return
        device.waitForIdle()
        shell("mkdir -p /sdcard/Download/moly-shots")
        shell("screencap -p /sdcard/Download/moly-shots/$name.png")
    }

    /** For direct window queries; with the flag that leaves the blocker running (see init). */
    val uiAutomation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /**
     * Switch the blocker's accessibility service on or off, as the user
     * would in Settings > Accessibility, and wait until it has connected or
     * gone. Switching it off logs any open session.
     */
    fun setBlocker(on: Boolean) {
        if (on) {
            shell("settings put secure enabled_accessibility_services " +
                "${ctx.packageName}/${BlockerService::class.java.name}")
            shell("settings put secure accessibility_enabled 1")
        } else {
            shell("settings delete secure enabled_accessibility_services")
        }
        check(eventually { (BlockerService.instance != null) == on }) {
            "the blocker didn't switch ${if (on) "on" else "off"}"
        }
    }

    /**
     * Every required setting on, usage access and notifications allowed, onboarding seen, the
     * given rules and nothing else (no usage, no screen-time history, no leftover block wall), the
     * blocker running, and the launcher in front.
     */
    fun freshState(rules: Map<String, Rule> = emptyMap()) {
        val pkg = ctx.packageName
        // A just-booted emulator (CI cold-boots one) can put "<app> isn't
        // responding" over everything while the system catches up, hiding
        // the screen under test. Don't show such dialogs; wait out one that's up.
        shell("settings put global hide_error_dialogs 1")
        if (device.findObject(textContains("isn't responding")) != null) {
            device.findObject(text("Wait"))?.click()
        }
        shell("appops set $pkg GET_USAGE_STATS allow")
        shell("dumpsys deviceidle whitelist +$pkg")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.findObject(text("Go to home screen"))?.click()
        for (app in listOf(CLOCK, PHONE, FIXTURE)) shell("am force-stop $app")
        // Strict mode off and its device admin let go (from inside the app:
        // adb can't remove an admin that isn't test-only), so no test starts
        // locked in, and Gradle can uninstall the app after the run.
        Strict.resetForTests(ctx)
        shell("dumpsys battery reset")
        // Switch the blocker off before wiping: a session still open from the
        // last test would otherwise be written into the wiped storage a few
        // seconds later ("1 minute used" in a test that never opened the app).
        // Switching it off writes the session now; the wipe then removes it.
        setBlocker(false)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        ctx.getSharedPreferences("appblock", Context.MODE_PRIVATE).edit().clear().commit()
        // No past feedback, none sent today, and no fake server left from the last test.
        ctx.getSharedPreferences(Storage.FEEDBACK_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        Feedback.post = { _ -> throw AssertionError("this test sends feedback but didn't say what the server answers") }
        // An empty screen-time history, and no syncing by itself: tests seed what they need.
        History.autoSync = false
        History.resetForTests(ctx)
        Storage.setOnboardingSeen(ctx)
        Storage.saveRules(ctx, rules)
        Feeds.useForTests(null)
        BlockerService.closePipForTests(true)
        setBlocker(true)
        openHome()
        device.pressHome()
        device.waitForIdle()
    }

    /**
     * Strict mode on with this way out, as if chosen on its setup screen and
     * then Moly made a device admin (through adb here; the system's
     * Activate page is driven in StrictE2eTest.turningOn_goesThroughTheDeviceAdminPage).
     */
    fun strictOn(way: Storage.WayOut, cooldownMin: Int = 15, pass: Rule? = null) {
        Strict.choose(ctx, way, cooldownMin, pass)
        shell("dpm set-active-admin --user 0 ${ctx.packageName}/${StrictAdmin::class.java.name}")
        check(eventually { Storage.loadStrict(ctx).on }) { "strict mode didn't go on" }
    }

    // ---- the device's own state: its name, its saved Wi-Fi networks, its language ----
    //
    // Strict mode reads the system's Settings pages, so some tests need the
    // phone itself to say something there. Whatever a test changes with
    // these, it puts back in an E2e.Cleanup rule.

    /** Run `block` with the shell's permissions, like adb: writing system settings, saving a Wi-Fi network. */
    fun <T> asShell(block: () -> T): T {
        uiAutomation.adoptShellPermissionIdentity()
        try { return block() } finally { uiAutomation.dropShellPermissionIdentity() }
    }

    /** The phone's name, as About phone shows it ("Device name"); null when it was never set. */
    fun deviceName(): String? = android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")

    /** Name the phone, as the user would on About phone (any text, spaces included); null puts back "never set". */
    fun setDeviceName(name: String?) {
        if (name == null) shell("settings delete global device_name")
        else asShell { android.provider.Settings.Global.putString(ctx.contentResolver, "device_name", name) }
        check(eventually { deviceName() == name }) { "the device name didn't change to $name" }
    }

    private val wifi get() = ctx.getSystemService(android.net.wifi.WifiManager::class.java)

    /**
     * Save an open Wi-Fi network called `ssid`, as if the user had once
     * joined it: Settings lists it under Saved networks, in range or not,
     * Wi-Fi on or off. False where the phone won't have it (no Wi-Fi).
     */
    @Suppress("DEPRECATION")   // the one way to save a network without joining it
    fun saveWifiNetwork(ssid: String): Boolean = try {
        asShell {
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
            }
            wifi.addNetwork(config) >= 0
        }
    } catch (e: Exception) {
        Log.w("E2e", "couldn't save the Wi-Fi network $ssid: $e")
        false
    }

    /** Forget every saved Wi-Fi network called `ssid`. */
    @Suppress("DEPRECATION")
    fun forgetWifiNetwork(ssid: String) {
        try {
            asShell {
                wifi.configuredNetworks.filter { it.SSID == "\"$ssid\"" }.forEach { wifi.removeNetwork(it.networkId) }
            }
        } catch (e: Exception) { Log.w("E2e", "couldn't forget the Wi-Fi network $ssid: $e") }
    }

    /** The phone's languages, in the user's order ("en-US", "de-DE,en-US"). */
    fun languages(): String = ctx.resources.configuration.locales.toLanguageTags()

    /**
     * Switch the phone's language, as Settings > System > Languages does,
     * and wait until this process has heard. False where it can't be done
     * from a test (it goes through the system's own LocalePicker, which
     * isn't in the SDK).
     */
    fun setLanguages(tags: String): Boolean = try {
        asShell {
            Class.forName("com.android.internal.app.LocalePicker")
                .getMethod("updateLocales", android.os.LocaleList::class.java)
                .invoke(null, android.os.LocaleList.forLanguageTags(tags))
        }
        eventually { languages() == tags }
    } catch (e: Exception) {
        Log.w("E2e", "couldn't switch the language to $tags: $e")
        false
    }

    /**
     * What the blocker itself can read in `pkg`'s windows: each window's
     * title, then its texts. Not UI Automator's view of them: the blocker
     * is shown less of some screens than UI Automator is (on Android 14,
     * Settings' Device admin apps list reaches it without its rows).
     */
    fun blockerReads(pkg: String): List<Pair<String?, List<String>>> {
        val out = mutableListOf<Pair<String?, List<String>>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (w in BlockerService.instance?.windows.orEmpty()) {
                val root = try { w.root } catch (e: Exception) { null } ?: continue
                if (root.packageName?.toString() != pkg) continue
                val texts = mutableListOf<String>()
                val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
                var seen = 0
                while (stack.isNotEmpty() && seen++ < 3000) {
                    val node = stack.removeLast()
                    node.text?.let { texts += it.toString() }
                    node.contentDescription?.let { texts += it.toString() }
                    for (i in 0 until node.childCount) try { node.getChild(i)?.let(stack::add) } catch (e: Exception) {}
                }
                out += w.title?.toString() to texts
            }
        }
        return out
    }

    /** Record that `pkg` was used from `startAgo` to `endAgo` before now. */
    fun seedUsage(pkg: String, startAgo: Long, endAgo: Long, windowMin: Int) {
        val now = System.currentTimeMillis()
        Storage.addUsage(ctx, pkg, now - startAgo, now - endAgo, windowMin)
    }

    /** Moly's home screen, freshly built from storage. */
    fun openHome() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(text("Block an app")), WAIT)) { "home screen didn't open" }
    }

    /** Launch another app the way its launcher icon would, and wait for it to be in front. */
    fun openApp(pkg: String) {
        // From the shell (monkey silently doesn't launch from a test).
        val launcher = ctx.packageManager.getLaunchIntentForPackage(pkg)!!.component!!
        shell("am start -W -n ${launcher.flattenToShortString()}")
        check(device.wait(Until.hasObject(By.pkg(pkg)), WAIT)) { "$pkg didn't open" }
    }

    /**
     * Open `pkg` beside the app in front, in split screen (it takes the
     * bottom half). Returns once its window is in its half on screen: the
     * window manager calls the task visible as the move starts, before the
     * windows settle, and a test that pulls down the shade by then hides
     * the split from the blocker until the shade closes.
     */
    fun splitScreenWith(pkg: String) {
        shell("dumpsys activity service SystemUIService WMShell splitscreen moveToSideStage ${taskId(pkg)} 1")
        check(eventually {
            device.findObject(By.pkg(pkg))?.let { windowBounds(it).top > device.displayHeight / 3 } ?: false
        }) { "$pkg didn't open in split screen" }
    }

    /**
     * Open `pkg` in a floating pop-up window over what's on screen (the
     * emulator's "freeform" windows; OxygenOS and others call theirs pop-ups).
     */
    fun openAsPopUp(pkg: String) {
        shell("settings put global enable_freeform_support 1")
        val launcher = ctx.packageManager.getLaunchIntentForPackage(pkg)!!.component!!
        // The window manager hears of the setting through a ContentObserver on
        // its display thread, after `settings put` has returned, and nothing in
        // dumpsys shows when it has. A launch that gets there first is quietly
        // made full screen (on a busy CI emulator, runs 39107836672 and
        // 39951486366), so ask the window manager what it made, and try again.
        var tries = 0
        val popUp = eventually {
            // A task left from an earlier test would come back as it was (full
            // screen), not as a pop-up, so it goes first; so does a full-screen try.
            taskIdOrNull(pkg)?.let { id ->
                shell("am stack remove $id")
                check(eventually { taskIdOrNull(pkg) == null }) { "$pkg's task $id wasn't removed" }
            }
            tries++
            shell("am start -W --windowingMode 5 -n ${launcher.flattenToShortString()}")
            val mode = windowingMode(pkg)
            if (mode != "freeform") Log.w("E2e", "$pkg came up $mode (try $tries): freeform not in effect yet")
            mode == "freeform"
        }
        check(popUp) { "$pkg came up full screen $tries times: are pop-ups supported?" }
        check(eventually {
            isOnScreen(pkg) &&
                (device.findObject(By.pkg(pkg))?.let { windowBounds(it).width() < device.displayWidth } ?: false)
        }) { "$pkg didn't open as a pop-up" }
    }

    /**
     * Open the fixture's video (fixture/, like YouTube's watch page) and
     * wait until it plays: going home then shrinks it into picture-in-picture.
     */
    fun playVideo() {
        shell("am start -W -n $FIXTURE/.VideoActivity")
        waitFor(By.pkg(FIXTURE).text("Playing"))
    }

    /** The text on the block wall over `pkg`, read from the blocker itself (BlockerService.wallTextForTests), or null without one. */
    fun wallText(pkg: String): List<String>? {
        var text: List<String>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = BlockerService.instance?.wallTextForTests(pkg)
        }
        return text
    }

    /** Whether `pkg` is in picture-in-picture (the mini player), per the window manager. */
    fun inPip(pkg: String): Boolean = windowingMode(pkg) == "pinned"

    /** Resize `pkg`'s pop-up to `widthDp` x `heightDp`, in the top right corner. */
    fun resizePopUp(pkg: String, widthDp: Int, heightDp: Int) {
        val density = ctx.resources.displayMetrics.density
        val right = device.displayWidth - (16 * density).toInt()
        val top = (120 * density).toInt()
        val width = (widthDp * density).toInt()
        shell("am task resize ${taskId(pkg)} ${right - width} $top $right ${top + (heightDp * density).toInt()}")
        check(eventually {
            device.findObject(By.pkg(pkg))?.let { windowBounds(it).width() <= width + 1 } ?: false
        }) { "$pkg's pop-up didn't resize" }
    }

    /**
     * Close `pkg`'s task, so its next page starts a new one. A task keeps
     * the intent it was first started for, whatever page it shows now, and
     * Android answers that same intent again by bringing the task forward
     * as it is, opening nothing.
     */
    fun closeTask(pkg: String) {
        check(eventually {
            val id = taskIdOrNull(pkg)
            if (id != null) shell("am stack remove $id")
            id == null
        }) { "$pkg's task wasn't closed" }
    }

    /** The task `pkg` runs in. */
    private fun taskId(pkg: String): Int = taskIdOrNull(pkg) ?: throw AssertionError("$pkg has no task")

    private fun taskIdOrNull(pkg: String): Int? =
        Regex("taskId=(\\d+): ${Regex.escape(pkg)}/").find(shell("am stack list"))
            ?.groupValues?.get(1)?.toInt()

    /** The window manager's windowing mode for `pkg`'s task ("freeform", "fullscreen", ...), or null without one. */
    private fun windowingMode(pkg: String): String? =
        // One block per root task: its configuration (with the mode), then its tasks.
        shell("am stack list").split(Regex("\n\\s*\n"))
            .firstOrNull { Regex("taskId=\\d+: ${Regex.escape(pkg)}/").containsMatchIn(it) }
            ?.let { Regex("mWindowingMode=(\\w+)").find(it)?.groupValues?.get(1) }

    /** Whether `pkg` has a visible window, per the window manager. */
    fun isOnScreen(pkg: String): Boolean =
        shell("am stack list").lines().any { it.contains(Regex("taskId=\\d+: ${Regex.escape(pkg)}/")) && "visible=true" in it }

    /**
     * Whether `condition` holds within `timeout`, checked every quarter
     * second. A check that reads the screen as it changes (a window
     * replaced mid-read) just counts as not yet.
     */
    fun eventually(timeout: Long = WAIT, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            if (try { condition() } catch (e: StaleObjectException) { false }) return true
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(250)
        }
    }

    /** The bounds of the whole window `obj` sits in (e.g. the block wall). */
    fun windowBounds(obj: UiObject2): android.graphics.Rect {
        var root = obj
        while (root.parent != null) root = root.parent
        return root.visibleBounds
    }

    /**
     * The launcher's package. Not device.launcherPackageName: on the emulator
     * images that names Settings' fallback home (a placeholder shown only
     * while the real launcher starts).
     */
    fun homeScreenPackage(): String =
        shell("cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.HOME").trim().lines().last().substringBefore('/')

    /**
     * `s` as a pattern, a space matching a no-break space too: the app keeps
     * some phrases on one line with them (Storage.phrases), and they look the same.
     */
    private fun spaced(s: String) = s.split(' ').joinToString("[ \u00A0]") { Pattern.quote(it) }

    /** Exact on-screen text, ignoring case: buttons render their labels in caps. */
    fun text(s: String): BySelector = By.text(Pattern.compile(spaced(s), Pattern.CASE_INSENSITIVE))

    fun textContains(s: String): BySelector =
        By.text(Pattern.compile(".*" + spaced(s) + ".*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL))

    fun waitFor(selector: BySelector, timeout: Long = WAIT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("not on screen after ${timeout}ms: $selector")

    /**
     * Like waitFor, but looks at the screen afresh each time: UI Automator
     * keeps a cache of what's on screen, and late in a long run it can miss
     * a text that changed in place (the screen shows the new one, the cache
     * the old). For text that changes after something loads.
     */
    fun waitForFresh(selector: BySelector, timeout: Long = WAIT): UiObject2 {
        val until = System.currentTimeMillis() + timeout
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            device.findObject(selector)?.let { return it }
            if (System.currentTimeMillis() > until) throw AssertionError("not on screen after ${timeout}ms: $selector")
            Thread.sleep(250)
        }
    }

    /**
     * The object once it has stopped moving: a sheet's buttons slide up with
     * it, and a tap found mid-slide lands where the button was, maybe on the
     * dimmed page around the sheet, which closes it.
     */
    fun waitStill(selector: BySelector, timeout: Long = WAIT): UiObject2 {
        val until = System.currentTimeMillis() + timeout
        var last: android.graphics.Rect? = null
        while (System.currentTimeMillis() < until) {
            val now = try { device.findObject(selector)?.visibleBounds } catch (e: StaleObjectException) { null }
            if (now != null && now == last) return waitFor(selector)
            last = now
            Thread.sleep(250)
        }
        throw AssertionError("not still on screen after ${timeout}ms: $selector")
    }

    fun appears(selector: BySelector, timeout: Long = WAIT): Boolean =
        device.wait(Until.hasObject(selector), timeout)

    /**
     * Like waitForFresh, scrolling the page down while it isn't there: for
     * what can sit below the fold, like a row far down a long list.
     */
    fun scrollTo(selector: BySelector, timeout: Long = WAIT): UiObject2 {
        val until = System.currentTimeMillis() + timeout
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            device.findObject(selector)?.let { return it }
            if (System.currentTimeMillis() > until) throw AssertionError("not on screen after ${timeout}ms, scrolled: $selector")
            try { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f) } catch (e: StaleObjectException) {}
            Thread.sleep(250)
        }
    }

    // ---- the limit dialog ----

    /** Press OK on the limit dialog and wait for it to close. */
    fun pressOk() {
        waitFor(text("OK")).click()
        check(device.wait(Until.gone(text("Set limit")), WAIT)) { "the limit dialog didn't close" }
    }

    /** The Allow and in-any number fields, top to bottom. */
    fun limitFields(): List<UiObject2> {
        waitFor(By.clazz(EditText::class.java))
        return device.findObjects(By.clazz(EditText::class.java)).sortedBy { it.visibleBounds.top }
    }

    /**
     * JUnit rule: a failing test leaves a screenshot of the screen as it
     * was, like Playwright's, and the device log beside it (the system's
     * accessibility state, whether the blocker is bound and which windows
     * it sees, is logged into it first). Gradle pulls both into
     * app/build/outputs/connected_android_test_additional_output/, and CI
     * uploads them with the report.
     *
     * Cleanup that leaves the screen (pressing Home) must not be an @After:
     * JUnit runs those before any rule sees the failure, so the screenshot
     * would show the launcher. Use [Cleanup] ordered outside this rule.
     */
    class ScreenshotOnFailure : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
            val name = "${description.testClass.simpleName}.${description.methodName}"
            // From the shell: it can write the output directory, the app can't.
            shell("mkdir -p $dir")
            shell("screencap -p $dir/$name.png")
            // What UI Automator saw, beside what was on screen: they can differ.
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    device.dumpWindowHierarchy(out)
                    Log.i("E2eFailure", "window hierarchy:\n$out")
                }
            } catch (e: Exception) { Log.w("E2eFailure", "no window hierarchy: $e") }
            shell("dumpsys accessibility").lines()
                .forEach { Log.i("E2eFailure", it) }
            shell("logcat -d -v time -f $dir/$name.logcat.txt")
        }
    }

    /** JUnit rule: `block` runs after each test, after [ScreenshotOnFailure] (give it a lower `order`). */
    class Cleanup(private val block: () -> Unit) : ExternalResource() {
        override fun after() = block()
    }
}
