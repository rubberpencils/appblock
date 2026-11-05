package xyz.robinroy.appblock

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.YOUTUBE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.eventually
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.limitFields
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.pressOk
import xyz.robinroy.appblock.E2e.seedUsage
import xyz.robinroy.appblock.E2e.setBlocker
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.strictOn
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import xyz.robinroy.appblock.E2e.wallText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Strict mode, end to end: turned on through the system's device admin
 * page, the wall over the system pages that could switch the blocker off
 * or uninstall AppBlock (and not over the rest of Settings), limits that
 * only get stricter, and every way out: the cooldown, plugging in, the
 * emergency pass that opens the blocked apps without ending it, adb, and
 * the admin found switched off (safe mode, and back from it).
 */
@RunWith(AndroidJUnit4::class)
class StrictE2eTest {

    // Cleanup is the outer rule, so a failure's screenshot is taken first.
    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        // Strict mode must never outlive a test: the next one, and Gradle's
        // uninstall after the run, would find AppBlock locked in.
        Strict.resetForTests(ctx)
        shell("dumpsys battery reset")
        device.findObject(text("Go to home screen"))?.click()
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val settings = "com.android.settings"

    /** The package that shows `action`'s page (Settings, the package installer...). */
    private fun handler(intent: Intent): String =
        ctx.packageManager.resolveActivity(intent, 0)!!.activityInfo.packageName

    private fun openPage(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun strictWallOver(pkg: String) = eventually { wallText(pkg)?.any { it.startsWith("Strict mode") } == true }

    private val appInfo get() = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${ctx.packageName}"))

    @Test
    fun turningOn_goesThroughTheDeviceAdminPage() {
        freshState()
        openHome()
        waitFor(By.desc("Strict mode is off")).click()
        waitFor(text("Choose your way out."))
        waitFor(By.desc("Cooldown")).click()
        waitFor(text("Turn on strict mode")).click()
        waitFor(text("One more step"))
        waitFor(text("Open settings")).click()

        // Android's own page: AppBlock asks for no admin powers.
        waitFor(text("Activate this device admin app")).click()

        waitFor(By.desc("Strict mode is on"))
        assertTrue(Strict.adminActive(ctx))
        val s = Storage.loadStrict(ctx)
        assertTrue(s.on)
        assertEquals(Storage.WayOut.COOLDOWN, s.wayOut)
        assertEquals(15, s.cooldownMin)
    }

    @Test
    fun backingOutOfTheDeviceAdminPage_leavesItOff() {
        freshState()
        openHome()
        waitFor(By.desc("Strict mode is off")).click()
        waitFor(By.desc("Plug in to unlock")).click()
        waitFor(text("Turn on strict mode")).click()
        waitFor(text("Open settings")).click()
        waitFor(text("Activate this device admin app"))
        device.pressBack()

        waitFor(text("Turn on strict mode"))
        assertFalse(Storage.loadStrict(ctx).on)
        assertFalse(Storage.loadStrict(ctx).pending)
        assertFalse(Strict.adminActive(ctx))
    }

    @Test
    fun systemPagesNamingAppBlock_areWalled_theRestOfSettingsIsNot() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)

        // AppBlock's App info: force stop, clear storage, uninstall.
        openPage(appInfo)
        assertTrue("App info isn't walled", strictWallOver(handler(appInfo)))
        device.pressHome()

        // Accessibility, where the blocker is switched off.
        val accessibility = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        openPage(accessibility)
        assertTrue("Accessibility isn't walled", strictWallOver(handler(accessibility)))
        device.pressHome()

        // The uninstall dialog, in the package installer: opened from the
        // shell, as the launcher would (an app needs REQUEST_DELETE_PACKAGES).
        val uninstall = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${ctx.packageName}"))
        shell("am start -a android.intent.action.DELETE -d package:${ctx.packageName}")
        assertTrue("the uninstall dialog isn't walled", strictWallOver(handler(uninstall)))
        waitFor(text("Go back")).click()
        device.pressHome()

        // Wi-Fi never names AppBlock: Settings stays usable for it.
        openPage(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        assertTrue(appears(By.pkg(settings)))
        Thread.sleep(1500)   // longer than the wall ever takes (it went up in ~150 ms above)
        assertEquals(null, wallText(settings))
    }

