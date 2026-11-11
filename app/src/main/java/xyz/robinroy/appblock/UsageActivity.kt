package xyz.robinroy.appblock

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

/**
 * Screen time, in full, from the home screen's card: the chart of the
 * day, week, month or year showing (ScreenTimeView), then every app used
 * in it, with a bar against the top one and its time; a search box and
 * sort chips (time or name, tap again to flip) as in the app picker. Apps
 * Moly limits say so; the apps the user doesn't count come after, under
 * their own heading, and last, on request, the installed apps with no
 * logged use in that time. Tapping an app opens its own page
 * (AppUsageActivity), on the same day, week, month or year.
 */
class UsageActivity : Activity() {

    private lateinit var head: ScreenTimeView
    private lateinit var list: LinearLayout
    private lateinit var chips: Map<Storage.UsageSort, TextView>
    private lateinit var scroll: ScrollView
    private var started = false
    private var shown: ScreenTimeView.Shown? = null
    private var query = ""
    private var sort = Storage.UsageSort.TIME
    private var descending = true
    private var showUnused = false
    private val labels = mutableMapOf<String, String>()
    private var installed: Map<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        val start = intent.getStringExtra(EXTRA_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val period = intent.getStringExtra(EXTRA_PERIOD)?.let { runCatching { Storage.Period.valueOf(it) }.getOrNull() }
        head = ScreenTimeView(this, start, startPeriod = period ?: Storage.Period.DAY) { s -> shown = s; showApps() }
        chips = linkedMapOf(Storage.UsageSort.TIME to chip("Time", Storage.UsageSort.TIME),
            Storage.UsageSort.NAME to chip("Name", Storage.UsageSort.NAME))
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
            addView(searchField(), Ui.lp().apply { topMargin = Ui.dp(context, 12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.muted(context, "Sort", 13f).apply { setPadding(0, 0, Ui.dp(context, 10), 0) })
                for (c in chips.values) addView(c)
            }, Ui.lp().apply { topMargin = Ui.dp(context, 12) })
            addView(list, Ui.lp().apply { topMargin = Ui.dp(context, 8) })
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        setContentView(scroll)
        paintChips()
    }

    /** A tap outside a chart's card puts the chart back (Readout). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        Readout.outside(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // The first load is the view's own; after that, back on screen, it's shown again (today moves on).
        if (started) head.load() else started = true
    }

    /** The list for the day (or week, month, year) showing, searched and sorted. */
    private fun showApps() {
        val d = shown ?: return
        list.removeAllViews()
        val gap = Ui.dp(this, 20)
        val totals = d.apps
        val limited = Storage.loadRules(this).keys
        val ignored = Storage.screenTimeIgnored(this)
        val used = totals.map { (pkg, ms) -> Storage.AppUse(pkg, labelFor(pkg), ms) }
        val (counted, notCounted) = used.partition { it.pkg !in ignored }
        // Bars against the top app in each part: what counts, and what doesn't.
        val top = (counted.maxOfOrNull { it.ms } ?: 1L).toFloat()
        val topNotCounted = (notCounted.maxOfOrNull { it.ms } ?: 1L).toFloat()
        val found = { a: Storage.AppUse -> Storage.matches(a.label, query) }
        val countedShown = Storage.sortApps(counted.filter(found), sort, descending)
        val notCountedShown = Storage.sortApps(notCounted.filter(found), sort, descending)
        if (used.isEmpty()) list.addView(Ui.muted(this, "No apps used.", 14f), Ui.lp().apply { topMargin = Ui.dp(this@UsageActivity, 8) })
        for (a in countedShown) list.addView(appRow(a, a.ms / top, a.pkg in limited, d, counts = true))
        if (notCountedShown.isNotEmpty()) {
            list.addView(Ui.eyebrow(this, "Not counted"), Ui.lp().apply { topMargin = gap })
            list.addView(Ui.muted(this, "Left out of your screen time.", 13f), Ui.lp().apply { topMargin = Ui.dp(this@UsageActivity, 4) })
            for (a in notCountedShown) list.addView(appRow(a, a.ms / topNotCounted, a.pkg in limited, d, counts = false))
        }

        // Last, on request: the installed apps with nothing logged in that time.
        val unused = Storage.noUsage(installedApps(), used.map { it.pkg }.toSet()).filter(found)
        if (used.isNotEmpty() && countedShown.isEmpty() && notCountedShown.isEmpty() && unused.isEmpty()) {
            list.addView(Ui.muted(this, "No apps match \u201C${query.trim()}\u201D.", 14f), Ui.lp().apply { topMargin = Ui.dp(this@UsageActivity, 8) })
        }
        if (unused.isEmpty()) return
        list.addView(Ui.row(this, null, "Apps with no logged usage",
            "${unused.size} ${if (unused.size == 1) "app" else "apps"}",
            trailing = Ui.icon(this, R.drawable.ic_chevron, 18, R.color.muted).apply { rotation = if (showUnused) 270f else 90f }) {
            showUnused = !showUnused
            showApps()
        }.apply {
            contentDescription = "Apps with no logged usage, ${unused.size}, " + if (showUnused) "shown" else "hidden"
        }, Ui.lp().apply { topMargin = Ui.dp(this@UsageActivity, 12) })
        if (showUnused) for (a in unused) list.addView(appRow(a, 0f, a.pkg in limited, d, counts = a.pkg !in ignored))
    }

