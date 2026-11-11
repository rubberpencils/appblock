package xyz.robinroy.appblock

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.URL
import kotlin.concurrent.thread

/**
 * Feedback and support requests ("Help & feedback" on the info sheet and
 * on the app icon's long-press menu): gathers what goes with a message
 * (Storage.FeedbackAbout: the app's version, the phone, how Moly is set up;
 * never which apps are limited), posts it to the server, and keeps a copy
 * on the phone once the server has it. The decisions are in Storage.kt; the
 * form is FeedbackPages.kt.
 */
object Feedback {

    private const val TAG = "Feedback"

    /** How a send ended. */
    sealed class Outcome {
        data class Sent(val id: String) : Outcome()
        /** Not sent. `refused`: the server said no, and would again. */
        data class Failed(val refused: Boolean) : Outcome()
    }

    /**
     * What is being written, kept while the app runs: turning the phone, or
     * the sheet closing by accident, doesn't cost anyone their words. `id`
     * is the message's own, made for its first try and kept for the next.
     */
    class Draft(
        var kind: Storage.FeedbackKind = Storage.FeedbackKind.FEEDBACK,
        var message: String = "", var contact: String = "",
        var id: String = java.util.UUID.randomUUID().toString(),
    )

    @Volatile var draft = Draft()
        private set

    /** The message was sent, or thrown away: the next starts empty, with an id of its own. */
    fun newDraft() { draft = Draft() }

    /**
     * Sends a body to the server: its status and its answer. The e2e tests
     * put their own here, as they have no server.
     */
    @Volatile var post: (String) -> Pair<Int, String?> = ::postToServer

    /**
     * The server: trymoly.com, or a developer's machine in the "local"
     * build. The full-stack e2e tests point it at the server they run
     * against (docs/local-dev.md).
     */
    @Volatile var apiBase: String = BuildConfig.API_BASE

    /** Sends to the real server again, after a test that didn't. */
    fun resetForTests() {
        post = ::postToServer
        apiBase = BuildConfig.API_BASE
        newDraft()
    }

    /** When Android says the app was first installed and last updated on this phone. */
    fun androidInstallTimes(ctx: Context): Storage.InstallTimes {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return Storage.InstallTimes(info.firstInstallTime, info.lastUpdateTime)
    }

    /** What Moly reports about itself with a message. */
    fun about(ctx: Context): Storage.FeedbackAbout {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        val strict = Storage.loadStrict(ctx)
        val feeds = Storage.loadFeedBlocks(ctx)
        val diagnostics = linkedMapOf<String, Any>(
            "accessibility" to accessibilityOn(ctx),
            "battery_exempt" to batteryExempt(ctx),
            "usage_access" to usageAccessGranted(ctx),
            "notifications" to ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            "strict" to when {
                strict.on -> strict.wayOut.name.lowercase()
                strict.pending -> "pending"
                else -> "off"
            },
            "limited_apps" to Storage.loadRules(ctx).size,
            "shorts_blocked" to ("com.google.android.youtube" in feeds),
            "reels_blocked" to ("com.instagram.android" in feeds),
            "rules_updated" to (Feeds.rules(ctx)?.updated?.toString() ?: "none"),
            "language" to ctx.resources.configuration.locales[0].toLanguageTag(),
        )
        return Storage.FeedbackAbout(
            appVersion = info.versionName ?: "unknown",
            appBuild = build,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            androidSdk = Build.VERSION.SDK_INT,
            device = Storage.deviceName(Build.MANUFACTURER ?: "", Build.MODEL ?: ""),
            install = Storage.noteInstallTimes(ctx, androidInstallTimes(ctx)),
            diagnostics = diagnostics,
        )
    }

    /**
     * Sends `message` (and where to reply, if given) off the main thread,
     * then tells `done` on it. Sent means the server has it: only then is
     * it kept in the past feedback and counted towards the day's limit.
     */
    fun send(
        ctx: Context, id: String, kind: Storage.FeedbackKind, message: String, contact: String?,
        done: (Outcome) -> Unit
    ) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "feedback") {
            val outcome = try {
                val body = Storage.feedbackBody(id, kind, message, contact, Storage.senderId(app), about(app))
                val (status, answer) = post(body)
                val taken = Storage.feedbackId(status, answer)
                if (taken == null) {
                    Log.w(TAG, "not taken: HTTP $status")
                    Outcome.Failed(Storage.feedbackRefused(status))
                } else {
                    Storage.addSentFeedback(app,
                        Storage.SentFeedback(taken, System.currentTimeMillis(), message, kind, contact))
                    Outcome.Sent(taken)
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                Outcome.Failed(refused = false)
            }
            main.post { done(outcome) }
        }
    }

    private fun postToServer(body: String): Pair<Int, String?> {
        val conn = Net.open(URL("$apiBase/api/feedback"))
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        conn.requestMethod = "POST"
        conn.doOutput = true
        // As for the rules: the default names the phone's model, which the body says anyway, once.
        conn.setRequestProperty("User-Agent", "Moly")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return status to stream?.bufferedReader()?.use { it.readText() }
        } finally { conn.disconnect() }
    }
}
