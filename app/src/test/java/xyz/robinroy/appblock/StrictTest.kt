package xyz.robinroy.appblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict mode's decisions: which rule changes give time back (and are
 * locked), the cooldown, the emergency pass, the clock being moved, and
 * which system screens get walled. Times are built around a fixed `now`.
 */
class StrictTest {

    private val Int.min get() = this * 60_000L
    private val now = 1_000_000_000_000L
    private val day = 1440

    private val on = Storage.Strict(on = true, wayOut = Storage.WayOut.COOLDOWN, cooldownMin = 15,
        pass = Rule(5, day))

    // ---- loosening ----

    @Test
    fun `adding an app gives no time, removing one gives it all`() {
        assertFalse(Storage.loosens(null, Rule(5, 120)))
        assertTrue(Storage.loosens(Rule(5, 120), null))
    }

    @Test
    fun `the same rule loosens nothing`() {
        assertFalse(Storage.loosens(Rule(10, 60), Rule(10, 60)))
    }

    @Test
    fun `a bigger allowance loosens, a smaller one doesn't`() {
        assertTrue(Storage.loosens(Rule(10, 60), Rule(11, 60)))
        assertFalse(Storage.loosens(Rule(10, 60), Rule(9, 60)))
    }

    @Test
    fun `a longer window is stricter`() {
        assertFalse(Storage.loosens(Rule(10, 60), Rule(10, 120)))
        assertFalse(Storage.loosens(Rule(10, 60), Rule(10, day)))
    }

    @Test
    fun `a shorter window with the same allowance loosens`() {
        // 10 in any 59 min: 10 at once, then 1 more as the 60th minute starts.
        assertTrue(Storage.loosens(Rule(10, 60), Rule(10, 59)))
        assertTrue(Storage.loosens(Rule(10, day), Rule(10, 60)))
    }

    @Test
    fun `a shorter window with a smaller allowance can loosen`() {
        // 5 in any 20 min allows 15 in an hour, against the old 10.
        assertTrue(Storage.loosens(Rule(10, 60), Rule(5, 20)))
    }

    @Test
    fun `a shorter window with a small enough allowance is stricter`() {
        // 1 in any 30 min is at most 2 in an hour, against 10.
        assertFalse(Storage.loosens(Rule(10, 60), Rule(1, 30)))
        // Exactly the old allowance in the old window: 2 in any 30 against 4 in any 60.
        assertFalse(Storage.loosens(Rule(4, 60), Rule(2, 30)))
    }

    @Test
    fun `bigger allowance in a much longer window can still be stricter`() {
        // 30 min in any week vs 10 in any hour: in one hour, at most... 30. Looser.
        assertTrue(Storage.loosens(Rule(10, 60), Rule(30, 7 * day)))
        // 10 min in any week vs 10 in any hour: never more than 10 in an hour.
        assertFalse(Storage.loosens(Rule(10, 60), Rule(10, 7 * day)))
    }

    @Test
    fun `huge rules don't overflow`() {
        val huge = Rule(99_999 * 60, 99_999 * day)
        assertFalse(Storage.loosens(huge, huge))
        assertTrue(Storage.loosens(Rule(1, 99_999 * day), Rule(2, 99_999 * day)))
        assertEquals(99_999L * 60, Storage.maxUseMin(huge, 99_999L * day))
    }

    @Test
    fun `max use is the allowance per window, plus what fits of the next`() {
        assertEquals(10L, Storage.maxUseMin(Rule(10, 60), 60))
        assertEquals(11L, Storage.maxUseMin(Rule(10, 60), 61))
        assertEquals(25L, Storage.maxUseMin(Rule(10, 60), 125))  // 10, 10, then 5 of the third
        assertEquals(3L, Storage.maxUseMin(Rule(10, 60), 3))
    }

    // ---- cooldown ----

    @Test
    fun `no cooldown until stop is tapped`() {
        assertFalse(Storage.coolingDown(on))
        assertFalse(Storage.cooldownDone(on, now + 100.min))
        assertEquals(0L, Storage.cooldownLeftMs(on, now))
    }

