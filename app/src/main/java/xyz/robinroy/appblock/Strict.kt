package xyz.robinroy.appblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Strict mode's moving parts, around the decisions in Storage: turning it
 * on once Moly is a device admin, the cooldown and the emergency pass
 * with their notifications, and every way it ends. The screens
 * (StrictActivity), the blocker (BlockerService) and the receivers below
 * all go through here, so each step happens the same way whoever starts it.
 */
object Strict {
    private const val TAG = "Strict"
    private const val CHANNEL = "strict"
    private const val NOTIF_COOLDOWN = 100
    private const val NOTIF_PASS = 101
    private const val NOTIF_ENDED = 102

    /** How strict mode ended: through the way out, safe mode (the admin found off), or adb. */
    enum class Ending { PLUGGED_IN, COOLDOWN, ADMIN_OFF, ADB }

    fun admin(ctx: Context) = ComponentName(ctx, StrictAdmin::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun adminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    /** The system's "Activate device admin app?" page for Moly. */
    fun activateIntent(ctx: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Strict mode: while it's on, Android won't let Moly be uninstalled.")

    /**
     * The system apps holding a way around strict mode, which the blocker
     * walls while they show Moly's name: whatever opens Settings, App
     * info, Accessibility, the device admin page, the uninstall dialog and
     * the permission pages (Safety Center's "Remove access" card is in the
     * permission controller). Found on the phone, since makers move them;
     * the stock names are added in case one doesn't resolve.
     */
    fun guardedPackages(ctx: Context): Set<String> {
        val pkg = android.net.Uri.parse("package:${ctx.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN),
            Intent(Intent.ACTION_DELETE, pkg),
            @Suppress("DEPRECATION") Intent(Intent.ACTION_UNINSTALL_PACKAGE, pkg),
            Intent("android.intent.action.MANAGE_APP_PERMISSIONS"),
            Intent("android.safetycenter.action.SAFETY_CENTER"))
        val found = intents.flatMap { intent ->
            try { ctx.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } }
            catch (e: Exception) { emptyList() }
        }
        val stock = listOf("com.android.settings", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller")
        // "android" is the chooser, never a page of its own.
        return (found + stock).toSet() - ctx.packageName - "android"
    }

    /** Whether the phone is plugged in: a full battery, or one held at 80%, still counts. */
    fun pluggedIn(ctx: Context): Boolean =
        (ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    /** Chosen on the setup screen: waits for the user to make Moly a device admin. */
    fun choose(ctx: Context, wayOut: Storage.WayOut, cooldownMin: Int, pass: Rule?) {
        Storage.saveStrict(ctx, Storage.loadStrict(ctx).copy(
            pending = true, wayOut = wayOut, cooldownMin = cooldownMin, pass = pass))
    }

    /** The user backed out of the device admin page: nothing's on. */
    fun unchoose(ctx: Context) {
        val s = Storage.loadStrict(ctx)
        if (s.pending) Storage.saveStrict(ctx, s.copy(pending = false))
    }

    /**
     * Moly just became a device admin. If the user chose strict mode,
     * it's on. If not (made one from somewhere else), it lets go again:
     * an admin with no strict mode would only block uninstalling, with no
     * way out shown anywhere.
     */
    fun adminEnabled(ctx: Context) {
        val s = Storage.loadStrict(ctx)
        if (s.on) return
        if (!s.pending) { Log.i(TAG, "admin enabled outside strict mode: letting go"); releaseAdmin(ctx); return }
        Storage.saveStrict(ctx, s.copy(on = true, pending = false, cooldownEnds = 0, passStarted = 0,
            passInUse = 0, passUses = emptyList()))
        Log.i(TAG, "on: ${s.wayOut}, cooldown ${s.cooldownMin} min, pass ${s.pass}")
        changed(ctx)
    }

    /**
     * Let go of the device admin. Android removes it in the background, so a
     * second ending close behind the first (a double tap, the adb command
     * beside the admin's own "switched off" callback) can find it still
     * active, then have it gone by the time it asks: Android throws, and
     * the admin is let go all the same.
     */
    private fun releaseAdmin(ctx: Context) {
        if (!adminActive(ctx)) return
        try {
            dpm(ctx).removeActiveAdmin(admin(ctx))
        } catch (e: SecurityException) {
            Log.i(TAG, "admin already gone: ${e.message}")
        }
    }

    /**
     * Strict mode, brought up to date: the clock checked (Clock.check, which
     * moves the usage logs too), a cooldown that ran out unlocks Turn off, a
     * pass that ran out is stopped, and an admin found off (switched off in
     * safe mode, where Moly doesn't run) ends it too. Run whenever
     * Moly looks at strict mode: the screens, the blocker, the
     * clock-change receiver.
     */
    fun check(ctx: Context): Storage.Strict {
        Clock.check(ctx)
        var s = Storage.loadStrict(ctx)
        if (!s.on) {
            // An admin left over with strict mode off (an ending that didn't
            // finish): let go, so Moly can be uninstalled.
            if (!s.pending && adminActive(ctx)) releaseAdmin(ctx)
            return s
        }
        if (!adminActive(ctx)) return end(ctx, Ending.ADMIN_OFF)
        val now = System.currentTimeMillis()
        // Run out: strict mode stays on, its notification now says Turn off is unlocked.
        if (Storage.cooldownDone(s, now) && unlockShown != s.cooldownEnds) notifyCooldown(ctx, s)
        if (s.passStarted != 0L && !Storage.passRunning(s, now)) {
            s = Storage.stopPass(s, now)
            Storage.saveStrict(ctx, s)
            nm(ctx).cancel(NOTIF_PASS)
            notifyOnce(ctx, "Emergency pass used up", "Your blocked apps are closed again.")
            Log.i(TAG, "pass used up")
        }
        return s
    }

    /**
     * The clock was moved by `moved` (Clock.check): a counting cooldown
     * starts over and the pass's times move with it (Storage.afterClockMove),
     * and their countdowns, which count to a wall-clock time, are posted again.
     */
    fun clockMoved(ctx: Context, moved: Long, wall: Long) {
        val s = Storage.loadStrict(ctx)
        val after = Storage.afterClockMove(s, moved, wall)
        if (after == s) return
        Storage.saveStrict(ctx, after)
        if (after.cooldownEnds != s.cooldownEnds) Log.i(TAG, "the clock moved: cooldown starts over")
        if (Storage.coolingDown(after)) notifyCooldown(ctx, after)
        if (Storage.passRunning(after, wall)) notifyPass(ctx, after)
    }

    /**
     * Strict mode over: the running cooldown and pass dropped with their
     * notifications, and the admin let go so Moly can be uninstalled
     * as usual. Safe mode is told in a notification (the user may not be
     * looking at Moly).
     */
    fun end(ctx: Context, why: Ending): Storage.Strict {
        val s = Storage.strictEnded(Storage.loadStrict(ctx))
        Storage.saveStrict(ctx, s)
        nm(ctx).cancel(NOTIF_COOLDOWN)
        nm(ctx).cancel(NOTIF_PASS)
        releaseAdmin(ctx)
        Log.i(TAG, "off: $why")
        when (why) {
            Ending.ADMIN_OFF -> notifyOnce(ctx, "Strict mode ended", "Moly's device admin was switched off.")
            else -> {}
        }
        changed(ctx)
        return s
    }

    fun startCooldown(ctx: Context) {
        val s = check(ctx)
        if (!s.on || Storage.coolingDown(s)) return
        val started = Storage.startCooldown(s, System.currentTimeMillis())
        Storage.saveStrict(ctx, started)
        notifyCooldown(ctx, started)
        changed(ctx)
    }

    fun cancelCooldown(ctx: Context) {
        val s = check(ctx)
        if (!Storage.coolingDown(s)) return
        Storage.saveStrict(ctx, Storage.cancelCooldown(s))
        nm(ctx).cancel(NOTIF_COOLDOWN)
        changed(ctx)
    }

    /** Turn strict mode off by the way out: only while plugged in, or once the cooldown ran out. */
    fun turnOff(ctx: Context): Boolean {
        val s = check(ctx)
        if (!Storage.canTurnOff(s, System.currentTimeMillis(), pluggedIn(ctx))) return false
        end(ctx, if (s.wayOut == Storage.WayOut.PLUG) Ending.PLUGGED_IN else Ending.COOLDOWN)
        return true
    }

    fun startPass(ctx: Context) {
        val s = check(ctx)
        val started = Storage.startPass(s, System.currentTimeMillis())
        if (started == s) return
        Storage.saveStrict(ctx, started)
        notifyPass(ctx, started)
        changed(ctx)
    }

    /**
     * The blocker's word on the open pass: whether a blocked app is on
     * screen under it. The pass counts only then; its notification switches
     * between counting down and paused.
     */
    fun meterPass(ctx: Context, s: Storage.Strict, inBlocked: Boolean): Storage.Strict {
        val metered = Storage.meterPass(s, inBlocked, System.currentTimeMillis())
        if (metered == s) return s
        Storage.saveStrict(ctx, metered)
        if (Storage.passCounting(metered) != Storage.passCounting(s)) {
            Log.i(TAG, if (Storage.passCounting(metered)) "pass counting" else "pass paused")
            notifyPass(ctx, metered)
        }
        return metered
    }

    fun stopPass(ctx: Context) {
        val s = check(ctx)
        if (s.passStarted == 0L) return
        Storage.saveStrict(ctx, Storage.stopPass(s, System.currentTimeMillis()))
        nm(ctx).cancel(NOTIF_PASS)
        changed(ctx)
    }

    /** The blocker looks again: walls go up or down with strict mode and passes. */
    private fun changed(ctx: Context) {
        BlockerService.instance?.strictChanged()
    }

    // ---------------------------------------------------------- notifications

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channel(ctx: Context) {
        // LOW, like the session countdowns: in the status bar, no sound.
        nm(ctx).createNotificationChannel(
            NotificationChannel(CHANNEL, "Strict mode", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openStrict(ctx: Context) = PendingIntent.getActivity(ctx, 1,
        Intent(ctx, StrictActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, label: String, act: String) = Notification.Action.Builder(null, label,
        PendingIntent.getBroadcast(ctx, act.hashCode(),
            Intent(ctx, StrictReceiver::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)).build()

    /** A countdown like the session timer's, Live Update and all (BlockerService.timerNotification). */
    private fun countdown(ctx: Context, title: String, text: String, deadline: Long, action: Notification.Action) =
        Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openStrict(ctx))
            .addAction(action)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .build()

    private fun post(ctx: Context, id: Int, n: Notification) {
        channel(ctx)
        try { nm(ctx).notify(id, n) } catch (e: Exception) { Log.w(TAG, "notification $id refused", e) }
    }

    /** The cooldown whose "run out" notification is up, so check() posts it once, not on every look. */
    @Volatile private var unlockShown = 0L

    /**
     * The cooldown: counting down, then once it runs out, that Turn off is
     * unlocked. Both stay up (strict mode is still on) with Keep it on.
     */
    fun notifyCooldown(ctx: Context, s: Storage.Strict) {
        val keepOn = action(ctx, "Keep it on", StrictReceiver.KEEP_ON)
        if (!Storage.cooldownDone(s, System.currentTimeMillis())) {
            unlockShown = 0
            post(ctx, NOTIF_COOLDOWN, countdown(ctx, "Turn off unlocks soon", "Changed your mind? Keep it on.",
                s.cooldownEnds, keepOn))
            return
        }
        unlockShown = s.cooldownEnds
        val text = "Turn off is unlocked. Strict mode stays on until you tap it."
        post(ctx, NOTIF_COOLDOWN, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cooldown over")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openStrict(ctx))
            .addAction(keepOn)
            .build())
    }

    /**
     * The open pass: counting down while a blocked app is on screen, and
     * otherwise paused, with the time left written out.
     */
    fun notifyPass(ctx: Context, s: Storage.Strict) {
        val now = System.currentTimeMillis()
        val stop = action(ctx, "Stop, save the rest", StrictReceiver.STOP_PASS)
        post(ctx, NOTIF_PASS, if (Storage.passCounting(s))
            countdown(ctx, "Emergency pass", "Counting down while you're in a blocked app.",
                Storage.passEnds(s, now), stop)
        else Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Emergency pass · ${Storage.fmtClock(Storage.passLeftMs(s, now))} left")
            .setContentText("Your blocked apps are open. It only counts down while you're in one.")
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openStrict(ctx))
            .addAction(stop)
            .build())
    }

    private fun notifyOnce(ctx: Context, title: String, text: String) = post(ctx, NOTIF_ENDED,
        Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())

    /**
     * The blocker started: the countdowns again (it cleared every
     * notification), and a pass that was counting when it stopped counts
     * from now, the unlogged seconds before dropped (Storage.passAfterRestart).
     */
    fun renotify(ctx: Context) {
        var s = check(ctx)
        Storage.passAfterRestart(s).let { if (it != s) { s = it; Storage.saveStrict(ctx, s) } }
        if (Storage.coolingDown(s)) notifyCooldown(ctx, s)
        if (Storage.passRunning(s, System.currentTimeMillis())) notifyPass(ctx, s)
    }

    /** For the e2e tests: strict mode off and the admin let go, waited for. */
    fun resetForTests(ctx: Context) {
        Storage.saveStrict(ctx, Storage.Strict())
        listOf(NOTIF_COOLDOWN, NOTIF_PASS, NOTIF_ENDED).forEach { nm(ctx).cancel(it) }
        releaseAdmin(ctx)
        val deadline = System.currentTimeMillis() + 10_000
        while (adminActive(ctx) && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }
}

/**
 * Moly as a device admin, declaring no admin powers at all
 * (res/xml/strict_admin.xml): it's one only so that Android refuses to
 * uninstall it while strict mode is on.
 */
class StrictAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Strict.adminEnabled(context)

    /**
     * Switched off, which strict mode's walls keep the user from doing
     * except in safe mode (where Moly doesn't run, so this may never
     * arrive: Strict.check notices instead) or after strict mode let go.
     * Android still counts the admin as active until this returns, so it
     * ends strict mode itself rather than leaving it to Strict.check.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        if (Storage.loadStrict(context).on) Strict.end(context, Strict.Ending.ADMIN_OFF)
    }

    /** Shown on the page to switch it off: reachable in safe mode. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Switching this off ends strict mode."
}

/**
 * The clock being set (Strict.check runs Clock.check: the usage logs and
 * strict mode's times move with it), and the buttons on strict mode's
 * notifications.
 */
class StrictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            KEEP_ON -> Strict.cancelCooldown(context)
            STOP_PASS -> Strict.stopPass(context)
            else -> Strict.check(context)   // TIME_SET
        }
    }

    companion object {
        const val KEEP_ON = "xyz.robinroy.appblock.KEEP_ON"
        const val STOP_PASS = "xyz.robinroy.appblock.STOP_PASS"
    }
}

/**
 * The developer's way out: `adb shell am broadcast -a
 * xyz.robinroy.appblock.END_STRICT -n xyz.robinroy.appblock/.EndStrictReceiver`
 * ends strict mode. Only the shell can send it (it needs the DUMP
 * permission, which no app gets), so it's no way out for anyone without USB
 * debugging and a computer.
 */
class EndStrictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Strict.end(context, Strict.Ending.ADB)
    }
}
