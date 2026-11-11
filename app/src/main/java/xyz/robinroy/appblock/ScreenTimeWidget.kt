package xyz.robinroy.appblock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId

/**
 * The home-screen widget: today's screen time, as the home screen's card
 * has it. The total, the day's chart when the widget is big enough for
 * it, and the limited apps' time; one row high, the total alone
 * (Storage.widgetLook). A tap opens the Usage page. Without usage access
 * it says so, and a tap opens Moly.
 *
 * A widget is drawn by the launcher, from a layout file and a list of
 * changes to it (RemoteViews), and the launcher won't load an app's own
 * fonts for it. So the widget's face is one picture: the app lays out
 * and draws the same views it uses on its own screens (the type, the
 * card's chart) into a bitmap at the widget's size, and the layout file
 * holds only the card and that picture. What it says is the picture's
 * description, for TalkBack.
 *
 * It's shown afresh when Android asks (placed, resized, and every half
 * hour: res/xml/screen_time_widget.xml) and, sooner, whenever the screen
 * changes: the blocker tells it (screenChanged), so going back to the
 * home screen finds it up to date. A picture is in the colors and the
 * font size of the moment it was drawn, so it's drawn again when those
 * change. All of it runs in the blocker's process, on the main thread
 * and History's: nothing that goes wrong here may escape.
 */
class ScreenTimeWidget : AppWidgetProvider() {