    @Test
    fun `a cooldown counts down its full length, then it's done`() {
        val cooling = Storage.startCooldown(on, now)
        assertTrue(Storage.coolingDown(cooling))
        assertEquals(15.min, Storage.cooldownLeftMs(cooling, now))
        assertEquals(1.min, Storage.cooldownLeftMs(cooling, now + 14.min))
        assertFalse(Storage.cooldownDone(cooling, now + 14.min))
        assertTrue(Storage.cooldownDone(cooling, now + 15.min))
        assertEquals(0L, Storage.cooldownLeftMs(cooling, now + 16.min))
    }

    @Test
    fun `a cancelled cooldown starts from the full length next time`() {
        val cancelled = Storage.cancelCooldown(Storage.startCooldown(on, now))
        assertFalse(Storage.coolingDown(cancelled))
        val again = Storage.startCooldown(cancelled, now + 10.min)
        assertEquals(15.min, Storage.cooldownLeftMs(again, now + 10.min))
    }

    @Test
    fun `a cooldown of days`() {
        val long = Storage.startCooldown(on.copy(cooldownMin = 3 * day), now)
        assertEquals(3L * day * 60_000, Storage.cooldownLeftMs(long, now))
    }

    @Test
    fun `strict mode off never cools down`() {
        val off = Storage.strictEnded(Storage.startCooldown(on, now))
        assertFalse(Storage.coolingDown(off))
        assertFalse(Storage.cooldownDone(off, now + 100.min))
    }

    // ---- turning it off ----

    @Test
    fun `a run-out cooldown unlocks Turn off and leaves strict mode on`() {
        val cooling = Storage.startCooldown(on, now)
        assertFalse(Storage.canTurnOff(cooling, now, pluggedIn = false))
        assertFalse(Storage.canTurnOff(cooling, now + 15.min - 1, pluggedIn = false))
        assertTrue(Storage.canTurnOff(cooling, now + 15.min, pluggedIn = false))
        // It stays unlocked, and strict mode on, however long nobody taps it.
        assertTrue(Storage.canTurnOff(cooling, now + 30 * day.min, pluggedIn = false))
        assertTrue(cooling.on)
        assertTrue(Storage.coolingDown(cooling))
    }

    @Test
    fun `no cooldown, no Turn off, plugged in or not`() {
        assertFalse(Storage.canTurnOff(on, now + 100.min, pluggedIn = false))
        assertFalse(Storage.canTurnOff(on, now + 100.min, pluggedIn = true))
    }

    @Test
    fun `keep it on locks a run-out cooldown again`() {
        val kept = Storage.cancelCooldown(Storage.startCooldown(on, now))
        assertFalse(Storage.canTurnOff(kept, now + 20.min, pluggedIn = false))
        assertFalse(Storage.coolingDown(kept))
        // And the next one waits its full length again.
        val again = Storage.startCooldown(kept, now + 20.min)
        assertFalse(Storage.canTurnOff(again, now + 34.min, pluggedIn = false))
        assertTrue(Storage.canTurnOff(again, now + 35.min, pluggedIn = false))
    }

    @Test
    fun `the plug way unlocks only while plugged in`() {
        val plug = on.copy(wayOut = Storage.WayOut.PLUG)
        assertTrue(Storage.canTurnOff(plug, now, pluggedIn = true))
        assertFalse(Storage.canTurnOff(plug, now, pluggedIn = false))
        // A cooldown means nothing to it.
        assertFalse(Storage.canTurnOff(plug.copy(cooldownEnds = now - 1.min), now, pluggedIn = false))
    }

    @Test
    fun `nothing turns off strict mode that's off`() {
        val off = Storage.strictEnded(Storage.startCooldown(on, now))
        assertFalse(Storage.canTurnOff(off, now + 100.min, pluggedIn = true))
        assertFalse(Storage.canTurnOff(off.copy(wayOut = Storage.WayOut.PLUG), now, pluggedIn = true))
    }

    // ---- the clock (ClockTest has the anchor itself) ----

    private val anchor = Storage.ClockAnchor(wall = now, elapsed = 50.min, boot = 7)

    /** Strict mode after the clocks read `wall` and `elapsed` on boot `boot`, against `anchor`. */
    private fun afterClockCheck(s: Storage.Strict, wall: Long, elapsed: Long, boot: Int) =
        Storage.afterClockMove(s, Storage.clockMoved(anchor, wall, elapsed, boot), wall)

