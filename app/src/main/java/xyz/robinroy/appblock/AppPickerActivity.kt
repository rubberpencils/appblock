package xyz.robinroy.appblock

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/**
 * Picker screen: every launchable app (not yet blocked), with tick circles
 * and, when Usage access is granted, each app's screen time today and over
 * the last 7 days. Sort chips default to this-week descending, so the
 * biggest time sinks float to the top. A confirm pill slides up once
 * something is picked.
 *
 * Uses a ListView so rows are recycled: scrolling stays smooth even with
 * hundreds of installed apps.
 */
class AppPickerActivity : Activity() {

    private class Entry(val pkg: String, val label: String) {
        var todayMs = 0L
        var weekMs = 0L
    }

    private class Row(
        val root: LinearLayout, val icon: ImageView, val name: TextView,
        val usage: TextView, val check: FrameLayout, val tick: ImageView
    )

    private enum class SortKey { NAME, TODAY, WEEK }

    private val selected = mutableSetOf<String>()
    private val icons = mutableMapOf<String, Drawable>()
    private val allApps = mutableListOf<Entry>()
    private val shown = mutableListOf<Entry>()   // what the list currently displays

    private var sortKey = SortKey.WEEK
    private var descending = true
    private var userSorted = false   // until a chip is tapped, defaults apply
    private var query = ""
    private var haveUsage = false

