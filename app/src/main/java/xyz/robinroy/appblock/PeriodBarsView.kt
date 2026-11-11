package xyz.robinroy.appblock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate

/**
 * A week, a month or a year of screen time: a bar per day (per month in
 * a year, a day's average in it), from Storage.PeriodStats. Each bar is
 * the limited apps in the accent with the other apps stacked on top in
 * the quiet neutral, the colors of the day's chart and its legend; the
 * days still to come are left blank, and so are those before the history
 * began: neither can be read off. On the right, the time a bar
 * stands for, in round steps (Storage.barTicks); underneath, the days or
 * months (Storage.barLabels). A tap or a drag on it reads a bar off
 * exactly (Readout); `single` is one app's chart, one series, no names.
 * Rounded tops, flat on the baseline, as the peak hours' bars.
 */
class PeriodBarsView(context: Context) : View(context) {
    private var stats: Storage.PeriodStats? = null
    private var period = Storage.Period.WEEK
    private var today: LocalDate = LocalDate.now()
    private var single = false
    private var picked: Int? = null
    private var reveal = 1f
    private var growing: ValueAnimator? = null
    private var slotW = 0f
    private var plotW = 0f
    private val bubble = Readout.Bubble(context)
    private val touch = Readout.Touch(this) { x -> pick(x) }

    /** What it says to TalkBack (and the tests) with nothing picked; a pick says its values. */
    var summary: String = ""
        set(v) { field = v; if (picked == null) contentDescription = v }

    private val limitedBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val otherBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.chart_other_line) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        strokeWidth = Ui.dpf(context, 1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = Ui.dpf(context, 11f)
        typeface = Ui.sans(context)
    }
    private val rect = RectF()

    fun set(stats: Storage.PeriodStats, period: Storage.Period, today: LocalDate, single: Boolean, animate: Boolean) {
        this.stats = stats
        this.period = period
        this.today = today
        this.single = single
        unpick()
        // One still growing the last bars mustn't go on to shrink these.
        growing?.cancel()
        growing = null
        if (animate && Ui.motionOn(context)) {
            growing = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450L
                interpolator = Ui.settle
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { reveal = 1f; invalidate() }
    }

    private fun barMs(s: Storage.PeriodStats, i: Int) = s.limited[i] + s.other[i]

    /** With nothing to read off (no screen time in it at all), a touch is the card's, not the chart's. */
    override fun onTouchEvent(e: MotionEvent): Boolean =
        if (stats.let { it == null || it.totalMs <= 0L || it.since >= it.known }) super.onTouchEvent(e) else touch.handle(e)

    /** The bar under `x`, or the nearest with a day the history has: from its first day to today. */
    private fun pick(x: Float) {
        val s = stats ?: return
        if (slotW <= 0f || s.since >= s.known) return
        val i = (x.coerceIn(0f, plotW - 1f) / slotW).toInt().coerceIn(s.since, s.known - 1)
        if (picked != i) {
            picked = i
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
        Readout.showing(parent as? View ?: this) { unpick() }
    }

    /** The pick taken away: another week, the calendar opening. */
    fun unpick() {
        if (picked == null) return
        picked = null
        Readout.hidden(parent as? View ?: this)
        contentDescription = summary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = stats ?: return
        val n = s.starts.size
        if (n == 0) return
        val labelH = Ui.dpf(context, 20f)
        val top = Ui.dpf(context, 8f)
        val bottom = height - labelH
        val plotH = bottom - top
        var peak = 0L
        for (i in 0 until n) peak = maxOf(peak, barMs(s, i))
        val ticks = Storage.barTicks(peak)
        val gutter = label.measureText(Storage.WIDEST_BAR_TICK) + Ui.dpf(context, 8f)
        val w = width - gutter
        val slot = w / n
        slotW = slot
        plotW = w
        val scaleMs = (ticks.lastOrNull() ?: 1) * 60_000f
        for (m in ticks) {
            val gy = bottom - m * 60_000f / scaleMs * plotH
            canvas.drawLine(0f, gy, w, gy, grid)
            canvas.drawText(Storage.fmtBarTick(m, ticks), w + Ui.dpf(context, 6f), gy + label.textSize * 0.35f, label)
        }
        for ((i, text) in Storage.barLabels(period, s.starts)) {
            val tw = label.measureText(text)
            canvas.drawText(text, ((i + 0.5f) * slot - tw / 2).coerceIn(0f, w - tw), height - Ui.dpf(context, 4f), label)
        }
        canvas.drawLine(0f, bottom, w, bottom, grid)
        if (ticks.isEmpty()) return

        val gap = (slot * 0.28f).coerceAtLeast(Ui.dpf(context, 2f))
        val r = Ui.dpf(context, 4f).coerceAtMost((slot - gap) / 2)
        val p = picked
        for (i in 0 until s.known.coerceAtMost(n)) {
            val ms = barMs(s, i)
            if (ms <= 0L) continue
            val barH = (plotH * ms / scaleMs).coerceAtLeast(Ui.dpf(context, 3f)) * reveal
            val limH = barH * s.limited[i] / ms
            val left = i * slot + gap / 2
            val right = (i + 1) * slot - gap / 2
            // The bar picked stands out: the rest go soft.
            val alpha = if (p == null || p == i) 255 else 110
            limitedBar.alpha = alpha
            otherBar.alpha = alpha
            // One rounded bar, its bottom corners below the baseline and clipped away,
            // drawn in two parts: the other apps above where the limited ones end.
            rect.set(left, bottom - barH, right, bottom + r)
            if (s.other[i] > 0L) {
                canvas.save()
                canvas.clipRect(0f, 0f, width.toFloat(), bottom - limH)
                canvas.drawRoundRect(rect, r, r, otherBar)
                canvas.restore()
            }
            if (s.limited[i] > 0L) {
                canvas.save()
                canvas.clipRect(0f, bottom - limH, width.toFloat(), bottom)
                canvas.drawRoundRect(rect, r, r, limitedBar)
                canvas.restore()
            }
        }

        // The bar picked: its day (or month), and its exact values.
        if (p == null || p >= s.known) return
        val heading = Storage.barTitle(period, s.starts[p], today)
        val rows = if (single) listOf(null to Storage.fmtScreenTime(s.limited[p]))
        else listOf(R.color.accent to "Limited apps: ${Storage.fmtScreenTime(s.limited[p])}",
            R.color.chart_other_line to "Other apps: ${Storage.fmtScreenTime(s.other[p])}")
        bubble.draw(canvas, (p + 0.5f) * slot, w, heading, rows)
        contentDescription = "$heading: " + rows.joinToString(", ") { it.second }
    }
}