    @Test
    fun `moving the clock forward restarts a counting cooldown`() {
        val cooling = Storage.startCooldown(on, now)
        // 10 minutes in, the clock jumps an hour ahead.
        val after = afterClockCheck(cooling, now + 70.min, 60.min, 7)
        assertEquals(15.min, Storage.cooldownLeftMs(after, now + 70.min))
    }

    @Test
    fun `moving the clock past a counting cooldown's end doesn't unlock Turn off`() {
        val cooling = Storage.startCooldown(on, now)
        // 10 minutes in, an hour ahead: well past the end, but it starts over.
        val after = afterClockCheck(cooling, now + 70.min, 60.min, 7)
        assertFalse(Storage.canTurnOff(after, now + 70.min, pluggedIn = false))
        assertTrue(Storage.canTurnOff(after, now + 85.min, pluggedIn = false))
    }

    @Test
    fun `moving the clock after the cooldown ran out keeps Turn off unlocked`() {
        val cooling = Storage.startCooldown(on, now)
        // 20 minutes in (5 past the end), the clock goes an hour back...
        val back = afterClockCheck(cooling, now - 40.min, 70.min, 7)
        assertTrue(Storage.canTurnOff(back, now - 40.min, pluggedIn = false))
        // ...or an hour ahead.
        val ahead = afterClockCheck(cooling, now + 80.min, 70.min, 7)
        assertTrue(Storage.canTurnOff(ahead, now + 80.min, pluggedIn = false))
    }

    @Test
    fun `a cooldown that runs out on the dot of a move counts as run out`() {
        val cooling = Storage.startCooldown(on, now)
        // Exactly 15 minutes in, the clock goes an hour back.
        val back = afterClockCheck(cooling, now - 45.min, 65.min, 7)
        assertTrue(Storage.canTurnOff(back, now - 45.min, pluggedIn = false))
    }

    @Test
    fun `moving the clock back restarts it too`() {
        val cooling = Storage.startCooldown(on, now)
        val after = afterClockCheck(cooling, now - 50.min, 60.min, 7)
        assertEquals(15.min, Storage.cooldownLeftMs(after, now - 50.min))
    }

    @Test
    fun `an unmoved clock leaves everything`() {
        val cooling = Storage.startCooldown(on, now)
        assertEquals(cooling, afterClockCheck(cooling, now + 10.min, 60.min, 7))
    }

    @Test
    fun `a restart keeps the cooldown`() {
        val cooling = Storage.startCooldown(on, now)
        val after = afterClockCheck(cooling, now + 5.min, 1.min, 8)
        assertEquals(cooling.cooldownEnds, after.cooldownEnds)
    }

    @Test
    fun `moving the clock never refills the pass`() {
        // 4 of the 5 pass minutes used 30 minutes ago.
        val used = on.copy(passUses = listOf((now - 30.min) to (now - 26.min)))
        assertEquals(1.min, Storage.passSavedMs(used, now))
        // A day ahead would age that use out; the log moves with the clock.
        val after = afterClockCheck(used, now + day.min, 50.min, 7)
        assertEquals(1.min, Storage.passSavedMs(after, now + day.min))
    }

    @Test
    fun `moving the clock keeps a counting pass's time`() {
        val counting = Storage.meterPass(Storage.startPass(on, now), true, now)
        val after = afterClockCheck(counting, now + 2.min + 60.min, 52.min, 7)
        assertEquals(3.min, Storage.passLeftMs(after, now + 2.min + 60.min))
        assertTrue(Storage.passCounting(after))
    }

    @Test
    fun `moving the clock leaves strict mode that's off alone`() {
        val off = Storage.Strict(cooldownEnds = now)
        assertEquals(off, Storage.afterClockMove(off, 60.min, now + 60.min))
    }

    // ---- emergency passes ----

    // A pass opened at `now`, counting from a minute later (a blocked app opened).
    private val open get() = Storage.startPass(on, now)
    private val counting get() = Storage.meterPass(open, true, now + 1.min)

    @Test
    fun `no passes, no pass time`() {
        val none = on.copy(pass = null)
        assertEquals(0L, Storage.passSavedMs(none, now))
        assertEquals(none, Storage.startPass(none, now))
    }

