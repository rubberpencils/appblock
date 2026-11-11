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
import android.view.View
import android.widget.RemoteViews
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
 * changes to it (RemoteViews), so the chart goes over as a picture: the
 * app's own UsageChartView, drawn into a bitmap at the widget's size.
 *
 * It's shown afresh when Android asks (placed, resized, and every half
 * hour: res/xml/screen_time_widget.xml) and, sooner, whenever the screen
 * changes: the blocker tells it (screenChanged), so going back to the
 * home screen finds it up to date. All of it runs in the blocker's
 * process, on the main thread and History's: nothing that goes wrong
 * here may escape.
 */
class ScreenTimeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = whileAwake { refresh(context, it) }

    /** Resized: what fits has changed. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        whileAwake { refresh(context, it) }

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
        private val main = Handler(Looper.getMainLooper())
        private var queued = false

        /**
         * The screen changed (the blocker saw an app come or go): the
         * widgets are shown afresh a moment later, once for a burst of
         * changes. Nothing happens with no widget placed.
         */
        fun screenChanged(ctx: Context) {
            if (queued) return
            queued = true
            val app = ctx.applicationContext
            main.postDelayed({ queued = false; refresh(app) }, Storage.WIDGET_REFRESH_DELAY_MS)
        }

        /** Every placed widget shown afresh, with today as the history has it now; `done` when they are. */
        fun refresh(ctx: Context, done: () -> Unit = {}) {
            try {
                val app = ctx.applicationContext
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager?.getAppWidgetIds(ComponentName(app, ScreenTimeWidget::class.java)) ?: IntArray(0)
                if (manager == null || ids.isEmpty()) { done(); return }
                if (!usageAccessGranted(app)) {
                    for (id in ids) manager.updateAppWidget(id, atItsSizes(manager.getAppWidgetOptions(id)) { w, h -> asking(app, w, h) })
                    done()
                    return
                }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val limited = Storage.loadRules(app).keys
                val ignored = Storage.screenTimeIgnored(app)
                History.loadDays(app, today, today, zone, null, { d ->
                    // As the home screen's card: the apps the user doesn't count are left out.
                    val sessions = Storage.counted(d.sessions, ignored)
                    Today(Storage.buckets(sessions, d.from, d.to, ScreenTimeView.CHART_BUCKET_MIN * 60_000L, limited, d.upTo),
                        d.from, d.to, d.upTo, zone, Storage.screenTimeMs(sessions, d.from, d.to),
                        Storage.screenTimeMs(sessions.filter { it.pkg in limited }, d.from, d.to), limited.isNotEmpty())
                }) { t ->
                    try {
                        // The history couldn't be read: the widget stays as it was.
                        if (t != null) for (id in ids) {
                            manager.updateAppWidget(id, atItsSizes(manager.getAppWidgetOptions(id)) { w, h -> views(app, t, w, h) })
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "the widget wasn't shown afresh", e)
                    } finally { done() }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "the widget wasn't shown afresh", e)
                done()
            }
        }

        /**
         * The widget, built by `build` for the sizes the launcher has it at
         * (`options`): upright, and on its side when that's another size.
         */
        private fun atItsSizes(options: Bundle?, build: (widthDp: Int, heightDp: Int) -> RemoteViews): RemoteViews {
            fun size(width: String, height: String) =
                Storage.widgetSizeDp(options?.getInt(width) ?: 0, options?.getInt(height) ?: 0)
            // Upright, a widget is at its narrowest and tallest; on its side, its widest and shortest.
            val upright = size(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val sideways = size(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val portrait = build(upright.first, upright.second)
            return if (sideways == upright) portrait else RemoteViews(build(sideways.first, sideways.second), portrait)
        }

        private fun views(app: Context, t: Today, widthDp: Int, heightDp: Int): RemoteViews {
            val look = Storage.widgetLook(widthDp, heightDp, t.anyLimited)
            val summary = Storage.widgetSummary(t.totalMs, t.limitedMs, t.anyLimited)
            if (look.compact) return RemoteViews(app.packageName, R.layout.widget_screen_time_small).apply {
                setTextViewText(R.id.widget_total, Storage.fmtScreenTime(t.totalMs))
                setContentDescription(R.id.widget_root, summary)
                setOnClickPendingIntent(R.id.widget_root, opening(app, UsageActivity::class.java))
            }
            return RemoteViews(app.packageName, R.layout.widget_screen_time).apply {
                setTextViewText(R.id.widget_day, if (look.day) app.getString(R.string.widget_today) else "")
                setTextViewText(R.id.widget_total, Storage.fmtScreenTime(t.totalMs))
                setViewVisibility(R.id.widget_total, View.VISIBLE)
                setViewVisibility(R.id.widget_ask, View.GONE)
                val chart = Storage.widgetChartDp(widthDp, heightDp, look)
                setViewVisibility(R.id.widget_chart, if (chart != null) View.VISIBLE else View.GONE)
                if (chart != null) setImageViewBitmap(R.id.widget_chart, draw(app, t, chart.first, chart.second))
                setViewVisibility(R.id.widget_legend, if (look.legend) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_limited, Storage.widgetLimited(t.limitedMs))
                setContentDescription(R.id.widget_root, summary)
                setOnClickPendingIntent(R.id.widget_root, opening(app, UsageActivity::class.java))
            }
        }

        /** Without usage access there's nothing to show: it says what to do, and a tap opens Moly, which has the way. */
        private fun asking(app: Context, widthDp: Int, heightDp: Int): RemoteViews {
            if (Storage.widgetLook(widthDp, heightDp, false).compact) {
                return RemoteViews(app.packageName, R.layout.widget_screen_time_small).apply {
                    setTextViewText(R.id.widget_total, app.getString(R.string.widget_ask_short))
                    setContentDescription(R.id.widget_root, app.getString(R.string.widget_ask))
                    setOnClickPendingIntent(R.id.widget_root, opening(app, MainActivity::class.java))
                }
            }
            return asking(app)
        }

        private fun asking(app: Context) = RemoteViews(app.packageName, R.layout.widget_screen_time).apply {
            setTextViewText(R.id.widget_day, "")
            setViewVisibility(R.id.widget_total, View.GONE)
            setViewVisibility(R.id.widget_chart, View.GONE)
            setViewVisibility(R.id.widget_legend, View.GONE)
            setViewVisibility(R.id.widget_ask, View.VISIBLE)
            setContentDescription(R.id.widget_root, app.getString(R.string.widget_ask))
            setOnClickPendingIntent(R.id.widget_root, opening(app, MainActivity::class.java))
        }

        /** The day's chart as a picture, `widthDp` by `heightDp`: the card's own chart view, drawn off screen. */
        private fun draw(app: Context, t: Today, widthDp: Int, heightDp: Int): Bitmap {
            val w = Ui.dp(app, widthDp).coerceAtLeast(1)
            val h = Ui.dp(app, heightDp).coerceAtLeast(1)
            val chart = UsageChartView(app)
            chart.set(t.buckets, t.from, t.to, ScreenTimeView.CHART_BUCKET_MIN * 60_000L, t.zone, animate = false, upTo = t.upTo)
            chart.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            chart.layout(0, 0, w, h)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { chart.draw(Canvas(it)) }
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

        private const val REQUEST = 34
    }
}
