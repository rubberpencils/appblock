package xyz.robinroy.appblock

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId

/**
 * A day's screen time: the day with arrows to step through the history,
 * the total, the chart across the day (limited apps and the rest), its
 * legend, and the busiest hour. The home screen's top card (tapping it
 * opens the Usage page) and the Usage page's head. The history loads off
 * the main thread (History.loadDay); `onLoaded` gets each day as it shows.
 */
class ScreenTimeView(
    private val activity: Activity,
    startDay: LocalDate?,
    animate: Boolean = true,
    private val onLoaded: (History.Day) -> Unit = {},
) : LinearLayout(activity) {

    private val zone: ZoneId = ZoneId.systemDefault()
    var day: LocalDate = startDay ?: LocalDate.now(zone)
        private set
    private var token = 0
    private var firstDay: LocalDate? = null

    private val dayTitle = Ui.txt(activity, "", 14f, medium = true).apply { gravity = Gravity.CENTER }
    private val prev = arrow(R.drawable.ic_back, "Previous day") { step(-1) }
    private val next = arrow(R.drawable.ic_chevron, "Next day") { step(1) }
    private val total = Ui.txt(activity, "", 34f, tracking = -0.02f, lineSpacing = 1f, editorial = true)
    private val chart = UsageChartView(activity)
    private val limitedLegend = legend(R.color.accent, "Limited apps")
    private val otherLegend = legend(R.color.chart_other_line, "Other apps")
    private val peak = Ui.muted(activity, "", 13f)

    init {
        orientation = VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.eyebrow(activity, "Screen time"), Ui.lp(0).apply { weight = 1f })
            addView(prev, LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36)))
            addView(dayTitle, Ui.lp(Ui.dp(activity, 104)))
            addView(next, LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36)))
        }, Ui.lp())
        addView(total, Ui.lp().apply { topMargin = Ui.dp(activity, 6) })
        addView(chart, Ui.lp(Ui.MATCH, Ui.dp(activity, 132)).apply { topMargin = Ui.dp(activity, 14) })
        addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(limitedLegend.first, Ui.lp(0).apply { weight = 1f })
            addView(otherLegend.first, Ui.lp(0).apply { weight = 1f })
        }, Ui.lp().apply { topMargin = Ui.dp(activity, 14) })
        addView(peak, Ui.lp().apply { topMargin = Ui.dp(activity, 12) })
        enable(prev, false)
        enable(next, false)
        load(animate)
    }

    /** Back or forward a day: never past today, nor before the history starts. */
    private fun step(by: Long) {
        val today = LocalDate.now(zone)
        val to = day.plusDays(by)
        if (to.isAfter(today) || (by < 0 && firstDay.let { it == null || to.isBefore(it) })) return
        day = to
        load(animate = false)
    }

    /** Shows the day again: on return to the screen, after a step. */
    fun load(animate: Boolean = false) {
        val want = ++token
        val today = LocalDate.now(zone)
        dayTitle.text = Storage.dayTitle(day, today)
        History.loadDay(activity, day, zone) { d ->
            if (want != token || activity.isDestroyed || d == null) return@loadDay
            show(d, today, animate)
            onLoaded(d)
        }
    }

    private fun show(d: History.Day, today: LocalDate, animate: Boolean) {
        firstDay = d.firstDay
        val limited = Storage.loadRules(activity).keys
        val bucketMs = CHART_BUCKET_MIN * 60_000L
        val b = Storage.buckets(d.sessions, d.from, d.to, bucketMs, limited, d.upTo)
        chart.set(b, d.from, d.to, bucketMs, zone, animate)
        val all = Storage.screenTimeMs(d.sessions, d.from, d.to)
        val lim = Storage.screenTimeMs(d.sessions.filter { it.pkg in limited }, d.from, d.to)
        val noHistory = d.firstDay == null || d.day.isBefore(d.firstDay)
        total.text = if (noHistory && d.day != today) "No history" else Storage.fmtScreenTime(all)
        limitedLegend.second.text = Storage.fmtScreenTime(lim)
        otherLegend.second.text = Storage.fmtScreenTime(all - lim)
        val p = Storage.peakHour(d.sessions, d.day, zone)
        peak.text = if (p == null) (if (d.day == today) "Nothing yet today." else "Nothing on this day.")
            else "Busiest hour: ${Storage.fmtHour(p.start, zone)}, ${Storage.fmtScreenTime(p.ms)}"
        chart.contentDescription = "Screen time, ${dayTitle.text}: ${total.text}. Limited apps ${limitedLegend.second.text}." +
            (if (p != null) " ${peak.text}." else "")
        enable(prev, d.firstDay != null && d.day.isAfter(d.firstDay))
        enable(next, d.day.isBefore(today))
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.3f
    }

    private fun arrow(icon: Int, desc: String, onClick: () -> Unit) = FrameLayout(activity).apply {
        contentDescription = desc
        isClickable = true
        isFocusable = true
        background = Ui.shape(activity, R.color.surface_alt, 0f, oval = true)
        addView(Ui.icon(activity, icon, 16, R.color.ink).apply {
            layoutParams = FrameLayout.LayoutParams(Ui.dp(activity, 16), Ui.dp(activity, 16), Gravity.CENTER)
        })
        setOnClickListener { onClick() }
        Ui.press(this, 0.92f)
    }

    /** A legend entry: the series' dot and name, the time under it. Text in ink, never the series color. */
    private fun legend(color: Int, name: String): Pair<View, TextView> {
        val value = Ui.txt(activity, "", 15f, medium = true)
        val box = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(activity).apply { background = Ui.shape(activity, color, 0f, oval = true) },
                    LayoutParams(Ui.dp(activity, 9), Ui.dp(activity, 9)))
                addView(Ui.muted(activity, name, 13f).apply { setPadding(Ui.dp(activity, 7), 0, 0, 0) })
            })
            addView(value, Ui.lp().apply { topMargin = Ui.dp(activity, 3) })
        }
        return box to value
    }

    companion object {
        /** The chart's slices, in minutes: fine enough for sharp spikes, wide enough to read. */
        const val CHART_BUCKET_MIN = 15
    }
}