    @Test
    fun `an open pass counts nothing until a blocked app is on screen`() {
        assertTrue(Storage.passRunning(open, now))
        assertFalse(Storage.passCounting(open))
        assertEquals(5.min, Storage.passLeftMs(open, now + 60.min))
        assertTrue(Storage.passRunning(open, now + 3 * day.min))
        assertEquals(0L, Storage.passEnds(open, now))
    }

    @Test
    fun `a pass counts down while a blocked app is on screen`() {
        assertTrue(Storage.passCounting(counting))
        assertEquals(3.min, Storage.passLeftMs(counting, now + 3.min))
        assertEquals(now + 6.min, Storage.passEnds(counting, now + 3.min))
        assertTrue(Storage.passRunning(counting, now + 6.min - 1))
        assertFalse(Storage.passRunning(counting, now + 6.min))
    }

    @Test
    fun `a pass pauses when the blocked app leaves the screen`() {
        val paused = Storage.meterPass(counting, false, now + 3.min)
        assertFalse(Storage.passCounting(paused))
        assertTrue(Storage.passOpen(paused))
        assertEquals(listOf((now + 1.min) to (now + 3.min)), paused.passUses)
        assertEquals(3.min, Storage.passLeftMs(paused, now + 30.min))
        // And counts again from where it was.
        val again = Storage.meterPass(paused, true, now + 40.min)
        assertEquals(1.min, Storage.passLeftMs(again, now + 42.min))
    }

    @Test
    fun `a counting pass's time is logged every few seconds`() {
        assertEquals(counting, Storage.meterPass(counting, true, now + 1.min + 1_000))
        val at = now + 1.min + Storage.PASS_CHECKPOINT_MS
        val logged = Storage.meterPass(counting, true, at)
        assertEquals(listOf((now + 1.min) to at), logged.passUses)
        assertEquals(at, logged.passInUse)
        assertEquals(Storage.passLeftMs(counting, at + 1.min), Storage.passLeftMs(logged, at + 1.min))
    }

    // ---- when the pass comes back ----

    @Test
    fun `a whole pass says nothing about refilling`() {
        assertEquals(0L, Storage.passBackInMs(on, now))
        assertEquals(0L, Storage.passFullInMs(on, now))
        assertEquals(null, Storage.passRefillNote(on, now))
        assertEquals(null, Storage.passRefillNote(on.copy(pass = null), now))
    }

    @Test
    fun `a partly used pass says when it's full again`() {
        // 2 of 5 minutes used, ending 4 hours ago: whole again a day after that.
        val used = on.copy(passUses = listOf((now - 4 * 60.min - 2.min) to (now - 4 * 60.min)))
        assertEquals(0L, Storage.passBackInMs(used, now))
        assertEquals(20 * 60.min, Storage.passFullInMs(used, now))
        assertEquals("Full again in 20 hours", Storage.passRefillNote(used, now))
        // Its last use is what counts, not its first.
        val twice = used.copy(passUses = used.passUses + ((now - 60.min) to (now - 59.min)))
        assertEquals("Full again in 23 hours 1 minute", Storage.passRefillNote(twice, now))
    }

    @Test
    fun `a used up pass says when some comes back`() {
        // All 5 minutes, used 22 hours 40 minutes ago: the first comes back in 1 hr 20 min.
        val start = now - day.min + 80.min
        val spent = on.copy(passUses = listOf(start to (start + 5.min)))
        assertEquals(0L, Storage.passSavedMs(spent, now))
        assertEquals(80.min + 1.min, Storage.passBackInMs(spent, now))
        assertEquals("Back in 1 hour 21 minutes", Storage.passRefillNote(spent, now))
        assertEquals(85.min, Storage.passFullInMs(spent, now))
        // A minute and a bit later, some is back: then it says when it's full.
        val later = now + 81.min
        assertTrue(Storage.passSavedMs(spent, later) > 0)
        assertEquals(0L, Storage.passBackInMs(spent, later))
        assertEquals("Full again in 4 minutes", Storage.passRefillNote(spent, later))
        // And once it's all out of the window, nothing.
        assertEquals(null, Storage.passRefillNote(spent, now + 85.min))
    }