    private lateinit var adapter: BaseAdapter
    private lateinit var usageCard: LinearLayout
    private lateinit var chips: Map<SortKey, Pair<TextView, String>>
    private lateinit var confirmBar: LinearLayout
    private lateinit var confirm: Ui.Pill

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)

        val pm = packageManager
        val alreadyBlocked = Storage.loadRules(this).keys
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps.addAll(pm.queryIntentActivities(launcher, 0)
            .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName && it.pkg !in alreadyBlocked })

        val side = Ui.dp(this, 20)

        confirm = Ui.primaryButton(this, "Block selected apps", R.drawable.ic_check) {
            val rules = Storage.loadRules(this).toMutableMap()
            for (pkg in selected) rules[pkg] = Rule(5, 120)
            Storage.saveRules(this, rules)
            finish()
        }
        val canvas = getColor(R.color.canvas)
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, Ui.dp(context, 36), side, Ui.dp(context, 20))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(canvas and 0x00FFFFFF, canvas, canvas))
            visibility = View.GONE
            addView(confirm, Ui.lp(Ui.WRAP))
        }

        // Same dismissible info card as the home screen's reminders.
        usageCard = ReminderCard.make(
            this,
            message = "Grant usage access to see how much you use each app " +
                "(and sort by it). Tap to enable.",
            confirmTitle = "Hide this hint?",
            confirmBody = "Without usage access, the picker can't show " +
                "screen time or sort apps by it.\n\nHide this hint anyway? " +
                "(You can still enable usage access later from the About " +
                "page.)",
            onFix = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            onHideForever = { Storage.setUsageReminderDismissed(this) })

        val iconDp = 44
        val listView = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()

            override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView?.tag as? Row) ?: makeRow(iconDp).also { it.root.tag = it }
                val app = shown[i]
                row.icon.setImageDrawable(iconFor(app.pkg))
                row.name.text = app.label
                row.usage.apply {
                    visibility = if (haveUsage) View.VISIBLE else View.GONE
                    text = if (app.weekMs == 0L && app.todayMs == 0L)
                        "Not used in the last 7 days"
                    else Storage.phrases("${Storage.fmtScreenTime(app.todayMs)} today",
                        "${Storage.fmtScreenTime(app.weekMs)} last 7 days")
                }
                bindCheck(row, app.pkg in selected, animate = false)
                return row.root
            }
        }
        listView.adapter = adapter
        listView.divider = null
        listView.dividerHeight = 0
        listView.isVerticalScrollBarEnabled = false
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, Ui.dp(this, 110))
        listView.selector = RippleDrawable(
            ColorStateList.valueOf(getColor(R.color.line)), null, ColorDrawable(Color.BLACK))
        listView.setOnItemClickListener { _, view, i, _ ->
            val pkg = shown[i].pkg
            val nowSelected = pkg !in selected
            if (nowSelected) selected.add(pkg) else selected.remove(pkg)
            (view.tag as? Row)?.let { bindCheck(it, nowSelected, animate = true) }
            refreshConfirm()
        }

        val searchBox = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(getColor(R.color.muted))
            setTextColor(getColor(R.color.ink))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            background = null
            setPadding(Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    query = s.toString().trim().lowercase()
                    refreshShown()
                }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            })
        }
        val searchField = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(context, R.color.surface, 18f, R.color.line)
            setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 6), 0)
            addView(Ui.icon(context, R.drawable.ic_search, 20, R.color.muted))
            addView(searchBox, Ui.lp(0).apply { weight = 1f })
        }

        chips = mapOf(
            SortKey.NAME to (chip("Name") to "Name"),
            SortKey.TODAY to (chip("Today") to "Today"),
            SortKey.WEEK to (chip("7 days") to "7 days"))
        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.muted(context, "Sort", 13f).apply {
                setPadding(0, 0, Ui.dp(context, 10), 0)
            })
            for ((view, _) in chips.values) addView(view)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 12), 0, 0)
            addView(Ui.header(this@AppPickerActivity, "Choose apps"),
                Ui.lp().apply { marginStart = side; marginEnd = side })
            addView(usageCard, Ui.lp().apply {
                marginStart = side; marginEnd = side; topMargin = Ui.dp(context, 16)
            })
            addView(searchField, Ui.lp().apply {
                marginStart = side; marginEnd = side; topMargin = Ui.dp(context, 16)
            })
            addView(Ui.muted(context, "New blocks start at 5 minutes in any 2 hours. Change it after.", 13f),
                Ui.lp().apply { marginStart = side; marginEnd = side; topMargin = Ui.dp(context, 12) })
            addView(sortRow, Ui.lp().apply {
                marginStart = side; marginEnd = side
                topMargin = Ui.dp(context, 12); bottomMargin = Ui.dp(context, 6)
            })
            addView(listView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        }
        setContentView(FrameLayout(this).apply {
            addView(page, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(confirmBar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        })
    }

    private fun makeRow(iconDp: Int): Row {
        val icon = Ui.appIcon(this, getDrawable(android.R.drawable.sym_def_app_icon)!!, iconDp)
        val name = Ui.txt(this, "", 16f, medium = true, tracking = -0.01f).apply { maxLines = 1 }
        val usage = Ui.muted(this, "", 13f)
        val tick = Ui.icon(this, R.drawable.ic_check, 16, R.color.on_ink)
        val check = FrameLayout(this).apply {
            addView(tick, FrameLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16), Gravity.CENTER))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(context, 20)
            setPadding(p, Ui.dp(context, 10), p, Ui.dp(context, 10))
            addView(icon)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(usage, Ui.lp().apply { topMargin = Ui.dp(context, 2) })
            }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 14); marginEnd = Ui.dp(context, 12) })
            addView(check, LinearLayout.LayoutParams(Ui.dp(this@AppPickerActivity, 26), Ui.dp(this@AppPickerActivity, 26)))
        }
        return Row(root, icon, name, usage, check, tick)
    }

    /** Filled ink circle with a tick when picked, hairline ring when not. */
    private fun bindCheck(row: Row, checked: Boolean, animate: Boolean) {
        row.check.background = if (checked) Ui.shape(this, R.color.ink, 0f, oval = true)
        else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(Ui.dp(this@AppPickerActivity, 2), getColor(R.color.line))
            setColor(Color.TRANSPARENT)
        }
        row.tick.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        if (animate && checked) Ui.pop(row.check)
    }

    /** Slide the confirm pill up when the first app is picked, away when the last is dropped. */
    private fun refreshConfirm() {
        confirm.label.text = if (selected.size == 1) "Block 1 selected app"
        else "Block ${selected.size} selected apps"
        val show = selected.isNotEmpty()
        val bar = confirmBar
        val rise = Ui.dpf(this, 24f)
        if (show && bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            if (Ui.motionOn(this)) {
                bar.alpha = 0f
                bar.translationY = rise
                bar.animate().alpha(1f).translationY(0f).setDuration(260L)
                    .setInterpolator(Ui.settle).start()
            }
        } else if (!show && bar.visibility == View.VISIBLE) {
            if (Ui.motionOn(this)) {
                bar.animate().alpha(0f).translationY(rise).setDuration(160L)
                    .setInterpolator(Ui.settle)
                    .withEndAction { if (selected.isEmpty()) bar.visibility = View.GONE }.start()
            } else bar.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the usage-access settings page (or first landing).
        haveUsage = usageAccessGranted(this)
        usageCard.visibility = if (haveUsage || Storage.usageReminderDismissed(this))
            View.GONE else View.VISIBLE
        if (haveUsage) loadUsage()
        // Usage sorts need usage data; fall back to name until access is
        // granted, then to the 7-days default unless the user chose a sort.
        if (!haveUsage && sortKey != SortKey.NAME) {
            sortKey = SortKey.NAME; descending = false
        } else if (haveUsage && !userSorted) {
            sortKey = SortKey.WEEK; descending = true
        }
        refreshShown()
    }

    /** Screen time per package: today (since midnight) and the last 7 days. */
    private fun loadUsage() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = usm.queryAndAggregateUsageStats(midnight, now)
        val week = usm.queryAndAggregateUsageStats(now - 7 * 24 * 3_600_000L, now)
        for (app in allApps) {
            app.todayMs = day[app.pkg]?.totalTimeInForeground ?: 0L
            app.weekMs = week[app.pkg]?.totalTimeInForeground ?: 0L
        }
    }

    /** Re-apply the search filter and the active sort, then repaint. */
    private fun refreshShown() {
        val base: Comparator<Entry> = when (sortKey) {
            SortKey.NAME -> compareBy { it.label.lowercase() }
            SortKey.TODAY -> compareBy { it.todayMs }
            SortKey.WEEK -> compareBy { it.weekMs }
        }
        val cmp = (if (descending) base.reversed() else base)
            .thenBy { it.label.lowercase() }
        shown.clear()
        shown.addAll(allApps
            .filter { query.isEmpty() || query in it.label.lowercase() }
            .sortedWith(cmp))
        adapter.notifyDataSetChanged()
        for ((key, pair) in chips) {
            val (view, label) = pair
            val active = key == sortKey
            view.text = if (!active) label else label + if (descending) " ↓" else " ↑"
            view.setTextColor(getColor(if (active) R.color.on_ink else R.color.ink))
            view.background = if (active) Ui.shape(this, R.color.ink, 99f)
            else Ui.shape(this, R.color.surface, 99f, R.color.line)
            // Usage sorts are dead without usage access: visible but greyed.
            view.alpha = if (key != SortKey.NAME && !haveUsage) 0.4f else 1f
        }
    }

    /** Sort chip: tap to sort by it, tap again to flip direction. */
    private fun chip(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Ui.sans(context, medium = true)
        setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            marginEnd = Ui.dp(context, 8)
        }
        Ui.press(this, 0.95f)
        setOnClickListener {
            val key = chips.entries.first { it.value.first == this }.key
            if (key != SortKey.NAME && !haveUsage) {
                // A dead chip explains itself properly, with the fix a tap away.
                lateinit var sheet: Sheet
                sheet = Sheet(this@AppPickerActivity)
                    .header("Sorting needs Usage access")
                    .message("These sorts rank apps by screen time, " +
                        "which Android only shares after you grant " +
                        "AppBlock the Usage access permission.")
                sheet.actions(
                    sheet.secondary("Not now") { sheet.dismiss() },
                    sheet.primary("Open settings") {
                        sheet.dismiss()
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    })
                sheet.show()
                return@setOnClickListener
            }
            userSorted = true
            if (sortKey == key) descending = !descending
            else {
                sortKey = key
                descending = key != SortKey.NAME   // usage starts big-first
            }
            refreshShown()
        }
    }

    private fun iconFor(pkg: String): Drawable = icons.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) }
        catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
    }
}
