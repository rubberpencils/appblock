package xyz.robinroy.appblock

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate

/**
 * Screen time, in full, from the home screen's card: the day's chart and
 * busiest hour (ScreenTimeView), then every app used that day, most first,
 * with a bar against the top one and its time. Apps AppBlock limits say so.
 */
class UsageActivity : Activity() {

    private lateinit var head: ScreenTimeView
    private lateinit var list: LinearLayout
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        val start = intent.getStringExtra(EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head = ScreenTimeView(this, start) { day -> showApps(day) }
        val side = Ui.dp(this, 20)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 32))
            addView(Ui.iconButton(context, R.drawable.ic_back, "Back") { finish() }, Ui.lp(Ui.dp(context, 44), Ui.dp(context, 44)))
            addView(Ui.bezel(context, LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = Ui.dp(context, 20)
                setPadding(p, p, p, p)
                addView(head, Ui.lp())
            }), Ui.lp().apply { topMargin = Ui.dp(context, 16) })
            addView(Ui.eyebrow(context, "Apps"), Ui.lp().apply { topMargin = Ui.dp(context, 28) })
            addView(list, Ui.lp().apply { topMargin = Ui.dp(context, 8) })
        }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        // The first load is the view's own; after that, back on screen, it's shown again (today moves on).
        if (started) head.load() else started = true
    }

    private fun showApps(d: History.Day) {
        list.removeAllViews()
        val totals = Storage.appTotals(d.sessions, d.from, d.to)
        if (totals.isEmpty()) {
            list.addView(Ui.muted(this, "No apps used.", 14f), Ui.lp().apply { topMargin = Ui.dp(this@UsageActivity, 8) })
            return
        }
        val limited = Storage.loadRules(this).keys
        val top = totals.first().second.toFloat()
        for ((pkg, ms) in totals) list.addView(appRow(pkg, ms, ms / top, pkg in limited))
    }

    private fun appRow(pkg: String, ms: Long, fraction: Float, limited: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
        val name = labelFor(pkg)
        contentDescription = "$name, ${Storage.fmtScreenTime(ms)}" + if (limited) ", limited" else ""
        addView(Ui.appIcon(context, iconFor(pkg), 34))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.title(context, name, 15f).apply { maxLines = 1 }, Ui.lp(0).apply { weight = 1f })
                addView(Ui.txt(context, Storage.fmtScreenTime(ms), 13f, R.color.muted).apply {
                    setPadding(Ui.dp(context, 10), 0, 0, 0)
                })
            })
            if (limited) addView(Ui.txt(context, "Limited", 11f, R.color.accent, medium = true).apply {
                background = Ui.shape(context, R.color.accent_soft, 99f)
                setPadding(Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2))
            }, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 4) })
            addView(bar(fraction, if (limited) R.color.accent else R.color.chart_other_line),
                Ui.lp(Ui.MATCH, Ui.dp(context, 4)).apply { topMargin = Ui.dp(context, 7) })
        }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 12) })
    }

    /** A bar against the most used app's, in the chart's color for it (not the limit bar: full isn't "over"). */
    private fun bar(fraction: Float, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.shape(context, R.color.surface_alt, 99f)
        val f = fraction.coerceIn(0.02f, 1f)
        addView(android.view.View(context).apply { background = Ui.shape(context, color, 99f) },
            LinearLayout.LayoutParams(0, Ui.MATCH, f))
        addView(android.view.View(context), LinearLayout.LayoutParams(0, Ui.MATCH, 1f - f))
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }

    companion object {
        const val EXTRA_DAY = "day"
    }
}
