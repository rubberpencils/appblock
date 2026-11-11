package xyz.robinroy.appblock

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Screen time, a day, a week, a month or a year at a time (Storage.Period,
 * picked on the row under the heading): its name with arrows to step
 * through the history, the total, the chart, its legend (limited apps
 * and the rest), and the busiest part of it. A day's chart goes across
 * its hours (UsageChartView), with the busiest hour; the longer ones are
 * a bar per day, per month in a year (PeriodBarsView), with a day's
 * average and the busiest day or month. Tapping the name opens a
 * calendar (DayPicker), to jump to the day, or to the week, month or
 * year it is in. The home screen's top card (tapping it opens the Usage
 * page) and the Usage page's head; the apps the user doesn't count
 * (Storage.counted) are left out. With `only`, one app's time, counted or
 * not: the head of its own page. The history loads, and is added up,
 * off the main thread (History.loadDays); `onLoaded` gets each as it shows.
 */
class ScreenTimeView(
    private val activity: Activity,
    startDay: LocalDate?,
    animate: Boolean = true,
    private val only: String? = null,
    startPeriod: Storage.Period = Storage.Period.DAY,
    private val onLoaded: (Shown) -> Unit = {},
) : LinearLayout(activity) {

    /**
     * What's showing: the `period` that `day` is in, `first` to `last`.
     * `apps`: every app's time in it, most first, the uncounted ones too
     * (the Usage page lists them apart). A day has its chart's `buckets`
     * and its busiest hour (`peak`), the longer ones their `stats`.
     */
    class Shown(
        val period: Storage.Period, val day: LocalDate, val first: LocalDate, val last: LocalDate,
        val from: Long, val to: Long, val upTo: Long, val firstDay: LocalDate?,
        val apps: List<Pair<String, Long>>, val totalMs: Long, val limitedMs: Long,
        val buckets: Storage.Buckets?, val peak: Storage.Peak?, val stats: Storage.PeriodStats?,
    )

    private val zone: ZoneId = ZoneId.systemDefault()
    private val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    var day: LocalDate = startDay ?: LocalDate.now(zone)
        private set
    var period: Storage.Period = startPeriod
        private set
    private var token = 0
    var firstDay: LocalDate? = null
        private set

    private val dayTitle = Ui.txt(activity, "", 14f, medium = true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        // A pill like the arrows beside it: it's a button too (the calendar).
        background = Ui.shape(activity, R.color.surface_alt, 99f)
        minWidth = Ui.dp(activity, 104)
        setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 12), Ui.dp(activity, 10))
        Ui.press(this, 0.95f)
        setOnClickListener {
            unpick()
            DayPicker.show(activity, day, firstDay, LocalDate.now(zone)) { picked -> go(picked) }
        }
    }
    private val prev = arrow(R.drawable.ic_back) { step(-1) }
    private val next = arrow(R.drawable.ic_chevron) { step(1) }
    private val segments = linkedMapOf<Storage.Period, TextView>()
    private val total = Ui.txt(activity, "", 34f, tracking = -0.02f, lineSpacing = 1f, editorial = true)
    private val average = Ui.muted(activity, "", 13f)
    private val chart = UsageChartView(activity)
    private val bars = PeriodBarsView(activity)
    private val limitedLegend = legend(R.color.accent, "Limited apps")
    private val otherLegend = legend(R.color.chart_other_line, "Other apps")
    private val peak = Ui.muted(activity, "", 13f)

    init {
        orientation = VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.eyebrow(activity, "Screen time").apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, Ui.lp(0).apply { weight = 1f })
            addView(prev, LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36)))
            addView(dayTitle, Ui.lp(Ui.WRAP).apply {
                marginStart = Ui.dp(activity, 6); marginEnd = Ui.dp(activity, 6)
            })
            addView(next, LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36)))
        }, Ui.lp())
        addView(periodPicker(), Ui.lp().apply { topMargin = Ui.dp(activity, 12) })
        addView(total, Ui.lp().apply { topMargin = Ui.dp(activity, 10) })
        addView(average, Ui.lp().apply { topMargin = Ui.dp(activity, 2) })
        // One chart or the other, in the same place.
        addView(chart, Ui.lp(Ui.MATCH, Ui.dp(activity, 132)).apply { topMargin = Ui.dp(activity, 14) })
        addView(bars, Ui.lp(Ui.MATCH, Ui.dp(activity, 132)).apply { topMargin = Ui.dp(activity, 14) })
        // One app: its total says it all; no legend.
        if (only == null) addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            addView(limitedLegend.first, Ui.lp(0).apply { weight = 1f })
            addView(otherLegend.first, Ui.lp(0).apply { weight = 1f })
        }, Ui.lp().apply { topMargin = Ui.dp(activity, 14) })
        addView(peak, Ui.lp().apply { topMargin = Ui.dp(activity, 12) })
        enable(prev, false)
        enable(next, false)
        paintPeriod()
        load(animate)
    }

    /** Day, week, month, year: the look of the limit sheet's units. */
    private fun periodPicker() = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        background = Ui.shape(activity, R.color.surface_alt, 14f)
        val p = Ui.dp(activity, 3)
        setPadding(p, p, p, p)
        for (each in Storage.Period.values()) {
            val seg = Ui.txt(activity, each.label, 14f, R.color.muted, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (period == each) return@setOnClickListener
                    period = each
                    paintPeriod()
                    load(animate = true)
                }
            }
            segments[each] = seg
            addView(seg, Ui.lp(0).apply { weight = 1f })
        }
    }

    /** The period picked, on the picker, the arrows and the charts. */
    private fun paintPeriod() {
        for ((each, v) in segments) {
            val on = each == period
            v.isSelected = on
            v.setTextColor(activity.getColor(if (on) R.color.on_ink else R.color.muted))
            v.background = if (on) Ui.shape(activity, R.color.ink, 11f) else null
        }
        val word = period.label.lowercase()
        prev.contentDescription = "Previous $word"
        next.contentDescription = "Next $word"
        unpick()
        val isDay = period == Storage.Period.DAY
        chart.visibility = if (isDay) View.VISIBLE else View.GONE
        bars.visibility = if (isDay) View.GONE else View.VISIBLE
        average.visibility = if (isDay) View.GONE else View.VISIBLE
    }

    private fun unpick() {
        chart.unpick()
        bars.unpick()
    }

    /** Back or forward a day (a week, a month, a year): never past today, nor before the history starts. */
    private fun step(by: Long) {
        Storage.periodStep(period, day, by, firstDay, LocalDate.now(zone), firstDow)?.let { go(it) }
    }

    /** Straight to a day (the calendar's pick), or to the week, month or year it is in. */
    fun go(to: LocalDate) {
        day = to
        load(animate = false)
    }

    /** Shows it again: on return to the screen, after a step. */
    fun load(animate: Boolean = false) {
        val want = ++token
        val today = LocalDate.now(zone)
        val period = period
        val day = day
        dayTitle.text = Storage.periodTitle(period, day, today, firstDow)
        dayTitle.contentDescription = "${dayTitle.text}, pick a day"
        val (first, last) = Storage.periodDays(period, day, firstDow)
        val limited = Storage.loadRules(activity).keys
        val ignored = Storage.screenTimeIgnored(activity)
        History.loadDays(activity, first, last, zone, only, { d -> prepare(d, period, day, today, limited, ignored) }) { s ->
            if (want != token || activity.isDestroyed || s == null) return@loadDays
            show(s, today, animate)
            onLoaded(s)
        }
    }

    /** The history read, added up for the screen (on the history thread: nothing here touches a view). */
    private fun prepare(d: History.Days, period: Storage.Period, day: LocalDate, today: LocalDate,
                        limited: Set<String>, ignored: Set<String>): Shown {
        // One app's page shows it whether it counts or not; everywhere else, only what counts.
        val sessions = if (only != null) d.sessions else Storage.counted(d.sessions, ignored)
        // One app's chart is in the accent, limited or not.
        val accent = if (only != null) setOf(only) else limited
        val apps = if (only != null) emptyList() else Storage.appTotals(d.sessions, d.from, d.to)
        if (period == Storage.Period.DAY) {
            val all = Storage.screenTimeMs(sessions, d.from, d.to)
            val lim = Storage.screenTimeMs(sessions.filter { it.pkg in limited }, d.from, d.to)
            return Shown(period, day, d.first, d.last, d.from, d.to, d.upTo, d.firstDay, apps, all, lim,
                Storage.buckets(sessions, d.from, d.to, CHART_BUCKET_MIN * 60_000L, accent, d.upTo),
                Storage.peakHour(sessions, d.first, zone), null)
        }
        val stats = Storage.periodStats(sessions, period, d.first, d.last, zone, accent, d.firstDay, today)
        return Shown(period, day, d.first, d.last, d.from, d.to, d.upTo, d.firstDay, apps, stats.totalMs, stats.limitedMs,
            null, null, stats)
    }

    private fun show(s: Shown, today: LocalDate, animate: Boolean) {
        firstDay = s.firstDay
        val noHistory = s.firstDay == null || s.last.isBefore(s.firstDay)
        val current = !today.isBefore(s.first) && !today.isAfter(s.last)
        total.text = if (noHistory && !current) "No history" else Storage.fmtScreenTime(s.totalMs)
        limitedLegend.second.text = Storage.fmtScreenTime(s.limitedMs)
        otherLegend.second.text = Storage.fmtScreenTime(s.totalMs - s.limitedMs)
        val legend = if (only == null) " Limited apps ${limitedLegend.second.text}." else ""
        val b = s.buckets
        val stats = s.stats
        if (b != null) {
            chart.set(b, s.from, s.to, CHART_BUCKET_MIN * 60_000L, zone, animate, s.upTo, single = only != null)
            val p = s.peak
            peak.text = when {
                p != null -> "Busiest hour: ${Storage.fmtHour(p.start, zone)}, " +
                    Storage.fmtScreenTime(p.ms).replaceFirstChar { it.lowercase() }
                only != null -> if (current) "Not used yet today." else "Not used on this day."
                else -> if (current) "Nothing yet today." else "Nothing on this day."
            }
            chart.summary = "Screen time, ${dayTitle.text}: ${total.text}." + legend +
                (if (p != null) " ${peak.text}." else "")
        } else if (stats != null) {
            bars.set(stats, s.period, today, single = only != null, animate = animate)
            average.text = "Daily average: " + Storage.fmtScreenTime(stats.avgMs).replaceFirstChar { it.lowercase() }
            val top = stats.busiest
            val word = s.period.label.lowercase()
            peak.text = when {
                top == null -> if (only != null) "Not used in this $word." else "Nothing in this $word."
                s.period == Storage.Period.YEAR -> "Busiest month: ${Storage.monthName(stats.starts[top], today)}, " +
                    Storage.fmtScreenTime(stats.limited[top] + stats.other[top]).replaceFirstChar { it.lowercase() } + " a day"
                else -> "Busiest day: ${Storage.dayTitle(stats.starts[top], today)}, " +
                    Storage.fmtScreenTime(stats.limited[top] + stats.other[top]).replaceFirstChar { it.lowercase() }
            }
            bars.summary = "Screen time, ${dayTitle.text}: ${total.text}." + legend + " ${average.text}." +
                (if (top != null) " ${peak.text}." else "")
        }
        enable(prev, Storage.periodStep(s.period, s.day, -1, s.firstDay, today, firstDow) != null)
        enable(next, Storage.periodStep(s.period, s.day, 1, s.firstDay, today, firstDow) != null)
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.3f
    }

    private fun arrow(icon: Int, onClick: () -> Unit) = FrameLayout(activity).apply {
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
        /** The day chart's slices, in minutes: fine enough for sharp spikes, wide enough to read. */
        const val CHART_BUCKET_MIN = 15
    }
}
