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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
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
 *
 * The widget's face is a picture the app draws, so its words aren't on
 * screen as text: the tests read what the picture says to TalkBack (on
 * screen), what the app drew into it (ScreenTimeWidget.drawnForTests),
 * and its size.
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
    private val provider get() = ComponentName(ctx, ScreenTimeWidget::class.java)
    private val zone = ZoneId.systemDefault()

    /** A widget left placed by a run that was cut short goes first. */
    @Before
    fun noWidgetLeft() {
        AppWidgetHost(ctx, HOST).deleteHost()
        check(manager.getAppWidgetIds(provider).isEmpty()) { "a Moly widget is on the emulator's home screen: remove it" }
    }

    /** A widget's size as a launcher reports it: upright it's `minWidth` by `maxHeight`, on its side `maxWidth` by `minHeight`. */
    private fun size(minWidth: Int, maxHeight: Int, maxWidth: Int = minWidth, minHeight: Int = maxHeight) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
    }

    /**
     * The widget placed at `widthDp` by `heightDp`, as a launcher places
     * one, and put alone on a screen of Moly's. Returns its id.
     */
    private fun place(widthDp: Int, heightDp: Int): Int {
        shell("appwidget grantbind --package ${ctx.packageName} --user 0")
        val hosting = AppWidgetHost(ctx, HOST).also { host = it }
        val id = hosting.allocateAppWidgetId()
        check(manager.bindAppWidgetIdIfAllowed(id, provider, size(widthDp, heightDp))) {
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
    private fun resize(id: Int, widthDp: Int, heightDp: Int, options: Bundle = size(widthDp, heightDp)) {
        manager.updateAppWidgetOptions(id, options)
        instrumentation.runOnMainSync {
            widget!!.layoutParams = FrameLayout.LayoutParams(Ui.dp(ctx, widthDp), Ui.dp(ctx, heightDp), Gravity.CENTER)
        }
    }

    /** What the app last drew on the widget: its texts in order, and "[chart]" for the chart. */
    private fun drawn(id: Int): List<String> = ScreenTimeWidget.drawnForTests[id].orEmpty()

    /** The picture the placed widget holds now (on screen or not): its width and height in pixels. */
    private fun picture(): Pair<Int, Int>? {
        var out: Pair<Int, Int>? = null
        instrumentation.runOnMainSync {
            val bitmap = (widget!!.findViewById<ImageView>(R.id.widget_picture)?.drawable as? BitmapDrawable)?.bitmap
            out = bitmap?.let { it.width to it.height }
        }
        return out
    }

    /** The picture's size for a widget of `widthDp` by `heightDp`: the widget less its card's padding, at life size. */
    private fun pictureOf(widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val (side, edge) = Storage.widgetPaddingDp(Storage.widgetLook(widthDp, heightDp, true))
        return Ui.dp(ctx, widthDp - 2 * side) to Ui.dp(ctx, heightDp - 2 * edge)
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

    private fun String.lower() = replaceFirstChar { it.lowercase() }

    @Test
    fun widget_showsToday_fitsItsSize_andOpensTheUsagePage() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 1440)))
        // Clock (limited) for 20 minutes, then Phone for 10.
        val seeded = listOf(ago(CLOCK, 50, 30), ago(PHONE, 15, 5))
        History.insertForTests(ctx, seeded)
        val total = today(seeded)
        val limited = "Limited apps: " + today(seeded.filter { it.pkg == CLOCK }).lower()
        val says = "Screen time today: ${total.lower()}. $limited."
        val id = place(300, 200)

        // Four cells by two or so: the heading, the total, the day's chart, the limited apps' time.
        E2e.waitForFresh(By.desc(says))
        assertEquals(listOf("SCREEN TIME", "Today", total, "[chart]", limited), drawn(id))
        assertEquals("the picture is the widget's size, less its card's padding", pictureOf(300, 200), picture())

        // Made two cells by one: the total alone. It still says all of it.
        resize(id, 110, 40)
        assertTrue("a one-row widget drew ${drawn(id)}, its picture ${picture()}",
            E2e.eventually { drawn(id) == listOf(total) && picture() == pictureOf(110, 40) })
        E2e.waitForFresh(By.desc(says))

        // Two cells wide, taller: no room for a chart's marks, or for "Today" beside the heading,
        // and the limited apps' time goes under its name.
        resize(id, 150, 220)
        assertTrue("a narrow widget drew ${drawn(id)}", E2e.eventually {
            drawn(id) == listOf("SCREEN TIME", total, "Limited apps", today(seeded.filter { it.pkg == CLOCK }))
        })

        // A launcher that gives one size upright and another on its side gets a picture for each;
        // upright (as here) it's the narrow, tall one.
        resize(id, 300, 260, size(minWidth = 300, maxHeight = 260, maxWidth = 420, minHeight = 120))
        assertTrue("upright, the widget drew ${drawn(id)}, its picture ${picture()}", E2e.eventually {
            drawn(id) == listOf("SCREEN TIME", "Today", total, "[chart]", limited) && picture() == pictureOf(300, 260)
        })

        // A tap anywhere on it opens the Usage page, on today.
        E2e.waitForFresh(By.desc(says)).click()
        waitFor(text("Apps"))
        E2e.waitForFresh(By.desc("Today, pick a day"))
        E2e.waitForFresh(text(total))
    }

    @Test
    fun widget_keepsUp_asAppsComeAndGo() {
        freshState(mapOf(CLOCK to Rule(allowMin = 30, windowMin = 1440)))
        val seeded = listOf(ago(CLOCK, 50, 30))
        val more = seeded + ago(PHONE, 15, 5)
        val (before, after) = today(seeded) to today(more)
        // (In the day's first minutes neither is in today yet, and there'd be nothing to see change.)
        assumeTrue("too soon after midnight to tell $before from $after", before != after)
        History.insertForTests(ctx, seeded)
        val id = place(300, 200)
        assertTrue(E2e.eventually { before in drawn(id) })

        // More screen time is in the history, and an app comes on screen: the
        // blocker sees that, and the widget is shown afresh by itself.
        History.insertForTests(ctx, more)
        openApp(CLOCK)
        assertTrue("the widget still shows ${drawn(id)}, not $after", E2e.eventually { after in drawn(id) })
    }

    @Test
    fun withoutUsageAccess_theWidgetSaysSo_andOpensMoly() {
        freshState()
        shell("appops set ${ctx.packageName} GET_USAGE_STATS deny")
        val ask = "Allow usage access in Moly to see your screen time here."
        val id = place(300, 200)
        E2e.waitForFresh(By.desc(ask))
        assertEquals(listOf("SCREEN TIME", ask), drawn(id))

        // A tap opens Moly, whose home screen has the way to allow it.
        waitFor(By.desc(ask)).click()
        waitFor(text("Block an app"))
        waitFor(textContains("Allow usage access for your daily screen time"))

        // Allowed, and Moly left: the numbers are there.
        shell("appops set ${ctx.packageName} GET_USAGE_STATS allow")
        device.pressHome()
        assertTrue("the widget still shows ${drawn(id)}", E2e.eventually { drawn(id) == listOf("SCREEN TIME", "Today", "0 minutes", "[chart]") })
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
        assertEquals("a widget was placed", 0, manager.getAppWidgetIds(provider).size)
    }

    private companion object {
        /** The test's own widget host: any number no launcher uses. */
        const val HOST = 0x4d6f
    }
}