    @Test
    fun theWallGoesWhenStrictModeEnds() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)
        openPage(appInfo)
        assertTrue(strictWallOver(settings))

        Strict.end(ctx, Strict.Ending.ADB)

        assertTrue("the wall stayed", eventually { wallText(settings) == null })
    }

    @Test
    fun endingTwiceInARow_doesNotCrash() {
        // A double tap on Turn off, or the adb command beside the admin's
        // own "switched off" callback: the second ending comes while
        // Android is still removing the admin from the first.
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)

        Strict.end(ctx, Strict.Ending.ADB)
        Strict.end(ctx, Strict.Ending.ADB)

        assertFalse(Storage.loadStrict(ctx).on)
        assertTrue("the admin wasn't let go", eventually { !Strict.adminActive(ctx) })
    }

    @Test
    fun androidRefusesToUninstallAppBlock() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)
        // What makes Android refuse: an active admin in the package. (Not
        // tried for real: these tests run inside AppBlock.)
        val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        assertTrue(dpm.isAdminActive(Strict.admin(ctx)))
    }

    @Test
    fun limitsOnlyGetStricter() {
        freshState(mapOf(CLOCK to Rule(allowMin = 5, windowMin = 120)))
        strictOn(Storage.WayOut.COOLDOWN)
        openHome()
        waitFor(By.desc("Set limit for Clock")).click()
        waitFor(text("Set limit"))

        // Remove would give all the time back.
        assertFalse(waitFor(text("Remove")).isEnabled)

        // More time: refused, and said why.
        val (allow, _) = limitFields()
        allow.text = "10"
        waitFor(textContains("this limit can get stricter, not looser"))
        assertFalse(waitFor(text("OK")).isEnabled)

        // Less time: fine.
        limitFields()[0].text = "3"
        assertTrue(device.wait(Until.gone(textContains("stricter, not looser")), E2e.WAIT))
        pressOk()
        assertEquals(Rule(3, 120), Storage.loadRules(ctx)[CLOCK])
    }

    @Test
    fun aBlockedFeedStaysBlocked() {
        freshState(mapOf(YOUTUBE to Rule(allowMin = 30, windowMin = 240)))
        Storage.setFeedBlocked(ctx, YOUTUBE, true)
        strictOn(Storage.WayOut.COOLDOWN)
        openHome()

        waitFor(text("Locked by strict mode")).click()
        Thread.sleep(500)
        assertTrue(YOUTUBE in Storage.loadFeedBlocks(ctx))
    }

    @Test
    fun cooldown_startsAndCancels_thenUnlocksTurnOffWhenItRunsOut() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN, cooldownMin = 15)
        openHome()
        waitFor(By.desc("Strict mode is on")).click()
        waitFor(text("Want out? You'll have to wait."))

        waitFor(text("Stop strict mode")).click()
        waitFor(text("Hang tight. Still locked."))
        assertTrue(Storage.coolingDown(Storage.loadStrict(ctx)))

        waitFor(text("Never mind, keep it on")).click()
        waitFor(text("Want out? You'll have to wait."))
        assertFalse(Storage.coolingDown(Storage.loadStrict(ctx)))

        // Started again, with 3 seconds left of it, and the page left: the
        // blocker notices it run out on time. Strict mode stays on.
        waitFor(text("Stop strict mode")).click()
        waitFor(text("Hang tight. Still locked."))
        runsOutIn3s()
        assertEquals("Turn off unlocks soon", cooldownNotification())
        device.pressHome()
        assertTrue("no Cooldown over notification", eventually(15_000) { cooldownNotification() == "Cooldown over" })
        assertTrue(Storage.loadStrict(ctx).on)
        assertTrue(Strict.adminActive(ctx))

        // Changed their mind: Keep it on locks it again.
        openHome()
        waitFor(By.desc("Strict mode is on")).click()
        waitFor(text("Cooldown over. Turn off is unlocked."))
        waitFor(text("Keep strict mode on")).click()
        waitFor(text("Want out? You'll have to wait."))
        assertFalse(Storage.coolingDown(Storage.loadStrict(ctx)))
        assertTrue("the cooldown's notification stayed", eventually { cooldownNotification() == null })

        // Once more, on this page: it unlocks as it runs out, and Turn off ends it.
        waitFor(text("Stop strict mode")).click()
        waitFor(text("Hang tight. Still locked."))
        runsOutIn3s()
        waitFor(text("Cooldown over. Turn off is unlocked."), 15_000)
        assertTrue(Storage.loadStrict(ctx).on)
        waitFor(text("Turn off strict mode")).click()
        waitFor(text("Strict mode is off."))
        assertFalse(Storage.loadStrict(ctx).on)
        assertTrue("the admin wasn't let go", eventually { !Strict.adminActive(ctx) })
        assertTrue("the cooldown's notification stayed", eventually { cooldownNotification() == null })
    }

    /** The title of the cooldown's notification up now, counting or run out; null with none. */
    private fun cooldownNotification(): String? =
        ctx.getSystemService(NotificationManager::class.java).activeNotifications
            .map { it.notification.extras.getString("android.title") }
            .firstOrNull { it == "Turn off unlocks soon" || it == "Cooldown over" }

    /** The counting cooldown, moved to 3 seconds from its end; the blocker told. */
    private fun runsOutIn3s() {
        Storage.saveStrict(ctx, Storage.loadStrict(ctx).copy(cooldownEnds = System.currentTimeMillis() + 3_000))
        BlockerService.instance!!.strictChanged()
    }

    @Test
    fun plugIn_unlocksTurnOff() {
        freshState()
        shell("dumpsys battery unplug")
        strictOn(Storage.WayOut.PLUG)
        openHome()
        waitFor(By.desc("Strict mode is on")).click()

        waitFor(text("Plug in to unlock Turn off."))
        assertFalse(waitFor(text("Turn off strict mode")).isEnabled)

        // Plugged in, even with the battery full: a full battery isn't "charging".
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set level 100")
        shell("dumpsys battery set status 5")   // BATTERY_STATUS_FULL
        waitFor(text("Plugged in. Turn off is unlocked."))
        waitFor(text("Turn off strict mode")).click()

        waitFor(text("Strict mode is off."))
        assertFalse(Storage.loadStrict(ctx).on)
    }

    @Test
    fun emergencyPass_opensBlockedApps_andOnlyCountsInThem() {
        freshState(mapOf(CLOCK to Rule(allowMin = 1, windowMin = 120)))
        seedUsage(CLOCK, startAgo = 11.min, endAgo = 10.min, windowMin = 120)
        strictOn(Storage.WayOut.COOLDOWN, pass = Rule(5, 1440))
        fun saved() = Storage.passSavedMs(Storage.loadStrict(ctx), System.currentTimeMillis())
        fun counting() = Storage.passCounting(Storage.loadStrict(ctx))

        openApp(CLOCK)
        waitFor(textContains("Clock is blocked"))

        // Straight to AppBlock: after Home is pressed (the wall's button),
        // Android refuses other apps' launches for a few seconds.
        openHome()
        waitFor(By.desc("Strict mode is on")).click()
        waitFor(textContains("Use emergency pass")).click()
        waitFor(text("Your blocked apps are open."))

        // Open, but no blocked app on screen: nothing's used.
        Thread.sleep(2000)
        assertFalse("the pass counted outside a blocked app", counting())
        assertEquals(5.min, saved())

        openApp(CLOCK)
        Thread.sleep(1500)
        assertEquals("a pass should open the blocked app", null, wallText(CLOCK))
        assertTrue("the pass didn't count in the blocked app", eventually { counting() })
        // Its time is on the pass, not the app's limit: still the 1 minute seeded.
        assertTrue(Storage.usedMsInWindow(ctx, CLOCK, 120) < 1.min + 5_000)
        Thread.sleep(3000)
        assertTrue("saved ${saved()}", saved() < 5.min - 2_000)

        // Out of the blocked app, it pauses.
        openHome()
        assertTrue("the pass didn't pause", eventually { !counting() })
        val paused = saved()
        Thread.sleep(3000)
        assertEquals("the pass counted while paused", paused, saved())

        // Back in, then stopped from its notification's button, as the user would.
        openApp(CLOCK)
        assertTrue(eventually { counting() })
        Strict.stopPass(ctx)
        assertTrue("the wall didn't come back", eventually { wallText(CLOCK) != null })
        assertTrue("saved ${saved()}", saved() in 4.min..paused)
        assertFalse(counting())
        // Strict mode stayed on the whole time.
        assertTrue(Storage.loadStrict(ctx).on)
    }

    @Test
    fun adb_endsStrictMode() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN, cooldownMin = 99_999)
        shell("am broadcast -a xyz.robinroy.appblock.END_STRICT -n ${ctx.packageName}/${EndStrictReceiver::class.java.name}")

        assertTrue(eventually { !Storage.loadStrict(ctx).on })
        assertTrue("the admin wasn't let go", eventually { !Strict.adminActive(ctx) })
    }

    @Test
    fun adminSwitchedOff_endsStrictMode_andSaysSo() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)
        // As in safe mode: the admin switched off without strict mode's say.
        ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java).removeActiveAdmin(Strict.admin(ctx))

        assertTrue(eventually { !Storage.loadStrict(ctx).on })
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue("no notification", eventually {
            nm.activeNotifications.any { it.notification.extras.getString("android.title") == "Strict mode ended" }
        })
    }

    @Test
    fun backFromSafeMode_theBlockerFindsTheAdminOff_andEndsStrictMode() {
        freshState()
        strictOn(Storage.WayOut.COOLDOWN)
        val wasOn = Storage.loadStrict(ctx)
        // Safe mode: AppBlock doesn't run while the admin is switched off,
        // so it's never told. Here it's running, and told (the test above):
        // with the blocker off, as on a phone that's not booted yet, the
        // admin goes, and strict mode is put back as safe mode leaves it,
        // still on, with no "ended" notification.
        setBlocker(false)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java).removeActiveAdmin(Strict.admin(ctx))
        assertTrue(eventually { !Strict.adminActive(ctx) && !Storage.loadStrict(ctx).on })
        Storage.saveStrict(ctx, wasOn)
        nm.cancelAll()

        // The normal boot after it: the blocker starts, and has to notice.
        setBlocker(true)
        assertTrue("strict mode still on", eventually { !Storage.loadStrict(ctx).on })
        assertTrue("no notification", eventually {
            nm.activeNotifications.any { it.notification.extras.getString("android.title") == "Strict mode ended" }
        })
    }

    @Test
    fun contactSupport_showsTheAddress() {
        freshState()
        ctx.startActivity(Intent(ctx, SupportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor(text("Contact support"))
        waitFor(text("support@robinroy.xyz"))
    }
}
