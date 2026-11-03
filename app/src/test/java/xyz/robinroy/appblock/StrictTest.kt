package xyz.robinroy.appblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        pass = Rule(5, day), clock = Storage.ClockAnchor(now, 50.min, 7))

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

    // ---- the clock ----

    private val anchor = Storage.ClockAnchor(wall = now, elapsed = 50.min, boot = 7)

    @Test
    fun `clocks that agree haven't moved`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min, 80.min, 7))
    }

    @Test
    fun `small network corrections are ignored`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min + 50_000, 80.min, 7))
        assertEquals(0L, Storage.clockMoved(anchor, now + 30.min - 50_000, 80.min, 7))
    }

    @Test
    fun `a clock set forward or back is caught`() {
        assertEquals(2 * 60.min, Storage.clockMoved(anchor, now + 30.min + 2 * 60.min, 80.min, 7))
        assertEquals(-60.min, Storage.clockMoved(anchor, now + 30.min - 60.min, 80.min, 7))
    }

    @Test
    fun `after a restart the clocks can't be compared`() {
        assertEquals(0L, Storage.clockMoved(anchor, now + 5 * 60.min, 1.min, 8))
        assertEquals(0L, Storage.clockMoved(null, now, 1.min, 8))
    }

    @Test
    fun `moving the clock forward restarts a counting cooldown`() {
        val cooling = Storage.startCooldown(on, now)
        // 10 minutes in, the clock jumps an hour ahead.
        val after = Storage.afterClockCheck(cooling, now + 70.min, 60.min, 7)
        assertEquals(15.min, Storage.cooldownLeftMs(after, now + 70.min))
        assertEquals(Storage.ClockAnchor(now + 70.min, 60.min, 7), after.clock)
    }

    @Test
    fun `moving the clock back restarts it too`() {
        val cooling = Storage.startCooldown(on, now)
        val after = Storage.afterClockCheck(cooling, now - 50.min, 60.min, 7)
        assertEquals(15.min, Storage.cooldownLeftMs(after, now - 50.min))
    }

    @Test
    fun `an unmoved clock leaves everything, anchor included`() {
        val cooling = Storage.startCooldown(on, now)
        assertEquals(cooling, Storage.afterClockCheck(cooling, now + 10.min, 60.min, 7))
    }

    @Test
    fun `small moves add up against the first anchor`() {
        val cooling = Storage.startCooldown(on, now)
        // 40 s ahead: under the slack, nothing changes, the anchor stays.
        val first = Storage.afterClockCheck(cooling, now + 40_000, 50.min, 7)
        assertEquals(cooling, first)
        // Another 40 s: 80 s off the first anchor, caught.
        val second = Storage.afterClockCheck(first, now + 80_000, 50.min, 7)
        assertEquals(15.min, Storage.cooldownLeftMs(second, now + 80_000))
    }

    @Test
    fun `a restart sets a new anchor and keeps the cooldown`() {
        val cooling = Storage.startCooldown(on, now)
        val after = Storage.afterClockCheck(cooling, now + 5.min, 1.min, 8)
        assertEquals(cooling.cooldownEnds, after.cooldownEnds)
        assertEquals(Storage.ClockAnchor(now + 5.min, 1.min, 8), after.clock)
    }

    @Test
    fun `moving the clock never refills the pass`() {
        // 4 of the 5 pass minutes used 30 minutes ago.
        val used = on.copy(passUses = listOf((now - 30.min) to (now - 26.min)))
        assertEquals(1.min, Storage.passSavedMs(used, now))
        // A day ahead would age that use out; the log moves with the clock.
        val after = Storage.afterClockCheck(used, now + day.min, 50.min, 7)
        assertEquals(1.min, Storage.passSavedMs(after, now + day.min))
    }

    @Test
    fun `moving the clock keeps a running pass's time`() {
        val running = Storage.startPass(on, now)
        val after = Storage.afterClockCheck(running, now + 2.min + 60.min, 52.min, 7)
        assertEquals(3.min, Storage.passLeftMs(after, now + 2.min + 60.min))
    }

    @Test
    fun `the clock isn't watched while strict mode is off`() {
        val off = Storage.Strict()
        assertEquals(off, Storage.afterClockCheck(off, now, 1.min, 1))
    }

    @Test
    fun `the first look sets the anchor`() {
        val unanchored = on.copy(clock = null)
        assertEquals(Storage.ClockAnchor(now, 1.min, 3), Storage.afterClockCheck(unanchored, now, 1.min, 3).clock)
    }

    // ---- emergency passes ----

    @Test
    fun `no passes, no pass time`() {
        val none = on.copy(pass = null)
        assertEquals(0L, Storage.passSavedMs(none, now))
        assertEquals(none, Storage.startPass(none, now))
    }

    @Test
    fun `a pass runs for all the time saved`() {
        val running = Storage.startPass(on, now)
        assertTrue(Storage.passRunning(running, now))
        assertEquals(5.min, Storage.passLeftMs(running, now))
        assertEquals(now + 5.min, Storage.passEnds(running))
        assertTrue(Storage.passRunning(running, now + 5.min - 1))
        assertFalse(Storage.passRunning(running, now + 5.min))
    }

    @Test
    fun `stopping early saves the rest`() {
        val stopped = Storage.stopPass(Storage.startPass(on, now), now + 2.min)
        assertFalse(Storage.passRunning(stopped, now + 2.min))
        assertEquals(3.min, Storage.passSavedMs(stopped, now + 2.min))
        // Used again: it runs for the 3 minutes left.
        val again = Storage.startPass(stopped, now + 10.min)
        assertEquals(3.min, Storage.passLeftMs(again, now + 10.min))
    }

    @Test
    fun `a pass stopped late only logs up to when it ran out`() {
        val stopped = Storage.stopPass(Storage.startPass(on, now), now + 30.min)
        assertEquals(listOf(now to now + 5.min), stopped.passUses)
        assertEquals(0L, Storage.passSavedMs(stopped, now + 30.min))
    }

    @Test
    fun `a used-up pass can't start`() {
        val spent = Storage.stopPass(Storage.startPass(on, now), now + 5.min)
        assertEquals(spent, Storage.startPass(spent, now + 6.min))
    }

    @Test
    fun `a pass refills as old use leaves its window`() {
        val spent = Storage.stopPass(Storage.startPass(on, now), now + 5.min)
        assertEquals(0L, Storage.passSavedMs(spent, now + 6.min))
        assertEquals(2.min, Storage.passSavedMs(spent, now + day.min + 2.min))
        assertEquals(5.min, Storage.passSavedMs(spent, now + day.min + 5.min))
    }

    @Test
    fun `a partial pass gives what's left`() {
        val partly = on.copy(passUses = listOf((now - 60.min) to (now - 56.min)))
        val running = Storage.startPass(partly, now)
        assertEquals(1.min, Storage.passLeftMs(running, now))
    }

    @Test
    fun `starting a running pass again changes nothing`() {
        val running = Storage.startPass(on, now)
        assertEquals(running, Storage.startPass(running, now + 1.min))
    }

    @Test
    fun `no pass while strict mode is off`() {
        val off = on.copy(on = false)
        assertEquals(off, Storage.startPass(off, now))
        assertFalse(Storage.passRunning(off.copy(passStarted = now), now))
    }

    @Test
    fun `ending strict mode drops what's running and keeps the choices`() {
        val busy = Storage.startPass(Storage.startCooldown(on, now), now)
        val ended = Storage.strictEnded(busy)
        assertFalse(ended.on)
        assertEquals(0L, ended.cooldownEnds)
        assertEquals(0L, ended.passStarted)
        assertNull(ended.clock)
        assertEquals(on.wayOut, ended.wayOut)
        assertEquals(on.cooldownMin, ended.cooldownMin)
        assertEquals(on.pass, ended.pass)
    }

    // ---- the Settings wall ----

    // Texts read off the real pages on the Android 14 emulator.
    private val appInfo = listOf("AppBlock", "Open", "Uninstall", "Force stop", "Notifications")
    private val accessibility = listOf("Use AppBlock", "Options", "AppBlock shortcut", "Off")
    private val stopDialog = listOf("Stop AppBlock?", "Tapping Stop will stop AppBlock.", "Cancel", "Stop")
    private val forceStopDialog = listOf("Force stop?", "If you force stop an app, it may misbehave.", "Cancel", "OK")
    private val clearStorageDialog = listOf("Delete app data?",
        "This app’s data, including files and settings, will be permanently deleted from this device", "Cancel", "Delete")
    private val safetyCard = listOf("Review app with full device access", "AppBlock", "Remove access")
    private val wifi = listOf("Internet", "AndroidWifi", "Connected", "Network preferences")

    @Test
    fun `screens that name AppBlock`() {
        for (page in listOf(appInfo, accessibility, stopDialog, safetyCard))
            assertTrue(page.toString(), Storage.namesApp(page, "AppBlock"))
        assertTrue(Storage.namesApp(listOf("appblock"), "AppBlock"))
    }

    @Test
    fun `screens that don't`() {
        for (page in listOf(forceStopDialog, clearStorageDialog, wifi, emptyList()))
            assertFalse(page.toString(), Storage.namesApp(page, "AppBlock"))
    }

    private val settings = "com.android.settings"
    private val installer = "com.google.android.packageinstaller"

    @Test
    fun `a screen naming AppBlock is walled`() {
        assertEquals(setOf(settings), Storage.strictWalls(emptySet(), setOf(settings), setOf(settings)))
    }

    @Test
    fun `the wall stays while the app is on screen, whatever it shows`() {
        // App info, then its Force stop dialog (which doesn't name AppBlock).
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
        val busy = Storage.startPass(Storage.startCooldown(on, now), now).copy(
            passUses = listOf((now - 60.min) to (now - 58.min)), wayOut = Storage.WayOut.PLUG)
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
}
