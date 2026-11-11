package xyz.robinroy.appblock

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.robinroy.appblock.E2e.CLOCK
import xyz.robinroy.appblock.E2e.PHONE
import xyz.robinroy.appblock.E2e.appears
import xyz.robinroy.appblock.E2e.ctx
import xyz.robinroy.appblock.E2e.device
import xyz.robinroy.appblock.E2e.freshState
import xyz.robinroy.appblock.E2e.homeScreenPackage
import xyz.robinroy.appblock.E2e.min
import xyz.robinroy.appblock.E2e.openApp
import xyz.robinroy.appblock.E2e.openHome
import xyz.robinroy.appblock.E2e.shell
import xyz.robinroy.appblock.E2e.text
import xyz.robinroy.appblock.E2e.textContains
import xyz.robinroy.appblock.E2e.waitFor
import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * The home-screen widget: today's screen time on it, what a small one
 * leaves out, keeping up as apps come and go, opening the Usage page,
 * what it says without usage access, and the Usage page handing it to
 * the launcher.
 *
 * The test is the launcher here: it places the real widget through
 * Android's widget service (as adb may, `appwidget grantbind`), on a
 * screen of Moly's, where it can be looked at and tapped. So what's
 * tested is the widget Android would put on any home screen, without
 * leaving one on the emulator's: a widget's views say they are Moly's,
 * and one left behind would look like Moly on screen to every later test.
 */
@RunWith(AndroidJUnit4::class)
class WidgetE2eTest {

    private var host: AppWidgetHost? = null
    private var widget: AppWidgetHostView? = null

    @get:Rule(order = 0)
    val leave = E2e.Cleanup {
        // The widget goes with its host: none is left placed.
        try { host?.stopListening(); host?.deleteHost() } catch (e: Exception) {}
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        device.pressHome()
    }

    @get:Rule(order = 1)
    val screenshotOnFailure = E2e.ScreenshotOnFailure()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = AppWidgetManager.getInstance(ctx)
    private val zone = ZoneId.systemDefault()

    private fun size(widthDp: Int, heightDp: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }

