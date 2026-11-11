package xyz.robinroy.appblock

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Feedback from the info sheet: gathers what goes with a message
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
        object Failed : Outcome()
    }

    /**
     * Sends a body to the server: its status and its answer. The e2e tests
     * put their own here, as they have no server.
     */
    @Volatile var post: (String) -> Pair<Int, String?> = ::postToServer

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
    fun send(ctx: Context, message: String, contact: String?, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        thread(name = "feedback") {
            val outcome = try {
                val body = Storage.feedbackBody(message, contact, Storage.senderId(app), about(app))
                val (status, answer) = post(body)
                val id = Storage.feedbackId(status, answer)
                if (id == null) {
                    Log.w(TAG, "not taken: HTTP $status")
                    Outcome.Failed
                } else {
                    Storage.addSentFeedback(app, Storage.SentFeedback(id, System.currentTimeMillis(), message))
                    Outcome.Sent(id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                Outcome.Failed
            }
            main.post { done(outcome) }
        }
    }

    private fun postToServer(body: String): Pair<Int, String?> {
        val url = URL("${BuildConfig.API_BASE}/api/feedback")
        // HTTPS or nothing, except to this machine in the "local" build
        // (app/build.gradle.kts), which talks to a developer's own server.
        val conn = if (url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))
            url.openConnection() as HttpURLConnection
        else url.openConnection() as HttpsURLConnection
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
