package xyz.robinroy.appblock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import xyz.robinroy.appblock.Storage.WidgetLook

/**
 * The home-screen widget: what it has room for at a size, its words, and
 * its wiring. The manifest, the widget's description for the launcher
 * and its layouts name each other by string, so nothing at compile time
 * notices one renamed: the widget would just be missing from the
 * launcher's list. (Unit tests run with the module dir, app/, as cwd.)
 */
class WidgetTest {

    private val Int.min get() = this * 60_000L

    @Test
    fun `the total always, the chart with room to read it, the limited apps' line with a line to spare`() {
        fun look(chart: Boolean, legend: Boolean, compact: Boolean = false, day: Boolean = !compact) =
            WidgetLook(compact, chart, legend, day)
        // One row high: the total alone, without even its heading.
        assertEquals(look(chart = false, legend = false, compact = true), Storage.widgetLook(110, 40, true))
        assertEquals(look(chart = false, legend = false, compact = true), Storage.widgetLook(400, 89, true))
        // Room for the heading, then for the limited apps' line.
        assertEquals(look(chart = false, legend = false), Storage.widgetLook(250, 90, true))
        assertEquals(look(chart = false, legend = false), Storage.widgetLook(250, 111, true))
        assertEquals(look(chart = false, legend = true), Storage.widgetLook(250, 112, true))
        // High enough for the chart: it comes before the line.
        assertEquals(look(chart = true, legend = false), Storage.widgetLook(250, 152, true))
        assertEquals(look(chart = true, legend = false), Storage.widgetLook(250, 173, true))
        assertEquals(look(chart = true, legend = true), Storage.widgetLook(250, 174, true))
        // Too narrow for a chart's marks, however tall, or for "Today" beside the heading.
        assertEquals(look(chart = false, legend = true, day = false), Storage.widgetLook(199, 400, true))
        assertEquals(look(chart = true, legend = true), Storage.widgetLook(200, 400, true))
        // No app limited: no line about limited apps.
        assertEquals(look(chart = true, legend = false), Storage.widgetLook(300, 400, false))
        assertEquals(look(chart = false, legend = false, day = false), Storage.widgetLook(110, 110, false))
    }

    @Test
    fun `the chart takes what the total and the line leave`() {
        assertEquals(218 to 56, Storage.widgetChartDp(250, 152, WidgetLook(false, chart = true, legend = false, day = true)))
        assertEquals(268 to 82, Storage.widgetChartDp(300, 200, WidgetLook(false, chart = true, legend = true, day = true)))
        assertNull(Storage.widgetChartDp(300, 200, WidgetLook(false, chart = false, legend = true, day = true)))
        // Whatever the size, a chart that's shown has some height.
        for (h in 0..600) for (limited in listOf(true, false)) {
            val look = Storage.widgetLook(300, h, limited)
            Storage.widgetChartDp(300, h, look)?.let { assertTrue("$h dp high: $it", it.second >= 56) }
        }
    }

    @Test
    fun `a launcher that gives no size gets the size the widget starts as`() {
        assertEquals(250 to 110, Storage.widgetSizeDp(0, 0))
        assertEquals(320 to 110, Storage.widgetSizeDp(320, 0))
        assertEquals(320 to 180, Storage.widgetSizeDp(320, 180))
    }

    @Test
    fun `the widget's words`() {
        assertEquals("Limited apps: 40 minutes", Storage.widgetLimited(40.min))
        assertEquals("Limited apps: less than a minute", Storage.widgetLimited(20_000))
        assertEquals("Limited apps: 0 minutes", Storage.widgetLimited(0))
        assertEquals("Screen time today: 2 hours 5 minutes. Limited apps: 40 minutes.",
            Storage.widgetSummary(125.min, 40.min, anyLimited = true))
        assertEquals("Screen time today: 0 minutes.", Storage.widgetSummary(0, 0, anyLimited = false))
    }

    // ---- wiring ----

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.all(tag: String) = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String) = getAttributeNS(androidNs, name)

    private val manifest = parse("src/main/AndroidManifest.xml")
    private val receiver = manifest.all("receiver").single { it.attr("name") == ".ScreenTimeWidget" }
    private val info = parse("src/main/res/xml/screen_time_widget.xml")

    @Test
    fun `the manifest's widget is the class, and takes Android's updates`() {
        Class.forName("xyz.robinroy.appblock" + receiver.attr("name"), false, javaClass.classLoader)  // throws if renamed
        assertEquals("true", receiver.attr("exported"))
        assertTrue(receiver.all("action").any { it.attr("name") == "android.appwidget.action.APPWIDGET_UPDATE" })
        assertTrue(receiver.all("meta-data").any {
            it.attr("name") == "android.appwidget.provider" && it.attr("resource") == "@xml/screen_time_widget"
        })
    }

    @Test
    fun `the widget's layouts exist, and hold every view the app fills in`() {
        for (key in listOf("initialLayout", "previewLayout")) {
            val layout = info.attr(key)
            assertTrue("$key is $layout", layout.startsWith("@layout/"))
            assertTrue("$layout is missing", File("src/main/res/layout/${layout.removePrefix("@layout/")}.xml").exists())
        }
        assertEquals("@layout/widget_screen_time", info.attr("initialLayout"))
        val layout = parse("src/main/res/layout/widget_screen_time.xml")
        val ids = (listOf(layout) + layout.all("*")).map { it.attr("id").removePrefix("@+id/") }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("widget_root", "widget_eyebrow", "widget_day", "widget_total", "widget_ask", "widget_chart",
            "widget_legend", "widget_limited"), ids)
        // The one-row layout: the total alone.
        val small = parse("src/main/res/layout/widget_screen_time_small.xml")
        assertEquals("@+id/widget_root", small.attr("id"))
        assertEquals(listOf("@+id/widget_total"), small.all("*").map { it.attr("id") })
    }

    @Test
    fun `it starts at four cells by two, and Android reminds it no more often than it will`() {
        // The size it starts as when the launcher gives none is the size it's described with.
        assertEquals("${Storage.WIDGET_DEFAULT_WIDTH_DP}dp", info.attr("minWidth"))
        assertEquals("${Storage.WIDGET_DEFAULT_HEIGHT_DP}dp", info.attr("minHeight"))
        assertEquals("4", info.attr("targetCellWidth"))
        assertEquals("2", info.attr("targetCellHeight"))
        // Under half an hour, Android rounds it up to half an hour anyway.
        assertTrue(info.attr("updatePeriodMillis").toLong() >= 30 * 60_000L)
    }
}
