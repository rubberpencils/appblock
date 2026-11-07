package xyz.robinroy.appblock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Catches the wall clock being moved (Settings > Date & time, automatic
 * time off) and moves everything time-stamped with it: the usage logs, so
 * setting the clock never gives time back (issue #7), and strict mode's
 * cooldown and emergency pass. The decisions are in Storage (clockMoved,
 * nextAnchor, shiftLog, afterClockMove).
 *
 * Run before anything reads or writes those times: the blocker looks on
 * every refresh and tick, and Strict.check (the screens, the TIME_SET
 * receiver) starts with it. All of them run on the main thread, so a move
 * is never shifted twice.
 */
object Clock {
    private const val TAG = "Clock"

    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /** How far the clock moved since the last look (0 if it didn't), with every stored time moved as far. */
    fun check(ctx: Context): Long {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = boot(ctx)
        val anchor = Storage.loadClockAnchor(ctx)
        val moved = Storage.clockMoved(anchor, wall, elapsed, boot)
        val next = Storage.nextAnchor(anchor, wall, elapsed, boot)
        // The anchor first: a crash between the two loses a shift rather than doubling it.
        if (next != anchor) Storage.saveClockAnchor(ctx, next)
        if (moved != 0L) {
            Log.i(TAG, "the clock moved ${moved / 1000} s: the usage logs and strict mode move with it")
            Storage.shiftUsage(ctx, moved)
            Strict.clockMoved(ctx, moved, wall)
        }
        return moved
    }
}