    private fun appRow(a: Storage.AppUse, fraction: Float, limited: Boolean, showing: ScreenTimeView.Shown, counts: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
        val time = if (a.ms > 0) Storage.fmtScreenTime(a.ms) else "No usage"
        contentDescription = "${a.label}, $time" + (if (limited) ", limited" else "") +
            if (counts) "" else ", not counted"
        isClickable = true
        isFocusable = true
        setOnClickListener {
            startActivity(android.content.Intent(context, AppUsageActivity::class.java)
                .putExtra(AppUsageActivity.EXTRA_PKG, a.pkg)
                .putExtra(EXTRA_DAY, showing.day.toString())
                .putExtra(EXTRA_PERIOD, showing.period.name))
        }
        Ui.press(this, 0.98f)
        if (!counts) alpha = 0.6f
        addView(Ui.appIcon(context, iconFor(a.pkg), 34))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.title(context, a.label, 15f).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, Ui.lp(0).apply { weight = 1f })
                addView(Ui.txt(context, time, 13f, R.color.muted).apply {
                    setPadding(Ui.dp(context, 10), 0, 0, 0)
                })
            })
            if (limited) addView(Ui.txt(context, "Limited", 11f, R.color.accent, medium = true).apply {
                background = Ui.shape(context, R.color.accent_soft, 99f)
                setPadding(Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2))
            }, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 4) })
            if (a.ms > 0) addView(bar(fraction), Ui.lp(Ui.MATCH, Ui.dp(context, 4)).apply { topMargin = Ui.dp(context, 7) })
        }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 12) })
    }

    /** A bar against the most used app's (not the limit bar: full isn't "over"). */
    private fun bar(fraction: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.shape(context, R.color.surface_alt, 99f)
        val f = fraction.coerceIn(0.02f, 1f)
        addView(android.view.View(context).apply { background = Ui.shape(context, R.color.accent, 99f) },
            LinearLayout.LayoutParams(0, Ui.MATCH, f))
        addView(android.view.View(context), LinearLayout.LayoutParams(0, Ui.MATCH, 1f - f))
    }

    /** The search box, as in the app picker. */
    private fun searchField(): View {
        val box = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(getColor(R.color.muted))
            setTextColor(getColor(R.color.ink))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = null
            setPadding(Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
            // Typing: the box goes to the top, so the results show above the keyboard.
            setOnFocusChangeListener { v, has -> if (has) bringSearchUp(v) }
            setOnClickListener { bringSearchUp(it) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) { query = s.toString(); showApps() }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(context, R.color.surface, 18f, R.color.line)
            setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 6), 0)
            addView(Ui.icon(context, R.drawable.ic_search, 20, R.color.muted))
            addView(box, Ui.lp(0).apply { weight = 1f })
        }
    }

    private fun bringSearchUp(box: View) {
        val field = box.parent as? View ?: return
        // Room below to scroll that far, however short the list.
        list.minimumHeight = scroll.height
        scroll.post { scroll.smoothScrollTo(0, field.top - Ui.dp(this, 12)) }
    }

    /** A sort chip: tap to sort by it, tap again to flip. Time starts most first, name from A. */
    private fun chip(label: String, key: Storage.UsageSort) = TextView(this).apply {
        text = label
        tag = label
        textSize = 13f
        typeface = Ui.sans(context, medium = true)
        gravity = android.view.Gravity.CENTER
        setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
        // As wide as with its arrow, so it doesn't change width as the arrow comes and goes.
        minWidth = (paint.measureText("$label \u2193") + Ui.dp(context, 28)).toInt()
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(context, 8) }
        Ui.press(this, 0.95f)
        setOnClickListener {
            if (sort == key) descending = !descending
            else { sort = key; descending = key == Storage.UsageSort.TIME }
            paintChips()
            showApps()
        }
    }

    private fun paintChips() {
        for ((key, view) in chips) {
            val active = key == sort
            val label = view.tag as String
            view.text = if (!active) label else label + if (descending) " \u2193" else " \u2191"
            view.contentDescription = "Sort by $label" + if (active) (if (descending) ", descending" else ", ascending") else ""
            view.setTextColor(getColor(if (active) R.color.on_ink else R.color.ink))
            view.background = if (active) Ui.shape(this, R.color.ink, 99f) else Ui.shape(this, R.color.surface, 99f, R.color.line)
        }
    }

    /** Every app with a launcher icon, package to name; read once. */
    private fun installedApps(): Map<String, String> = installed ?: run {
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = try {
            packageManager.queryIntentActivities(launcher, 0)
                .associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        } catch (e: Exception) { emptyMap() }
        labels.putAll(apps)
        apps.also { installed = it }
    }

    private fun labelFor(pkg: String) = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }

    companion object {
        const val EXTRA_DAY = "day"
        /** A Storage.Period's name: the week, month or year that day is in, instead of the day alone. */
        const val EXTRA_PERIOD = "period"
    }
}
