package xyz.robinroy.appblock

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Screen time's calendar: a sheet with a month of days, to jump straight
 * to one instead of stepping a day at a time. Only days with a history
 * (its first day to today) can be picked (Storage.pickable); the day
 * showing is filled, today is ringed. Weeks start as the phone's locale
 * says.
 */
object DayPicker {

    fun show(host: Activity, selected: LocalDate, firstDay: LocalDate?, today: LocalDate, pick: (LocalDate) -> Unit) {
        val sheet = Sheet(host)
        val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        var month = YearMonth.from(selected)
        val grid = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val title = Ui.display(host, "", 24f)
        lateinit var prev: View
        lateinit var next: View

        fun choose(day: LocalDate) {
            sheet.dismiss()
            if (day != selected) pick(day)
        }

        fun paint() {
            title.text = month.format(DateTimeFormatter.ofPattern(
                if (month.year == today.year) "MMMM" else "MMMM yyyy", Locale.ENGLISH))
            enable(prev, Storage.monthPickable(month.minusMonths(1), firstDay, today))
            enable(next, Storage.monthPickable(month.plusMonths(1), firstDay, today))
            grid.removeAllViews()
            for (week in Storage.monthGrid(month, firstDow)) {
                grid.addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (day in week) addView(cell(host, day, day == selected, day == today,
                        day != null && Storage.pickable(day, firstDay, today)) { choose(it) },
                        Ui.lp(0, Ui.dp(host, 44)).apply { weight = 1f })
                }, Ui.lp())
            }
        }

        prev = arrow(host, R.drawable.ic_back, "Previous month") { month = month.minusMonths(1); paint() }
        next = arrow(host, R.drawable.ic_chevron, "Next month") { month = month.plusMonths(1); paint() }
        sheet.add(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, Ui.lp(0).apply { weight = 1f })
            addView(prev, LinearLayout.LayoutParams(Ui.dp(host, 40), Ui.dp(host, 40)))
            addView(next, LinearLayout.LayoutParams(Ui.dp(host, 40), Ui.dp(host, 40)).apply {
                marginStart = Ui.dp(host, 8)
            })
        }, 0)
        // The weekday initials, in the week's order.
        sheet.add(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 7) {
                val dow = firstDow.plus(i.toLong())
                addView(Ui.txt(host, dow.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), 12f, R.color.muted, medium = true).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, Ui.lp(0).apply { weight = 1f })
            }
        }, 18)
        sheet.add(grid, 8)
        paint()
        val actions = mutableListOf<View>()
        if (selected != today) actions += sheet.secondary("Today") { choose(today) }
        actions += sheet.primary("Close") { sheet.dismiss() }
        sheet.actions(*actions.toTypedArray())
        sheet.show()
    }

    /** One day: a number in a 40 dp circle. Blank for the cells outside the month. */
    private fun cell(host: Activity, day: LocalDate?, selected: Boolean, today: Boolean, pickable: Boolean,
                     onPick: (LocalDate) -> Unit): View = FrameLayout(host).apply {
        if (day == null) return@apply
        val size = Ui.dp(host, 40)
        val num = TextView(host).apply {
            text = day.dayOfMonth.toString()
            textSize = 15f
            typeface = Ui.sans(host, medium = selected || today)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(host.getColor(if (selected) R.color.on_ink else R.color.ink))
            background = when {
                selected -> Ui.shape(host, R.color.ink, 0f, oval = true)
                today -> Ui.shape(host, R.color.surface, 0f, R.color.ink, oval = true)
                else -> null
            }
            if (!pickable) alpha = 0.3f
        }
        addView(num, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        contentDescription = day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)) +
            (if (today) ", today" else "") + (if (selected) ", showing" else "")
        isSelected = selected
        isEnabled = pickable
        if (pickable) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onPick(day) }
            Ui.press(this, 0.9f)
        }
    }

    private fun arrow(host: Activity, icon: Int, desc: String, onClick: () -> Unit) = FrameLayout(host).apply {
        contentDescription = desc
        isClickable = true
        isFocusable = true
        background = Ui.shape(host, R.color.surface_alt, 0f, oval = true)
        addView(Ui.icon(host, icon, 16, R.color.ink).apply {
            layoutParams = FrameLayout.LayoutParams(Ui.dp(host, 16), Ui.dp(host, 16), Gravity.CENTER)
        })
        setOnClickListener { if (isEnabled) onClick() }
        Ui.press(this, 0.92f)
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.3f
    }
}