    /**
     * Any app can send this receiver a broadcast, with anything in it:
     * what Android's own reading of one throws stops here, not in the
     * process the blocker runs in.
     */
    override fun onReceive(context: Context, intent: Intent) {
        try { super.onReceive(context, intent) } catch (e: Exception) { Log.w(TAG, "a broadcast that couldn't be read", e) }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = whileAwake { refresh(context, it) }

    /** Resized: what fits has changed. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        whileAwake { refresh(context, it) }

    /** The last one taken off the home screen: the screen changing is nothing to it now. */
    override fun onDisabled(context: Context) { placed = false }

    /**
     * Android may stop the app once a broadcast is dealt with, and the
     * history is read on another thread: the broadcast is held open until
     * `work` says it's done, or a few seconds at most.
     */
    private fun whileAwake(work: (done: () -> Unit) -> Unit) {
        val pending = try { goAsync() } catch (e: Exception) { null }
        var finished = false
        val finish = Runnable {
            if (!finished) {
                finished = true
                try { pending?.finish() } catch (e: Exception) {}
            }
        }
        main.postDelayed(finish, 8_000L)
        work { main.removeCallbacks(finish); finish.run() }
    }

    /** Today, added up for the widget (on the history thread). */
    private class Today(
        val buckets: Storage.Buckets, val from: Long, val to: Long, val upTo: Long, val zone: ZoneId,
        val totalMs: Long, val limitedMs: Long, val anyLimited: Boolean,
    )

    companion object {
        private const val TAG = "ScreenTimeWidget"
        private const val REQUEST = 34
        private const val FIT = "fit"
        private val main = Handler(Looper.getMainLooper())

        // All of these are the main thread's.
        private var queued = false
        private var watching = false
        private var loading = false
        private var loadingSince = 0L
        private var again = false

        /** Whether a widget is on a home screen: not known until the first look (null). */
        private var placed: Boolean? = null

        /** What was last drawn on each widget (its texts, and "[chart]"), for the end-to-end tests. */
        @Volatile var drawnForTests: Map<Int, List<String>> = emptyMap()
            private set

        /**
         * The screen changed (the blocker saw an app come or go), or Moly's
         * own screen was left: the widgets are shown afresh a moment later,
         * once for a burst of changes. With no widget placed it does
         * nothing at all.
         */
        fun screenChanged(ctx: Context) {
            if (queued || placed == false) return
            queued = true
            val app = ctx.applicationContext
            main.postDelayed({ queued = false; refresh(app) }, Storage.WIDGET_REFRESH_DELAY_MS)
        }

        /**
         * Every placed widget shown afresh, with today as the history has
         * it now; `done` when they are. Asked for again while it's at it,
         * it goes round once more at the end, not twice at once.
         */
        fun refresh(ctx: Context, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            if (Looper.myLooper() != main.looper) { main.post { refresh(app, done) }; return }
            // (A read that never came back, the history thread gone, doesn't hold the next ones up for good.)
            if (loading && android.os.SystemClock.elapsedRealtime() - loadingSince < 30_000L) { again = true; done(); return }
            try {
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager?.getAppWidgetIds(ComponentName(app, ScreenTimeWidget::class.java)) ?: IntArray(0)
                placed = ids.isNotEmpty()
                if (manager == null || ids.isEmpty()) { done(); return }
                watchTheLook(app)
                if (!usageAccessGranted(app)) {
                    show(app, manager, ids, null)
                    done()
                    return
                }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val limited = Storage.loadRules(app).keys
                val ignored = Storage.screenTimeIgnored(app)
                loading = true
                loadingSince = android.os.SystemClock.elapsedRealtime()
                History.loadDays(app, today, today, zone, null, { d ->
                    // As the home screen's card: the apps the user doesn't count are left out.
                    val sessions = Storage.counted(d.sessions, ignored)
                    Today(Storage.buckets(sessions, d.from, d.to, ScreenTimeView.CHART_BUCKET_MIN * 60_000L, limited, d.upTo),
                        d.from, d.to, d.upTo, zone, Storage.screenTimeMs(sessions, d.from, d.to),
                        Storage.screenTimeMs(sessions.filter { it.pkg in limited }, d.from, d.to), limited.isNotEmpty())
                }) { t ->
                    // The history couldn't be read (null): the widget stays as it was.
                    try { if (t != null) show(app, manager, ids, t) } finally {
                        loading = false
                        done()
                        if (again) { again = false; refresh(app) }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "the widget wasn't shown afresh", e)
                loading = false
                done()
            }
        }

        /** Each widget drawn at its own size and handed to the launcher; one that can't be doesn't stop the rest. */
        private fun show(app: Context, manager: AppWidgetManager, ids: IntArray, t: Today?) {
            val drawn = mutableMapOf<Int, List<String>>()
            for (id in ids) {
                try {
                    val options = manager.getAppWidgetOptions(id)
                    fun size(width: String, height: String) =
                        Storage.widgetSizeDp(options?.getInt(width) ?: 0, options?.getInt(height) ?: 0)
                    // Upright, a widget is at its narrowest and tallest; on its side, its widest and shortest.
                    val upright = size(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    val sideways = size(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    val portrait = views(app, t, upright.first, upright.second) { drawn[id] = it }
                    manager.updateAppWidget(id,
                        if (sideways == upright) portrait else RemoteViews(views(app, t, sideways.first, sideways.second) {}, portrait))
                } catch (e: Throwable) {
                    Log.w(TAG, "widget $id wasn't shown afresh", e)
                }
            }
            drawnForTests = drawn
        }

        /** The widget at `widthDp` by `heightDp`: its card, its picture, what the picture says, and where a tap goes. */
        private fun views(app: Context, t: Today?, widthDp: Int, heightDp: Int, texts: (List<String>) -> Unit): RemoteViews {
            val look = Storage.widgetLook(widthDp, heightDp, t?.anyLimited ?: false)
            val (side, edge) = Storage.widgetPaddingDp(look)
            val face = face(app, t, look)
            texts(textsOf(face))
            return RemoteViews(app.packageName, R.layout.widget_screen_time).apply {
                setViewPadding(R.id.widget_root, Ui.dp(app, side), Ui.dp(app, edge), Ui.dp(app, side), Ui.dp(app, edge))
                setImageViewBitmap(R.id.widget_picture, draw(app, face, widthDp - 2 * side, heightDp - 2 * edge))
                setContentDescription(R.id.widget_root,
                    if (t == null) app.getString(R.string.widget_ask) else Storage.widgetSummary(t.totalMs, t.limitedMs, t.anyLimited))
                // Without usage access, Moly's home screen has the way to allow it.
                setOnClickPendingIntent(R.id.widget_root,
                    opening(app, if (t == null) MainActivity::class.java else UsageActivity::class.java))
            }
        }

        /**
         * The widget's face, as views: the app's own type, and the card's
         * chart. `t` null: without usage access, what to do about it.
         */
        private fun face(app: Context, t: Today?, look: Storage.WidgetLook): View {
            val column = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            val total = if (t == null) app.getString(R.string.widget_ask_short) else Storage.fmtScreenTime(t.totalMs)
            fun totalView() = Ui.txt(app, total, Storage.WIDGET_TOTAL_SP, tracking = -0.02f, lineSpacing = 1f, editorial = true).apply {
                maxLines = 1
                tag = FIT
            }
            if (look.compact) {
                // One line: the total alone, as large as fits (draw sees to that).
                column.gravity = Gravity.CENTER_VERTICAL
                column.addView(totalView(), Ui.lp())
                return column
            }
            column.addView(LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.eyebrow(app, app.getString(R.string.widget_eyebrow)).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, Ui.lp(0).apply { weight = 1f })
                if (t != null && look.day) addView(Ui.txt(app, app.getString(R.string.widget_today), 12f, R.color.muted, medium = true))
            }, Ui.lp())
            if (t == null) {
                column.addView(Ui.txt(app, app.getString(R.string.widget_ask), 13f).apply { gravity = Gravity.CENTER_VERTICAL },
                    LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
                return column
            }
            column.addView(totalView(), Ui.lp().apply { topMargin = Ui.dp(app, 4) })
            if (look.chart) column.addView(UsageChartView(app).apply {
                set(t.buckets, t.from, t.to, ScreenTimeView.CHART_BUCKET_MIN * 60_000L, t.zone, animate = false, upTo = t.upTo)
            }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.dp(app, 6) })
            if (look.legend) {
                // Wide: "Limited apps: 40 minutes" on a line. Narrow (no "Today" either): the name, and the time under it.
                column.addView(LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(app).apply { background = Ui.shape(app, R.color.accent, 0f, oval = true) },
                        LinearLayout.LayoutParams(Ui.dp(app, 9), Ui.dp(app, 9)))
                    addView(Ui.muted(app, if (look.day) Storage.widgetLimited(t.limitedMs) else "Limited apps", 12f).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(Ui.dp(app, 7), 0, 0, 0)
                    })
                }, Ui.lp().apply { topMargin = Ui.dp(app, 6) })
                if (!look.day) column.addView(Ui.txt(app, Storage.fmtScreenTime(t.limitedMs), 13f, medium = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, Ui.lp().apply { topMargin = Ui.dp(app, 2) })
            }
            return column
        }

        /**
         * `face` laid out in `widthDp` by `heightDp` and drawn into a
         * bitmap. The total is made smaller until it's on its one line; a
         * widget the size of a tablet's screen is drawn smaller and
         * stretched, to stay inside what Android lets a widget send.
         */
        private fun draw(app: Context, face: View, widthDp: Int, heightDp: Int): Bitmap {
            val w = Ui.dp(app, widthDp).coerceAtLeast(1)
            val h = Ui.dp(app, heightDp).coerceAtLeast(1)
            face.findViewWithTag<TextView>(FIT)?.let { total ->
                var sp = Storage.WIDGET_TOTAL_SP
                // One line, and in a single row no taller than the row.
                fun fits() = total.paint.measureText(total.text.toString()) <= w &&
                    total.paint.fontMetrics.let { it.descent - it.ascent } <= h
                while (!fits() && sp > Storage.WIDGET_TOTAL_MIN_SP) { sp -= 1f; total.textSize = sp }
            }
            face.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            face.layout(0, 0, w, h)
            val scale = Storage.widgetPictureScale(w, h)
            val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            face.draw(Canvas(bitmap).apply { scale(scale, scale) })
            return bitmap
        }

        /** What `face` shows, in order: each text, and "[chart]" for the chart. */
        private fun textsOf(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is UsageChartView -> listOf("[chart]")
            is ViewGroup -> (0 until view.childCount).flatMap { textsOf(view.getChildAt(it)) }
            else -> emptyList()
        }

        /**
         * Dark or light, the font size: the picture is from before. Heard
         * for as long as the app runs, from the first time a widget is shown.
         */
        private fun watchTheLook(app: Context) {
            if (watching) return
            watching = true
            app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = screenChanged(app)
                override fun onLowMemory() {}
            })
        }

        /**
         * A tap's way into the app. Its own request code: Android hands
         * back an existing PendingIntent for the same screen and code as it
         * was first made, and the notifications' one for the home screen
         * (code 0) is made without these flags.
         */
        private fun opening(app: Context, screen: Class<*>): PendingIntent = PendingIntent.getActivity(app, REQUEST,
            Intent(app, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