    @Test
    fun `a pass used up in pieces comes back with its oldest piece`() {
        // 3 minutes 10 hours ago, 2 minutes an hour ago: the 3 come back first.
        val spent = on.copy(passUses = listOf(
            (now - 10 * 60.min) to (now - 10 * 60.min + 3.min),
            (now - 60.min) to (now - 58.min)))
        assertEquals(0L, Storage.passSavedMs(spent, now))
        assertEquals("Back in 14 hours 1 minute", Storage.passRefillNote(spent, now))
        assertEquals("Full again in 9 hours 1 minute", Storage.passRefillNote(spent, now + 14 * 60.min + 1.min))
    }

    @Test
    fun `a counting pass says nothing, its refill keeps moving`() {
        assertEquals(null, Storage.passRefillNote(counting, now + 3.min))
        // Paused, it says again.
        val paused = Storage.meterPass(counting, false, now + 3.min)
        assertEquals("Full again in 1 day", Storage.passRefillNote(paused, now + 3.min))
    }

    @Test
    fun `stopping early saves the rest`() {
        val stopped = Storage.stopPass(counting, now + 3.min)
        assertFalse(Storage.passOpen(stopped))
        assertFalse(Storage.passRunning(stopped, now + 3.min))
        assertEquals(3.min, Storage.passSavedMs(stopped, now + 3.min))
        // Used again: 3 minutes left.
        val again = Storage.startPass(stopped, now + 10.min)
        assertEquals(3.min, Storage.passLeftMs(again, now + 10.min))
    }

    @Test
    fun `a pass stopped late only logs up to when it ran out`() {
        val stopped = Storage.stopPass(counting, now + 30.min)
        assertEquals(listOf((now + 1.min) to (now + 6.min)), stopped.passUses)
        assertEquals(0L, Storage.passSavedMs(stopped, now + 30.min))
    }

    @Test
    fun `a pass that pauses late only logs up to when it ran out`() {
        val paused = Storage.meterPass(counting, false, now + 30.min)
        assertEquals(listOf((now + 1.min) to (now + 6.min)), paused.passUses)
    }

    @Test
    fun `a used-up pass can't start`() {
        val spent = Storage.stopPass(counting, now + 6.min)
        assertEquals(spent, Storage.startPass(spent, now + 7.min))
    }

    @Test
    fun `a pass refills as old use leaves its window`() {
        val spent = Storage.stopPass(counting, now + 6.min)
        assertEquals(0L, Storage.passSavedMs(spent, now + 7.min))
        assertEquals(2.min, Storage.passSavedMs(spent, now + day.min + 3.min))
        assertEquals(5.min, Storage.passSavedMs(spent, now + day.min + 6.min))
    }

    @Test
    fun `a partial pass gives what's left`() {
        val partly = on.copy(passUses = listOf((now - 60.min) to (now - 56.min)))
        assertEquals(1.min, Storage.passLeftMs(Storage.startPass(partly, now), now))
    }

    @Test
    fun `starting an open pass again changes nothing`() {
        assertEquals(open, Storage.startPass(open, now + 1.min))
        assertEquals(counting, Storage.startPass(counting, now + 2.min))
    }

    @Test
    fun `no pass while strict mode is off`() {
        val off = on.copy(on = false)
        assertEquals(off, Storage.startPass(off, now))
        assertFalse(Storage.passRunning(off.copy(passStarted = now), now))
        assertFalse(Storage.passCounting(Storage.meterPass(off.copy(passStarted = now), true, now)))
    }

    @Test
    fun `a blocked app counts nothing without an open pass`() {
        assertEquals(on, Storage.meterPass(on, true, now))
    }

    @Test
    fun `a restart drops the seconds not logged yet`() {
        val logged = Storage.meterPass(counting, true, now + 1.min + Storage.PASS_CHECKPOINT_MS)
        val restarted = Storage.passAfterRestart(logged)
        assertFalse(Storage.passCounting(restarted))
        assertTrue(Storage.passOpen(restarted))
        assertEquals(logged.passUses, restarted.passUses)
        assertEquals(open, Storage.passAfterRestart(open))
    }

    @Test
    fun `ending strict mode drops what's running and keeps the choices`() {
        val busy = Storage.meterPass(Storage.startPass(Storage.startCooldown(on, now), now), true, now)
        val ended = Storage.strictEnded(busy)
        assertFalse(ended.on)
        assertEquals(0L, ended.cooldownEnds)
        assertEquals(0L, ended.passStarted)
        assertEquals(0L, ended.passInUse)
        assertEquals(on.wayOut, ended.wayOut)
        assertEquals(on.cooldownMin, ended.cooldownMin)
        assertEquals(on.pass, ended.pass)
    }

