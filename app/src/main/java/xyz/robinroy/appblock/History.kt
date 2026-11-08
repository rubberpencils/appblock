package xyz.robinroy.appblock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * AppBlock's own screen-time history: every app session, kept for good in
 * a database on the phone (Storage.HISTORY_DB, left out of Auto Backup).
 * Android keeps its usage events for a week or so; a sync copies the new
 * ones in, as sessions (Storage.sessionsFrom), merged with what's stored
 * (Storage.mergeSessions), so reading the same events twice is harmless.
 * It runs by itself: hourly from the blocker service, and when the home
 * screen opens. Needs Usage access; without it nothing is read, and
 * nothing is lost while Android still has the events.
 *
 * Every database read and write happens on one background thread, and
 * nothing that goes wrong there may escape: it would take the blocker,
 * which runs in the same process, down with it.
 */
object History {
    private const val TAG = "History"

    /** How often the blocker service syncs; showing today syncs too, at most this often. */
    const val SERVICE_SYNC_MS = 60 * 60_000L
    const val SCREEN_SYNC_MS = 30_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "history").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Off in the end-to-end tests, which seed the history themselves. */
    @Volatile var autoSync = true

    // The sessions on screen at the last sync, not stored until they end: today's chart shows them.
    @Volatile private var open: List<Storage.Session> = emptyList()

    private var helper: Db? = null
    private fun db(ctx: Context): SQLiteDatabase =
        (helper ?: Db(ctx.applicationContext).also { helper = it }).writableDatabase

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, Storage.HISTORY_DB, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE apps(id INTEGER PRIMARY KEY, pkg TEXT NOT NULL UNIQUE)")
            // Wall-clock ms. Not unique: shifting for a clock move updates rows one by one.
            db.execSQL("CREATE TABLE sessions(app INTEGER NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX sessions_start ON sessions(start)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    /** Run `work` on the history thread; anything it throws is logged, never raised. */
    private fun run(work: () -> Unit) {
        try {
            worker.execute { try { work() } catch (e: Throwable) { Log.w(TAG, "history: ${e.message}", e) } }
        } catch (e: Exception) { Log.w(TAG, "history thread refused work", e) }
    }

    /** A sync, if one is due (at least `gapMs` since the last). Returns at once. */
    fun syncIfDue(ctx: Context, gapMs: Long) {
        if (!autoSync) return
        val app = ctx.applicationContext
        run { if (due(app, gapMs)) sync(app) }
    }

    private fun due(ctx: Context, gapMs: Long): Boolean {
        val db = db(ctx)
        return Storage.syncDue(meta(db, "lastElapsed") ?: 0, (meta(db, "lastBoot") ?: 0).toInt(),
            SystemClock.elapsedRealtime(), boot(ctx), gapMs)
    }

    /** One day of history, as of now. */
    data class Day(val day: LocalDate, val sessions: List<Storage.Session>, val from: Long, val to: Long,
                   val upTo: Long, val firstDay: LocalDate?)

    /**
     * The sessions of `day` (cut to it by the reader), then `done` with them
     * on the main thread. Today's comes after a sync, with what's on screen
     * now; null if the history couldn't be read.
     */
    fun loadDay(ctx: Context, day: LocalDate, zone: ZoneId, done: (Day?) -> Unit) {
        val app = ctx.applicationContext
        run {
            var result: Day? = null
            try {
                val today = LocalDate.now(zone)
                if (day == today && autoSync && due(app, SCREEN_SYNC_MS)) sync(app)
                val (from, to) = Storage.dayBounds(day, zone)
                val db = db(app)
                val stored = mutableListOf<Storage.Session>()
                db.rawQuery("SELECT a.pkg, s.start, s.stop FROM sessions s JOIN apps a ON a.id = s.app " +
                    "WHERE s.start >= ? AND s.start < ? AND s.stop > ? ORDER BY s.start",
                    arrayOf((from - Storage.MAX_SESSION_MS).toString(), to.toString(), from.toString())).use { c ->
                    while (c.moveToNext()) stored += Storage.Session(c.getString(0), c.getLong(1), c.getLong(2))
                }
                val now = System.currentTimeMillis()
                val sessions = if (day == today) stored + open.map { it.copy(end = minOf(it.end, now)) } else stored
                val first = db.rawQuery("SELECT MIN(start) FROM sessions", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
                val firstDay = first?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                result = Day(day, sessions, from, to, minOf(to, now), firstDay)
            } finally {
                val r = result
                main.post { done(r) }
            }
        }
    }

    // ------------------------------------------------------------ the sync

    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun sync(ctx: Context) {
        if (!usageAccessGranted(ctx)) return
        val db = db(ctx)
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = boot(ctx)

        // The clock moved since the last sync: Android moved its events, so this boot's history moves too.
        val anchor = loadAnchor(db)
        Storage.historyShift(anchor, wall, elapsed, boot)?.let { (from, by) ->
            Log.i(TAG, "the clock moved ${by / 1000} s: this boot's history moves with it")
            tx(db) {
                db.execSQL("UPDATE sessions SET start = start + ?, stop = stop + ? WHERE start >= ?", arrayOf(by, by, from))
                meta(db, "safePoint")?.let { if (it >= from) setMeta(db, "safePoint", it + by) }
                // With the shift, or a failure before the end would shift it again next time.
                setAnchor(db, Storage.nextAnchor(anchor, wall, elapsed, boot))
            }
        }

        val from = Storage.syncFrom(meta(db, "safePoint"), wall)
        val events = read(ctx, from, wall)
        // Usage access taken away meanwhile: what came back may be nothing, not "nothing happened".
        if (!usageAccessGranted(ctx)) return
        val derived = Storage.sessionsFrom(events, wall, ignored(ctx), Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        tx(db) {
            store(db, derived.closed)
            setMeta(db, "safePoint", derived.safePoint)
            setAnchor(db, Storage.nextAnchor(loadAnchor(db), wall, elapsed, boot))
            setMeta(db, "lastElapsed", elapsed)
            setMeta(db, "lastBoot", boot.toLong())
        }
        open = derived.open
    }

    /** The event kinds sessionsFrom reads; the rest aren't kept in memory. */
    private val KINDS = setOf(Storage.EV_RESUMED, Storage.EV_PAUSED, Storage.EV_STOPPED, Storage.EV_SCREEN_OFF,
        Storage.EV_SHUTDOWN, Storage.EV_STARTUP)

    private fun read(ctx: Context, from: Long, to: Long): List<Storage.UsageEv> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(from, to) ?: return emptyList()
        val out = ArrayList<Storage.UsageEv>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType in KINDS) out += Storage.UsageEv(e.timeStamp, e.eventType, e.packageName ?: "", e.className)
        }
        return out
    }

    /**
     * What isn't an app's screen time: the home screen and the system UI.
     * The launcher in use only: Settings can stand in as a home screen too
     * (its FallbackHome), and its time is real.
     */
    private fun ignored(ctx: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = try {
            ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        } catch (e: Exception) { null }
        // "android": no launcher chosen yet, the chooser answered.
        return setOfNotNull(launcher?.takeIf { it != "android" }, "com.android.systemui")
    }

    /** New sessions in, merged with the stored ones they touch. */
    private fun store(db: SQLiteDatabase, sessions: List<Storage.Session>) {
        for ((pkg, own) in sessions.groupBy { it.pkg }) {
            val app = appId(db, pkg)
            val lo = own.minOf { it.start } - Storage.MERGE_GAP_MS
            val hi = own.maxOf { it.end } + Storage.MERGE_GAP_MS
            val touching = mutableListOf<Pair<Long, Storage.Session>>()
            db.rawQuery("SELECT rowid, start, stop FROM sessions WHERE app = ? AND start >= ? AND start <= ? AND stop >= ?",
                arrayOf(app.toString(), (lo - Storage.MAX_SESSION_MS).toString(), hi.toString(), lo.toString())).use { c ->
                while (c.moveToNext()) touching += c.getLong(0) to Storage.Session(pkg, c.getLong(1), c.getLong(2))
            }
            for ((rowid, _) in touching) db.delete("sessions", "rowid = ?", arrayOf(rowid.toString()))
            for (s in Storage.mergeSessions(touching.map { it.second } + own)) {
                db.insert("sessions", null, ContentValues().apply {
                    put("app", app); put("start", s.start); put("stop", s.end)
                })
            }
        }
    }

    private fun appId(db: SQLiteDatabase, pkg: String): Long {
        db.rawQuery("SELECT id FROM apps WHERE pkg = ?", arrayOf(pkg)).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return db.insert("apps", null, ContentValues().apply { put("pkg", pkg) })
    }

    private fun loadAnchor(db: SQLiteDatabase): Storage.ClockAnchor? = meta(db, "anchorWall")?.let {
        Storage.ClockAnchor(it, meta(db, "anchorElapsed") ?: 0, (meta(db, "anchorBoot") ?: 0).toInt())
    }

    private fun setAnchor(db: SQLiteDatabase, a: Storage.ClockAnchor) {
        setMeta(db, "anchorWall", a.wall)
        setMeta(db, "anchorElapsed", a.elapsed)
        setMeta(db, "anchorBoot", a.boot.toLong())
    }

    private fun meta(db: SQLiteDatabase, key: String): Long? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun setMeta(db: SQLiteDatabase, key: String, value: Long) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun tx(db: SQLiteDatabase, work: () -> Unit) {
        db.beginTransaction()
        try { work(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    // ------------------------------------------------------------ for tests

    /** Blocks until the history thread has run `work`; for the end-to-end tests. */
    private fun <T> await(work: () -> T): T = worker.submit(work).get()

    /** The history emptied, and the open sessions forgotten. */
    fun resetForTests(ctx: Context) = await {
        val db = db(ctx.applicationContext)
        db.delete("sessions", null, null)
        db.delete("apps", null, null)
        db.delete("meta", null, null)
        open = emptyList()
    }

    /** Sessions stored as if synced. */
    fun insertForTests(ctx: Context, sessions: List<Storage.Session>) = await {
        val db = db(ctx.applicationContext)
        tx(db) { store(db, sessions) }
    }

    /** Everything stored, oldest first. */
    fun allForTests(ctx: Context): List<Storage.Session> = await {
        val out = mutableListOf<Storage.Session>()
        db(ctx.applicationContext).rawQuery("SELECT a.pkg, s.start, s.stop FROM sessions s JOIN apps a ON a.id = s.app " +
            "ORDER BY s.start, a.pkg", null).use { c ->
            while (c.moveToNext()) out += Storage.Session(c.getString(0), c.getLong(1), c.getLong(2))
        }
        out
    }

    /** A sync now, waited for, whether auto-sync is on or not. */
    fun syncForTests(ctx: Context) = await { sync(ctx.applicationContext) }
}
