package xyz.robinroy.appblock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The enforcer: an accessibility service. Android tells it the moment any
 * window on screen changes, and hands it the list of windows with the app
 * each belongs to and where it is. That's every app on screen, not just the
 * one in front: both halves of a split screen, a floating pop-up window.
 * The service reads only that (which app, where), never what's inside,
 * except in an app whose short-video feed the user blocked (YouTube's
 * Shorts): there it looks for the feed's player by its view id, nothing
 * else, and walls the app while one is on screen.
 *
 * Each limited app on screen gets its own session and its own countdown
 * notification, ticked every few seconds so a session is cut off the moment
 * its budget runs out. A spent app gets a block wall over its window, and
 * only its window: the rest of the screen (the other half of a split, the
 * home screen under a pop-up) stays usable. The wall is an accessibility
 * overlay window, which this service may draw without "Display over other
 * apps".
 *
 * The system keeps an enabled accessibility service running, and starts it
 * again after a reboot or an update, so there's no foreground service and
 * no "on duty" notification.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val tickMs = 5_000L

    private var onScreen: Map<String, Storage.Bounds> = emptyMap()
    private val sessions = mutableMapOf<String, Long>()   // limited app on screen -> when its session started
    private val walls = mutableMapOf<String, Wall>()      // spent app on screen -> the wall over it
    private val timers = mutableMapOf<String, Timer>()    // app -> the countdown notification up for it
    private val timerIds = mutableMapOf<String, Int>()    // app -> its notification id, kept for the service's life
    // Window id -> app, from the last read: a window's contents can be
    // briefly unreadable mid-transition, and the wall mustn't drop for it.
    private var windowApps = mapOf<Int, String>()
    // App -> its windows' contents, from the last read: searched for a blocked feed's player.
    private var appRoots = mapOf<String, List<AccessibilityNodeInfo>>()
    // Apps on screen whose feed is blocked: their content changes are watched.
    private var watchedFeeds = emptySet<String>()
    private val versions = mutableMapOf<String, Long>()  // feed app on screen -> its versionCode
    private val feedsShown = mutableSetOf<String>()      // feed apps showing their feed (for the log)
    private var refreshQueued = false

    /** What a wall shows; when it changes, the wall is built anew. */
    private data class Look(val kind: Storage.WallKind, val size: Storage.WallSize, val popUp: Boolean,
                            val homeButton: Boolean)
    private class Wall(val view: View, var bounds: Storage.Bounds, val look: Look)
    private data class Timer(val deadline: Long, val level: Int)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Clear leftovers from older versions (the foreground service's
        // notification, per-app warnings) and from before a restart.
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        for (old in listOf("service", "usage", "usage_hi")) nm.deleteNotificationChannel(old)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        Log.i(TAG, "connected")
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // In a watched feed's app (Shorts opening, or scrolled away from).
            // A playing video changes the screen many times a second: look
            // again at most every 300 ms.
            if (event.packageName?.toString() in watchedFeeds && !refreshQueued) {
                refreshQueued = true
                handler.postDelayed(refreshNow, 300)
            }
            return
        }
        // A burst of window events (an app opening fires several) is one refresh.
        handler.removeCallbacks(refreshNow)
        refreshQueued = true
        handler.postDelayed(refreshNow, 50)
    }

    override fun onInterrupt() {}

    private val refreshNow = Runnable { refresh() }

    /** Re-read what's on screen and act on it. */
    private fun refresh() {
        handler.removeCallbacks(refreshNow)
        refreshQueued = false
        // Screen off or locked: nothing's being used, and a wall must never
        // cover the lock screen.
        val usable = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val windows = if (usable) readWindows() else emptyList()
        val screen = screenSize()
        val apps = Storage.onScreenAfter(windows, packageName, screen.y, onScreen)
        if (apps.keys != onScreen.keys) Log.d(TAG, "on screen: ${apps.keys}")
        onScreen = apps
        enforce(windows, screen)
    }

    private fun readWindows(): List<Storage.ScreenWindow> {
        val list = try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val apps = mutableMapOf<Int, String>()
        val roots = mutableMapOf<String, MutableList<AccessibilityNodeInfo>>()
        val out = list.map { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            val kind = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> Storage.WindowKind.APP
                AccessibilityWindowInfo.TYPE_SYSTEM -> Storage.WindowKind.SYSTEM
                else -> Storage.WindowKind.OTHER
            }
            val root = if (kind != Storage.WindowKind.APP) null else try { w.root } catch (e: Exception) { null }
            val pkg = if (kind != Storage.WindowKind.APP) null
                else root?.packageName?.toString() ?: windowApps[w.id]
            if (pkg != null) apps[w.id] = pkg
            if (pkg != null && root != null) roots.getOrPut(pkg) { mutableListOf() } += root
            Storage.ScreenWindow(kind, Storage.Bounds(r.left, r.top, r.right, r.bottom), pkg)
        }
        windowApps = apps
        appRoots = roots
        return out
    }

    private fun spent(pkg: String, rule: Rule) =
        Storage.usedMsInWindow(this, pkg, rule.windowMin) >= rule.allowMin * 60_000L

    /** Walls over the spent apps on screen (and blocked feeds), sessions and countdowns for the other limited ones. */
    private fun enforce(windows: List<Storage.ScreenWindow>, screen: Point) {
        val rules = Storage.loadRules(this)
        val plan = Storage.plan(onScreen.keys, rules, ::spent)
        val now = System.currentTimeMillis()

        for (pkg in sessions.keys - plan.timed) endSession(pkg, rules[pkg])
        for (pkg in plan.timed) sessions.getOrPut(pkg) { now }

        // The shade or power menu is open: the walls would be over it.
        val stepAside = Storage.systemOnTop(windows, screen.y)
        // Home already showing beside the app (it's in a pop-up over it): a
        // "Go to home screen" button would do nothing.
        val homeButton = homePackage() !in onScreen
        val feedApps = Storage.loadFeedBlocks(this).filter { it in onScreen && it !in plan.walled }.toSet()
        watchFeeds(feedApps)
        val kinds = Storage.wallKinds(plan.walled, feedApps.filter(::feedShowing).toSet())
        for (pkg in walls.keys.toList()) if (stepAside || pkg !in kinds) hideWall(pkg)
        if (!stepAside) for ((pkg, kind) in kinds) {
            val app = onScreen.getValue(pkg)
            val bounds = Storage.wallBounds(app, windows, screen.y)
            val popUp = Storage.isPopUp(app, screen.x, screen.y)
            // A pop-up leaves the rest of the screen usable: no button needed there.
            val look = Look(kind, Storage.wallSize(toDp(bounds.width), toDp(bounds.height)), popUp,
                homeButton && !popUp)
            showWall(pkg, rules[pkg], bounds, look)
        }

        showTimers(plan.timed, rules)
        handler.removeCallbacks(tick)
        // Walled apps are re-checked too: the wall lifts once time comes back.
        if (plan.timed.isNotEmpty() || plan.walled.isNotEmpty()) handler.postDelayed(tick, tickMs)
    }

    /** Every few seconds while a limited app is on screen: log the time so far, then re-check. */
    private val tick = Runnable {
        val rules = Storage.loadRules(this)
        val now = System.currentTimeMillis()
        for (session in sessions.entries) {
            rules[session.key]?.let { Storage.addUsage(this, session.key, session.value, now, it.windowMin) }
            session.setValue(now)
        }
        refresh()
    }

    /** `pkg` left the screen (or ran out): log its session. */
    private fun endSession(pkg: String, rule: Rule?) {
        val start = sessions.remove(pkg) ?: return
        if (rule != null) Storage.addUsage(this, pkg, start, System.currentTimeMillis(), rule.windowMin)
    }

    // ------------------------------------------------------------------ feeds

    /**
     * Watch the content of the apps on screen whose feed is blocked: a feed
     * opens inside the app (a tab, a link) without any window changing.
     * Content changes are the busiest events there are, so they're only
     * asked for while such an app is on screen.
     */
    private fun watchFeeds(apps: Set<String>) {
        for (pkg in versions.keys - apps) { versions.remove(pkg); feedsShown.remove(pkg) }
        val was = watchedFeeds.isNotEmpty()
        watchedFeeds = apps
        if (apps.isNotEmpty() == was) return
        val info = serviceInfo ?: return
        val content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.eventTypes = if (apps.isNotEmpty()) info.eventTypes or content else info.eventTypes and content.inv()
        serviceInfo = info
    }

    /** Whether `pkg` is showing its feed (Shorts): one of its rules' player views fills its window. */
    private fun feedShowing(pkg: String): Boolean {
        val rules = Feeds.rules(this) ?: return false
        val version = versions.getOrPut(pkg) {
            versionOf(pkg).also { Log.i(TAG, "$pkg $it on screen, feed rules ${rules.version}") }
        }
        Feeds.maybeFetch(this, pkg, version)
        val window = onScreen[pkg] ?: return false
        val roots = appRoots[pkg].orEmpty()
        val showing = Storage.feedRulesFor(rules, pkg, version).any { rule ->
            val found = roots.flatMap { root ->
                rule.ids.flatMap { id ->
                    try { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") } catch (e: Exception) { emptyList() }
                }
            }.map { node ->
                val r = Rect().also { node.getBoundsInScreen(it) }
                Storage.FoundView(node.isVisibleToUser, Storage.Bounds(r.left, r.top, r.right, r.bottom))
            }
            Storage.feedShowing(found, window, rule.minCover)
        }
        if (showing != pkg in feedsShown) {
            if (showing) feedsShown += pkg else feedsShown -= pkg
            Log.i(TAG, if (showing) "$pkg feed on screen" else "$pkg feed gone")
        }
        return showing
    }

    private fun versionOf(pkg: String): Long = try {
        val info = packageManager.getPackageInfo(pkg, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) { -1 }

    /** The launcher's package (the user can change launchers, so asked each time). */
    private fun homePackage(): String? = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName

    private fun screenSize(): Point {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        @Suppress("DEPRECATION")
        return Point().also { wm.defaultDisplay.getRealSize(it) }
    }

    private fun toDp(px: Int) = (px / resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- countdowns

    /**
     * One status-bar countdown per limited app on screen, of what's left of
     * its allowance, like the Clock app's timer. The system ticks the
     * chronometer itself, so each is only (re)posted when something actually
     * changes: at session start, whenever old usage ages out of the rolling
     * window and gives time back, and when usage crosses 50%/90% (the
     * warning then replaces the text). Tapping one opens AppBlock.
     */
    private fun showTimers(apps: Set<String>, rules: Map<String, Rule>) {
        val nm = getSystemService(NotificationManager::class.java)
        for (pkg in timers.keys - apps) {
            timers.remove(pkg)
            nm.cancel(timerIds.getValue(pkg))
        }
        if (apps.isEmpty()) return
        // LOW: icon in the status bar, no sound or heads-up.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Session timer", NotificationManager.IMPORTANCE_LOW))
        for (pkg in apps) {
            val rule = rules.getValue(pkg)
            val usedMs = Storage.usedMsInWindow(this, pkg, rule.windowMin)
            val deadline = System.currentTimeMillis() + Storage.remainingMs(usedMs, rule.allowMin)
            val level = Storage.crossedWarnLevel(usedMs, rule.allowMin)
            val shown = timers[pkg]
            if (!Storage.timerNeedsRepost(shown?.deadline, deadline, shown?.level ?: 0, level)) continue
            timers[pkg] = Timer(deadline, level)
            val id = timerIds.getOrPut(pkg) { NOTIF_TIMER + timerIds.size }
            try {
                nm.notify(id, timerNotification(pkg, rule, usedMs, deadline, level))
            } catch (e: Exception) {  // refused: no countdown, blocking goes on
                Log.w(TAG, "countdown for $pkg refused", e)
            }
        }
    }

    /**
     * On Android 16 QPR1+ a countdown asks to be a "Live Update": the system
     * then shows it as a status-bar chip with the countdown ticking in it,
     * and OEM skins put it in their capsule (OxygenOS "Live Alerts"). Older
     * versions ignore the request and just show the notification.
     * Requirements met here: ongoing, has a title, plain style, channel above
     * MIN importance, and no short-text override so the chip shows the
     * chronometer.
     */
    private fun timerNotification(pkg: String, rule: Rule, usedMs: Long, deadline: Long, level: Int): Notification {
        val text = if (level == 0) "Allowance for the next ${Storage.fmtSpan(rule.windowMin)} window."
            else "You've used ${Storage.usedPct(usedMs, rule.allowMin)}% " +
                "(${Storage.fmtMin(usedMs)} min) of your usage for ${labelFor(pkg)}"
        return Notification.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${labelFor(pkg)}: time left")
            .setContentText(text)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            // Notification.Builder.setRequestPromotedOngoing(true), spelled
            // as the extra it sets so we needn't compile against SDK 36.1.
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .build()
    }

    private fun iconFor(pkg: String): android.graphics.drawable.Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    // ------------------------------------------------------------------ walls

    private fun wallParams(b: Storage.Bounds) = WindowManager.LayoutParams(
        b.right - b.left, b.bottom - b.top,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = b.left
        y = b.top
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Cover `pkg`'s window with the block wall, or move its wall to where the window now is. */
    private fun showWall(pkg: String, rule: Rule?, bounds: Storage.Bounds, look: Look) {
        val wm = getSystemService(WindowManager::class.java)
        walls[pkg]?.let { wall ->
            // Crossing into another layout (split <-> full screen <-> pop-up) rebuilds it.
            if (wall.look == look) {
                if (wall.bounds == bounds) return
                wall.bounds = bounds
                try { wm.updateViewLayout(wall.view, wallParams(bounds)) } catch (e: Exception) {}
                return
            }
            hideWall(pkg)
        }
        Log.i(TAG, "blocking $pkg (${look.kind}) at $bounds")
        val view = wallView(pkg, rule, look)
        try {
            wm.addView(view, wallParams(bounds))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't draw the wall over $pkg", e)
            return
        }
        walls[pkg] = Wall(view, bounds, look)
    }

    private fun hideWall(pkg: String) {
        val wall = walls.remove(pkg) ?: return
        try { getSystemService(WindowManager::class.java).removeView(wall.view) } catch (e: Exception) {}
    }

    private fun wallView(pkg: String, rule: Rule?, look: Look): View {
        val full = look.size == Storage.WallSize.FULL
        val tiny = look.size == Storage.WallSize.TINY
        val feed = look.kind == Storage.WallKind.FEED
        val label = labelFor(pkg)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            val side = Ui.dp(this@BlockerService, if (full) 28 else if (tiny) 10 else 16)
            setPadding(side, side, side, side)
            setBackgroundColor(getColor(R.color.canvas))

            // One TextView carries both lines (the e2e tests read it whole);
            // spans give the headline weight and the wait time a calmer tone.
            // A pop-up a few fingers wide fits one short word: it's plain which app it's over.
            val headline = if (feed) { if (tiny) "No Shorts." else "Shorts are blocked." }
                else if (tiny) "Blocked." else "$label is blocked."
            val wait = if (feed) "The rest of $label is still open."
                else "Try again in ${Storage.fmtSpan(Storage.ceilMin(Storage.msUntilUnblocked(this@BlockerService, pkg, rule!!)).toInt())}."
            val message = SpannableString("$headline\n\n$wait").apply {
                setSpan(AbsoluteSizeSpan(if (full) 40 else if (tiny) 22 else 28, true), 0, headline.length + 1, 0)
                setSpan(Ui.FontSpan(Ui.serif(this@BlockerService)), 0, headline.length, 0)
                setSpan(AbsoluteSizeSpan(10, true), headline.length + 1, headline.length + 2, 0)  // the blank line: a small gap
                setSpan(AbsoluteSizeSpan(if (full) 18 else if (tiny) 13 else 15, true), headline.length + 2, length, 0)
                setSpan(ForegroundColorSpan(getColor(R.color.muted)), headline.length + 2, length, 0)
            }

            if (full) addView(FrameLayout(context).apply {
                clipChildren = false   // the lock badge overhangs the icon's corner
                addView(Ui.appIcon(context, iconFor(pkg), 84).apply {
                    layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 84), Ui.dp(context, 84))
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix().apply { setSaturation(0f) })
                    alpha = 0.55f
                })
                addView(FrameLayout(context).apply {
                    background = Ui.shape(context, R.color.ink, 0f, R.color.canvas, oval = true)
                    addView(Ui.icon(context, R.drawable.ic_lock, 16, R.color.on_ink).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            Ui.dp(context, 16), Ui.dp(context, 16), Gravity.CENTER)
                    })
                }, FrameLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34),
                    Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = -Ui.dp(context, 6); bottomMargin = -Ui.dp(context, 6)
                })
            }, LinearLayout.LayoutParams(Ui.dp(context, 84), Ui.dp(context, 84)).apply {
                bottomMargin = Ui.dp(context, 32)
            })
            addView(TextView(context).apply {
                text = message
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.ink))
                typeface = Ui.sans(context)
                setLineSpacing(0f, 1.05f)
                letterSpacing = -0.01f
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            if (full && !feed) addView(Ui.muted(context, "The minutes come back as older use leaves the window.", 14f).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(context, 16) })
            // The wall covers a pop-up's own close button and handle (they're
            // drawn inside its window), so say how else to close it. Shorts
            // has the back button below instead.
            if (look.popUp && !feed) addView(Ui.muted(context, "To close it, swipe it away in Recents.",
                if (full) 14f else if (tiny) 11f else 13f).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(context, if (tiny) 8 else 16) })
            // Out of Shorts, into the rest of the app: back, as if the user swiped it.
            if (feed) addView(Ui.primaryButton(context, "Go back") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
                topMargin = Ui.dp(context, if (full) 36 else 16)
            })
            // On every wall that could cover the gesture bar, so there's always a way out.
            else if (look.homeButton) addView(Ui.primaryButton(context, "Go to home screen", R.drawable.ic_chevron) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
                topMargin = Ui.dp(context, if (full) 36 else 16)
            })

            // Settle in: no fade of the wall itself (it must cover instantly).
            for (i in 0 until childCount) Ui.enter(getChildAt(i), i)
        }
    }

    // ------------------------------------------------------------------- end

    /** Switched off (or the app updated): log open sessions, take everything down. */
    private fun shutDown() {
        if (instance !== this) return
        instance = null
        handler.removeCallbacksAndMessages(null)
        val rules = Storage.loadRules(this)
        for (pkg in sessions.keys.toList()) endSession(pkg, rules[pkg])
        showTimers(emptySet(), rules)
        for (pkg in walls.keys.toList()) hideWall(pkg)
        onScreen = emptyMap()
        appRoots = emptyMap()
        watchedFeeds = emptySet()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        Log.i(TAG, "disconnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val CHANNEL_TIMER = "timer"
        private const val NOTIF_TIMER = 2
        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1)
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        /** The running service, while it's switched on (the e2e tests wait on it). */
        @Volatile var instance: BlockerService? = null
            private set
    }
}