    // ---- the Settings wall ----

    // Texts read off the real pages on the Android 14 emulator.
    private val appInfo = listOf("Moly", "Open", "Uninstall", "Force stop", "Notifications")
    private val accessibility = listOf("Use Moly", "Options", "Moly shortcut", "Off")
    private val stopDialog = listOf("Stop Moly?", "Tapping Stop will stop Moly.", "Cancel", "Stop")
    private val forceStopDialog = listOf("Force stop?", "If you force stop an app, it may misbehave.", "Cancel", "OK")
    private val clearStorageDialog = listOf("Delete app data?",
        "This app’s data, including files and settings, will be permanently deleted from this device", "Cancel", "Delete")
    private val safetyCard = listOf("Review app with full device access", "Moly", "Remove access")
    private val wifi = listOf("Internet", "AndroidWifi", "Connected", "Network preferences")

    @Test
    fun `screens that name Moly`() {
        for (page in listOf(appInfo, accessibility, stopDialog, safetyCard))
            assertTrue(page.toString(), Storage.namesApp(page, "Moly"))
        assertTrue(Storage.namesApp(listOf("moly"), "Moly"))
    }

    @Test
    fun `screens that don't`() {
        for (page in listOf(forceStopDialog, clearStorageDialog, wifi, emptyList()))
            assertFalse(page.toString(), Storage.namesApp(page, "Moly"))
    }

    private val settings = "com.android.settings"
    private val installer = "com.google.android.packageinstaller"

    @Test
    fun `a screen naming Moly is walled`() {
        assertEquals(setOf(settings), Storage.strictWalls(emptySet(), setOf(settings), setOf(settings)))
    }

    @Test
    fun `the wall stays while the app is on screen, whatever it shows`() {
        // App info, then its Force stop dialog (which doesn't name Moly).
        assertEquals(setOf(settings), Storage.strictWalls(setOf(settings), setOf(settings), emptySet()))
    }

    @Test
    fun `the wall goes once the app leaves the screen`() {
        assertEquals(emptySet<String>(), Storage.strictWalls(setOf(settings), emptySet(), emptySet()))
        assertEquals(setOf(installer), Storage.strictWalls(setOf(settings), setOf(installer), setOf(installer)))
    }

    @Test
    fun `a strict wall wins over the others`() {
        val kinds = Storage.wallKinds(setOf(settings), setOf(settings), setOf(settings))
        assertEquals(Storage.WallKind.STRICT, kinds[settings])
    }

    // ---- storage ----

    @Test
    fun `strict mode round-trips through JSON`() {
        val busy = Storage.meterPass(Storage.startPass(Storage.startCooldown(on, now), now), true, now + 1.min).copy(
            passUses = listOf((now - 60.min) to (now - 58.min)), wayOut = Storage.WayOut.PLUG)
        assertEquals(now + 1.min, busy.passInUse)
        assertEquals(busy, Storage.strictFromJson(Storage.strictToJson(busy)))
        val plain = Storage.Strict(pending = true)
        assertEquals(plain, Storage.strictFromJson(Storage.strictToJson(plain)))
    }

    @Test
    fun `no strict mode stored means off`() {
        assertEquals(Storage.Strict(), Storage.strictFromJson(null))
        assertEquals(Storage.Strict(), Storage.strictFromJson("not json"))
        assertFalse(Storage.strictFromJson("{}").on)
    }

    // ---- the countdown's face ----

    @Test
    fun `countdowns read like a clock`() {
        assertEquals("15:00", Storage.fmtClock(15.min))
        assertEquals("4:05", Storage.fmtClock(4.min + 5_000))
        assertEquals("0:01", Storage.fmtClock(1))           // never 0:00 while time is left
        assertEquals("0:00", Storage.fmtClock(0))
        assertEquals("0:00", Storage.fmtClock(-5_000))
        assertEquals("1:02:03", Storage.fmtClock(62.min + 3_000))
        assertEquals("3d 4:05:06", Storage.fmtClock(3L * day.min + 245.min + 6_000))
    }
}
