package xyz.robinroy.appblock

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDate
import java.time.ZoneId

/**
 * One app's screen time, from a row on the Usage page: its day, week,
 * month or year (the chart, stepping through the history and the
 * calendar, as on the Usage page, and opened on the one showing there),
 * whether it counts in screen time, and its patterns over the last week,
 * 30 days or all time (Storage.appStats): its daily average, the day it
 * was used most, how often it was opened and for how long, and the hours
 * it gets used.
 */
class AppUsageActivity : Activity() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private lateinit var pkg: String
    private lateinit var head: ScreenTimeView
    private var range = Storage.StatsRange.WEEK
    private var token = 0
    private var started = false
    private var limited = false

    private val segments = mutableMapOf<Storage.StatsRange, TextView>()
    private lateinit var countSwitch: Switch
    private lateinit var countChip: TextView
    private lateinit var avg: Tile
    private lateinit var topDay: Tile
    private lateinit var opened: Tile
    private lateinit var longest: Tile
    private lateinit var peakValue: TextView
    private lateinit var bars: HourBarsView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        // Where it was before Android built the screen again; else where the Usage page was.
        val start = ScreenTimeView.savedDay(savedInstanceState)
            ?: intent.getStringExtra(UsageActivity.EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val name = labelFor(pkg)
        limited = pkg in Storage.loadRules(this).keys
        val period = ScreenTimeView.savedPeriod(savedInstanceState)
            ?: intent.getStringExtra(UsageActivity.EXTRA_PERIOD)?.let { runCatching { Storage.Period.valueOf(it) }.getOrNull() }
        head = ScreenTimeView(this, start, only = pkg, startPeriod = period ?: Storage.Period.DAY)

        val side = Ui.dp(this, 20)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 32))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back") { finish() }, Ui.lp(Ui.dp(context, 44), Ui.dp(context, 44)))

            // The app: icon, name, and what it is to Moly.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.appIcon(context, iconFor(pkg), 52))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.display(context, name, 26f).apply { maxLines = 2 })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        if (limited) addView(chip("Limited", R.color.accent, R.color.accent_soft), Ui.lp(Ui.WRAP).apply {
                            marginEnd = Ui.dp(context, 6)
                        })
                        countChip = chip("Not counted", R.color.muted, R.color.surface_alt)
                        addView(countChip, Ui.lp(Ui.WRAP))
                    }, Ui.lp().apply { topMargin = Ui.dp(context, 6) })
                }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 14) })
            }, Ui.lp().apply { topMargin = Ui.dp(context, 16) })

            addView(Ui.bezel(context, LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ui.dp(context, 20)
                setPadding(p, p, p, p)
                addView(head, Ui.lp())
            }), Ui.lp().apply { topMargin = Ui.dp(context, 20) })

            addView(countRow(), Ui.lp().apply { topMargin = Ui.dp(context, 12) })

            // Its patterns, over the range picked.
            addView(Ui.eyebrow(context, "Patterns"), Ui.lp().apply { topMargin = Ui.dp(context, 28) })
            addView(rangePicker(), Ui.lp().apply { topMargin = Ui.dp(context, 10) })
            avg = Tile("Daily average")
            topDay = Tile("Most used day")
            opened = Tile("Opened")
            longest = Tile("Longest session")
            addView(tiles(avg, topDay), Ui.lp().apply { topMargin = Ui.dp(context, 12) })
            addView(tiles(opened, longest), Ui.lp().apply { topMargin = Ui.dp(context, 10) })
            peakValue = Ui.title(context, "", 17f)
            bars = HourBarsView(context)
            addView(Ui.card(context).apply {
                val p = Ui.dp(context, 18)
                setPadding(p, p, p, Ui.dp(context, 14))
                addView(Ui.muted(context, "Peak hours", 13f))
                addView(peakValue, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
                addView(bars, Ui.lp(Ui.MATCH, Ui.dp(context, 96)).apply { topMargin = Ui.dp(context, 14) })
            }, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
        }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        })
        paintCount()
        paintRange()
        loadStats()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::head.isInitialized) ScreenTimeView.save(head, outState)
    }

    /** A tap outside a chart's card puts the chart back (Readout). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        Readout.outside(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // The first load is the views' own; back on screen, shown again (today moves on).
        if (started) { head.load(); loadStats() } else started = true
    }

    /** "Count in screen time": off leaves the app out of the totals, the chart and the busiest hour. */
    private fun countRow(): View {
        countSwitch = Switch(this).apply {
            // The row takes the tap, and is the switch to TalkBack too.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return Ui.card(this).apply {
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = countSwitch.isChecked
                }
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(context, 18)
            setPadding(p, Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.txt(context, "Count in screen time", 15f, medium = true))
                addView(Ui.muted(context, "Turn off for apps that shouldn't count, like maps while you drive " +
                    "or an app you study with. Its time still shows here.", 13f), Ui.lp().apply {
                    topMargin = Ui.dp(context, 3)
                })
            }, Ui.lp(0).apply { weight = 1f; marginEnd = Ui.dp(context, 10) })
            addView(countSwitch)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Storage.setCountsInScreenTime(context, pkg, !countSwitch.isChecked)
                paintCount()
            }
        }
    }

    private fun paintCount() {
        val counts = pkg !in Storage.screenTimeIgnored(this)
        countSwitch.isChecked = counts
        countChip.visibility = if (counts) View.GONE else View.VISIBLE
    }

    /** 7 days, 30 days, all time: the look of the limit sheet's units. */
    private fun rangePicker() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.shape(context, R.color.surface_alt, 14f)
        val p = Ui.dp(context, 3)
        setPadding(p, p, p, p)
        for (r in Storage.StatsRange.values()) {
            val seg = Ui.txt(context, r.label, 14f, R.color.muted, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (range == r) return@setOnClickListener
                    range = r
                    paintRange()
                    loadStats()
                }
            }
            segments[r] = seg
            addView(seg, Ui.lp(0).apply { weight = 1f })
        }
    }

    private fun paintRange() {
        for ((r, v) in segments) {
            val on = r == range
            v.isSelected = on
            v.setTextColor(getColor(if (on) R.color.on_ink else R.color.muted))
            v.background = if (on) Ui.shape(this, R.color.ink, 11f) else null
        }
    }

    private fun loadStats() {
        val want = ++token
        val today = LocalDate.now(zone)
        // Read from as far back as the range could go; it's cut to the history's start once that's known.
        val from = range.days?.let { today.minusDays(it - 1) } ?: LocalDate.of(2000, 1, 1)
        History.loadApp(this, pkg, from, today, zone) { span ->
            if (want != token || isDestroyed || span == null) return@loadApp
            val (first, last) = Storage.rangeDays(range, span.firstDay, today)
            showStats(Storage.appStats(span.sessions, first, last, zone, span.now), today)
        }
    }

    private fun showStats(s: Storage.AppStats, today: LocalDate) {
        avg.set(Storage.fmtScreenTime(s.avgMs), if (s.days == 1) "Over 1 day" else "Over ${s.days} days")
        val top = s.topDay
        if (top == null) topDay.set("Not used", null)
        else topDay.set(Storage.dayTitle(top, today), Storage.fmtScreenTime(s.topDayMs))
        opened.set(if (s.opens == 1) "Once" else "${s.opens} times",
            if (s.opens > 0) Storage.perOpenText(s.avgSessionMs) else null)
        longest.set(if (s.longestMs > 0) Storage.fmtScreenTime(s.longestMs) else "None", null)
        val peak = s.peakHour
        peakValue.text = if (peak == null) "Not used" else Storage.hourRange(peak)
        bars.set(s.hours, peak, R.color.accent, s.days)
        bars.summary = "Peak hours, ${range.label}: ${peakValue.text}"
    }

    /** A stat: what it is, the value, and an optional line under it. */
    private inner class Tile(val label: String) {
        val value = Ui.title(this@AppUsageActivity, "", 17f)
        val sub = Ui.muted(this@AppUsageActivity, "", 13f)
        val view = Ui.card(this@AppUsageActivity).apply {
            val p = Ui.dp(context, 16)
            setPadding(p, p, p, p)
            addView(Ui.muted(context, label, 13f))
            addView(value, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
            addView(sub, Ui.lp().apply { topMargin = Ui.dp(context, 2) })
        }

        fun set(v: String, under: String?) {
            value.text = v
            sub.text = under ?: ""
            sub.visibility = if (under == null) View.GONE else View.VISIBLE
            view.contentDescription = "$label: $v" + (under?.let { ", $it" } ?: "")
        }
    }

    private fun tiles(a: Tile, b: Tile) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a.view, Ui.lp(0, Ui.MATCH).apply { weight = 1f })
        addView(b.view, Ui.lp(0, Ui.MATCH).apply { weight = 1f; marginStart = Ui.dp(context, 10) })
    }

    private fun chip(text: String, color: Int, back: Int) = Ui.txt(this, text, 11f, color, medium = true).apply {
        background = Ui.shape(context, back, 99f)
        setPadding(Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2))
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
