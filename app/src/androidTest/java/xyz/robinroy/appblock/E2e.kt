package xyz.robinroy.appblock

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
 * setting granted, ways to open apps and find things on screen, and seeding
 * of the usage log so a test can start "5 minutes into" a budget instead of
 * waiting it out.
 *
 * The tests run inside AppBlock's own process, so they read and write its
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

    const val WAIT = 10_000L

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val Int.min get() = this * 60_000L

    fun shell(cmd: String): String = device.executeShellCommand(cmd)

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
     * Every required setting on, notifications allowed, onboarding seen, the
     * given rules and nothing else (no usage, no leftover block wall), the
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
        for (app in listOf(CLOCK, PHONE)) shell("am force-stop $app")
        // Switch the blocker off before wiping: a session still open from the
        // last test would otherwise be written into the wiped storage a few
        // seconds later ("1 min used" in a test that never opened the app).
        // Switching it off writes the session now; the wipe then removes it.
        setBlocker(false)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        ctx.getSharedPreferences("appblock", Context.MODE_PRIVATE).edit().clear().commit()
        Storage.setOnboardingSeen(ctx)
        Storage.saveRules(ctx, rules)
        setBlocker(true)
        openHome()
        device.pressHome()
        device.waitForIdle()
    }

    /** Record that `pkg` was used from `startAgo` to `endAgo` before now. */
    fun seedUsage(pkg: String, startAgo: Long, endAgo: Long, windowMin: Int) {
        val now = System.currentTimeMillis()
        Storage.addUsage(ctx, pkg, now - startAgo, now - endAgo, windowMin)
    }

    /** AppBlock's home screen, freshly built from storage. */
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
        shell("am start -W --windowingMode 5 -n ${launcher.flattenToShortString()}")
        check(eventually { isOnScreen(pkg) }) { "$pkg didn't open as a pop-up" }
    }

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

    /** The task `pkg` runs in. */
    private fun taskId(pkg: String): Int =
        Regex("taskId=(\\d+): ${Regex.escape(pkg)}/").find(shell("am stack list"))
            ?.groupValues?.get(1)?.toInt() ?: throw AssertionError("$pkg has no task")

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

    /** Exact on-screen text, ignoring case: buttons render their labels in caps. */
    fun text(s: String): BySelector = By.text(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE))

    fun textContains(s: String): BySelector =
        By.text(Pattern.compile(".*" + Pattern.quote(s) + ".*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL))

    fun waitFor(selector: BySelector, timeout: Long = WAIT): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("not on screen after ${timeout}ms: $selector")

    fun appears(selector: BySelector, timeout: Long = WAIT): Boolean =
        device.wait(Until.hasObject(selector), timeout)

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
     * was, like Playwright's, and the device log beside it (the blocker
     * service's state is logged into it first). Gradle pulls both into
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