    /**
     * The widget placed at `widthDp` by `heightDp`, as a launcher places
     * one, and put alone on a screen of Moly's. Returns its id.
     */
    private fun place(widthDp: Int, heightDp: Int): Int {
        shell("appwidget grantbind --package ${ctx.packageName} --user 0")
        val hosting = AppWidgetHost(ctx, 0x4d6f).also { host = it }
        // One left by a run that was cut short goes first.
        hosting.deleteHost()
        val id = hosting.allocateAppWidgetId()
        check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(ctx, ScreenTimeWidget::class.java), size(widthDp, heightDp))) {
            "Android didn't let the test place the widget"
        }
        hosting.startListening()
        val screen: Activity = instrumentation.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.runOnMainSync {
            val view = hosting.createView(screen, id, manager.getAppWidgetInfo(id)).also { widget = it }
            screen.setContentView(FrameLayout(screen).apply {
                setBackgroundColor(screen.getColor(R.color.canvas))
                addView(view, FrameLayout.LayoutParams(Ui.dp(screen, widthDp), Ui.dp(screen, heightDp), Gravity.CENTER))
            })
        }
        return id
    }

    /** The user drags the widget's corner: the launcher tells Android its new size, and lays it out at it. */
    private fun resize(id: Int, widthDp: Int, heightDp: Int) {
        manager.updateAppWidgetOptions(id, size(widthDp, heightDp))
        instrumentation.runOnMainSync {
            widget!!.layoutParams = FrameLayout.LayoutParams(Ui.dp(ctx, widthDp), Ui.dp(ctx, heightDp), Gravity.CENTER)
        }
    }

    /** What the placed widget holds now, on screen or not: its texts, and which parts show. */
    private class Held(val total: String, val limited: String?, val chart: BitmapDrawable?, val asks: Boolean)

    private fun held(): Held {
        lateinit var out: Held
        instrumentation.runOnMainSync {
            val w = widget!!
            fun shows(id: Int) = w.findViewById<View>(id)?.visibility == View.VISIBLE
            out = Held(
                if (shows(R.id.widget_total)) w.findViewById<TextView>(R.id.widget_total).text.toString() else "",
                if (shows(R.id.widget_legend)) w.findViewById<TextView>(R.id.widget_limited).text.toString() else null,
                if (shows(R.id.widget_chart)) w.findViewById<ImageView>(R.id.widget_chart).drawable as? BitmapDrawable else null,
                shows(R.id.widget_ask))
        }
        return out
    }

    /** `pkg` on screen from `from` to `to` minutes ago. */
    private fun ago(pkg: String, from: Int, to: Int): Storage.Session {
        val now = System.currentTimeMillis()
        return Storage.Session(pkg, now - from.min, now - to.min)
    }

    /** What those sessions come to today (all of it, but for a run in the day's first hour), as the widget words it. */
    private fun today(sessions: List<Storage.Session>): String {
        val (from, to) = Storage.dayBounds(LocalDate.now(zone), zone)
        return Storage.fmtScreenTime(Storage.screenTimeMs(sessions, from, to))
    }

    @Test
    fun widget_showsToday_fitsItsSize_andOpensTheUsagePage() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 1440)))
        // Clock (limited) for 20 minutes, then Phone for 10.
        val seeded = listOf(ago(CLOCK, 50, 30), ago(PHONE, 15, 5))
        History.insertForTests(ctx, seeded)
        val id = place(300, 200)

        // Four cells by two or so: the total, the day's chart, the limited apps' time.
        E2e.waitForFresh(text("Screen time"))
        E2e.waitForFresh(text("Today"))
        E2e.waitForFresh(text(today(seeded)))
        val limited = "Limited apps: " + today(seeded.filter { it.pkg == CLOCK }).replaceFirstChar { it.lowercase() }
        E2e.waitForFresh(text(limited))
        E2e.waitForFresh(By.desc("Screen time today: ${today(seeded).replaceFirstChar { it.lowercase() }}. $limited."))
        val chart = held().chart
        assertTrue("no chart in a widget with room for one", chart != null)
        assertEquals("the chart is drawn at the widget's width, less its padding",
            Ui.dp(ctx, 300 - Storage.WIDGET_SIDES_DP), chart!!.bitmap.width)

        // Made two cells by one: the total alone, still on its one line.
        resize(id, 110, 40)
        assertTrue("a small widget kept its chart or legend",
            E2e.eventually { held().let { it.chart == null && it.limited == null && it.total == today(seeded) } })
        E2e.waitForFresh(text(today(seeded)))
        assertFalse(appears(text(limited), timeout = 500))

        // And back, taller than before: all of it again.
        resize(id, 300, 260)
        assertTrue(E2e.eventually { held().let { it.chart != null && it.limited == limited } })

        // A tap anywhere on it opens the Usage page, on today.
        E2e.waitForFresh(text(today(seeded))).click()
        waitFor(text("Apps"))
        E2e.waitForFresh(By.desc("Today, pick a day"))
        E2e.waitForFresh(text(today(seeded)))
    }

    @Test
    fun widget_keepsUp_asAppsComeAndGo() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 1440)))
        val seeded = listOf(ago(CLOCK, 50, 30))
        History.insertForTests(ctx, seeded)
        place(300, 200)
        assertTrue(E2e.eventually { held().total == today(seeded) })

        // More screen time is in the history, and an app comes on screen: the
        // blocker sees that, and the widget is shown afresh by itself.
        val more = seeded + ago(PHONE, 15, 5)
        History.insertForTests(ctx, more)
        openApp(CLOCK)
        assertTrue("the widget still says ${held().total}, not ${today(more)}",
            E2e.eventually { held().total == today(more) })
    }

    @Test
    fun withoutUsageAccess_theWidgetSaysSo_andOpensMoly() {
        freshState()
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")
        place(300, 200)
        E2e.waitForFresh(textContains("Allow usage access in Moly"))
        assertTrue(held().let { it.asks && it.total.isEmpty() && it.chart == null })

        // A tap opens Moly, whose home screen has the way to allow it.
        waitFor(textContains("Allow usage access in Moly")).click()
        waitFor(text("Block an app"))
        waitFor(textContains("Allow usage access for your daily screen time"))

        // Allowed: the numbers are there the next time it's shown.
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        ScreenTimeWidget.refresh(ctx)
        assertTrue(E2e.eventually { held().let { !it.asks && it.total == "0 minutes" } })
    }

    @Test
    fun usagePage_handsTheWidgetToTheLauncher() {
        freshState()
        assumeTrue("this launcher doesn't take widgets from apps", manager.isRequestPinAppWidgetSupported)
        openHome()
        waitFor(text("See apps")).click()
        waitFor(text("Apps"))
        E2e.scrollTo(text("Add the widget to your home screen")).click()

        // The launcher's own sheet, asking where to put it. Not taken up: no widget is left on the home screen.
        val launcher = homeScreenPackage()
        assertTrue("the launcher didn't offer to add the widget",
            appears(By.pkg(launcher).text(Pattern.compile("add.*", Pattern.CASE_INSENSITIVE))))
        device.pressBack()
        waitFor(text("Apps"))
        assertEquals("a widget was placed", 0,
            manager.getAppWidgetIds(ComponentName(ctx, ScreenTimeWidget::class.java)).size)
    }
}
