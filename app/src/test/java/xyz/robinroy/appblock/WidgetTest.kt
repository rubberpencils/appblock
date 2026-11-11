package xyz.robinroy.appblock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
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
        // Too narrow for a chart's marks, however tall, or for "Today" beside the heading;
        // the limited apps' line is two there, and needs their height.
        assertEquals(look(chart = false, legend = true, day = false), Storage.widgetLook(199, 400, true))
        assertEquals(look(chart = false, legend = false, day = false), Storage.widgetLook(150, 129, true))
        assertEquals(look(chart = false, legend = true, day = false), Storage.widgetLook(150, 130, true))
        assertEquals(look(chart = true, legend = true), Storage.widgetLook(200, 400, true))
        // No app limited: no line about limited apps.
        assertEquals(look(chart = true, legend = false), Storage.widgetLook(300, 400, false))
        assertEquals(look(chart = false, legend = false, day = false), Storage.widgetLook(110, 110, false))
    }

    @Test
    fun `the card's padding leaves a single row its height, and a huge picture is drawn smaller`() {
        assertEquals(16 to 14, Storage.widgetPaddingDp(Storage.widgetLook(300, 200, true)))
        assertEquals(14 to 4, Storage.widgetPaddingDp(Storage.widgetLook(300, 40, true)))
        // Whatever the size, what's left inside the padding is something to draw in.
        for (h in 40..600) Storage.widgetLook(110, h, true).let { assertTrue(h - 2 * Storage.widgetPaddingDp(it).second >= 32) }
        // A phone's widget at life size; a tablet's full screen within what Android takes.
        assertEquals(1f, Storage.widgetPictureScale(964, 580))
        assertEquals(1f, Storage.widgetPictureScale(1500, 1000))
        val scale = Storage.widgetPictureScale(2400, 1500)
        assertTrue(scale < 1f)
        assertTrue((2400 * scale).toLong() * (1500 * scale).toLong() <= Storage.WIDGET_PICTURE_MAX_PX)
        assertTrue("barely smaller than it must be", 2400 * scale * 1500 * scale > Storage.WIDGET_PICTURE_MAX_PX * 0.99)
        // Sizes that would overflow an Int multiplied.
        assertTrue(Storage.widgetPictureScale(100_000, 100_000) > 0f)
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
    fun `the widget's layouts exist, and its own holds the views the app fills in`() {
        for (key in listOf("initialLayout", "previewLayout")) {
            val layout = info.attr(key)
            assertTrue("$key is $layout", layout.startsWith("@layout/"))
            assertTrue("$layout is missing", File("src/main/res/layout/${layout.removePrefix("@layout/")}.xml").exists())
        }
        assertEquals("@layout/widget_screen_time", info.attr("initialLayout"))
        val layout = parse("src/main/res/layout/widget_screen_time.xml")
        // The card and its one picture.
        assertEquals("@+id/widget_root", layout.attr("id"))
        assertEquals(listOf("@+id/widget_picture"), layout.all("*").map { it.attr("id") })
        // The picture keeps its shape whatever size the launcher gives it: never stretched.
        assertEquals("fitStart", layout.all("ImageView").single().attr("scaleType"))
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
