package xyz.robinroy.appblock

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The Shorts-detection rules on the phone: the newest of the copy bundled
 * in the APK and the last one fetched. Fetching is a plain download of the
 * signed rules file from the rules' own `source` (GitHub for now), and a
 * new rules file can name a new `source`, so the host can move without an
 * app update. A file whose signature doesn't check out is thrown away.
 * Nothing about the phone or its user is sent.
 */
object Feeds {

    private const val TAG = "Feeds"
    private const val BUNDLED = "rules.signed.json"

    @Volatile private var current: Storage.FeedRules? = null
    @Volatile private var fetching = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feedrules", Context.MODE_PRIVATE)

    /** The rules in use. */
    fun rules(ctx: Context): Storage.FeedRules? = current ?: run {
        val bundled = try {
            ctx.assets.open(BUNDLED).bufferedReader().use { Storage.openSignedRules(it.readText()) }
        } catch (e: Exception) { null }
        val fetched = prefs(ctx).getString("signed", null)?.let { Storage.openSignedRules(it) }
        Storage.newerRules(bundled, fetched).also { current = it }
    }

    /** The e2e tests' rules (for their stand-in app), or null to go back to the real ones. */
    fun useForTests(rules: Storage.FeedRules?) {
        current = rules
    }

    /**
     * Fetch the rules again in the background if they may be out of date
     * (Storage.shouldFetchRules), as `pkg` comes on screen at `versionCode`.
     */
    fun maybeFetch(ctx: Context, pkg: String, versionCode: Long) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (fetching || !Storage.shouldFetchRules(p.getLong("lastAttempt", 0), now,
                p.getLong("version:$pkg", -1), versionCode)) return
        p.edit().putLong("lastAttempt", now).putLong("version:$pkg", versionCode).apply()
        fetching = true
        val app = ctx.applicationContext
        thread(name = "feed-rules") {
            try { fetch(app) } catch (e: Exception) { Log.w(TAG, "fetch failed", e) } finally { fetching = false }
        }
    }

    private fun fetch(ctx: Context) {
        val have = rules(ctx) ?: return
        val conn = URL(have.source).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        // The default ("Dalvik/2.1.0 (Linux; U; Android 14; CPH2401 ...)")
        // names the phone's model and Android version.
        conn.setRequestProperty("User-Agent", "AppBlock")
        prefs(ctx).getString("etag", null)?.let { conn.setRequestProperty("If-None-Match", it) }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Log.i(TAG, "rules ${have.version} still current")
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val got = Storage.openSignedRules(body)
                    if (got == null) { Log.w(TAG, "fetched rules not signed or unreadable"); return }
                    if (Storage.newerRules(have, got) !== got) { Log.i(TAG, "fetched rules ${got.version}, have ${have.version}"); return }
                    prefs(ctx).edit().putString("signed", body).putString("etag", conn.getHeaderField("ETag")).apply()
                    current = got
                    Log.i(TAG, "rules ${have.version} -> ${got.version}")
                }
                else -> Log.w(TAG, "rules fetch: HTTP ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }
}
