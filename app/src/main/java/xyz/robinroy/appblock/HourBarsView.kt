package xyz.robinroy.appblock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * When an app gets used: a bar per hour of the clock, midnight to 11 PM,
 * each its share of the busiest hour (Storage.AppStats.hours). One series,
 * in the accent; the peak hour in full, the rest softer. On the right, the
 * minutes a day in that hour, on average, in round steps (Storage.niceTicks).
 * A tap or a drag on it reads an hour off exactly (Readout). Rounded tops, flat on the baseline;
 * labels at midnight, 6 AM, noon and 6 PM, as on the day chart.
 */
class HourBarsView(context: Context) : View(context) {
    private var hours = LongArray(24)
    private var peak: Int? = null
    private var days = 1
    private var picked: Int? = null
    private var slotW = 0f
    private var plotW = 0f
    private val bubble = Readout.Bubble(context)
    private val touch = Readout.Touch(this) { x -> pick(x) }

    /** What it says to TalkBack (and the tests) with nothing picked; a pick says its value. */
    var summary: String = ""
        set(v) { field = v; if (picked == null) contentDescription = v }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
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

    fun set(hours: LongArray, peak: Int?, colorRes: Int, days: Int) {
        this.hours = hours
        this.peak = peak
        this.days = days.coerceAtLeast(1)
        unpick()
        bar.color = context.getColor(colorRes)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean =
        if (hours.all { it <= 0L }) super.onTouchEvent(e) else touch.handle(e)

    private fun pick(x: Float) {
        if (slotW <= 0f) return
        val h = (x.coerceIn(0f, plotW - 1f) / slotW).toInt().coerceIn(0, 23)
        if (picked != h) {
            picked = h
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
        Readout.showing(parent as? View ?: this) { unpick() }
    }

    private fun unpick() {
        if (picked == null) return
        picked = null
        Readout.hidden(parent as? View ?: this)
        contentDescription = summary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = Ui.dpf(context, 20f)
        val top = Ui.dpf(context, 4f)
        val bottom = height - labelH
        // Minutes a day in each hour, on average.
        val perDay = FloatArray(24) { hours[it] / 60_000f / days }
        val ticks = Storage.niceTicks(perDay.maxOrNull() ?: 0f, 60)
        val gutter = label.measureText(Storage.fmtTick(60)) + Ui.dpf(context, 8f)
        val w = width - gutter
        val slot = w / 24f
        slotW = slot
        plotW = w
        val scaleMin = (ticks.lastOrNull() ?: 60).toFloat()
        for (m in ticks) {
            val gy = bottom - m / scaleMin * (bottom - top)
            canvas.drawLine(0f, gy, w, gy, grid)
            canvas.drawText(Storage.fmtTick(m), w + Ui.dpf(context, 6f), gy + label.textSize * 0.35f, label)
        }
        for ((h, text) in listOf(0 to "12 AM", 6 to "6 AM", 12 to "Noon", 18 to "6 PM")) {
            val tw = label.measureText(text)
            val lx = if (h == 0) 0f else h * slot - tw / 2
            canvas.drawText(text, lx.coerceIn(0f, w - tw), height - Ui.dpf(context, 4f), label)
        }
        canvas.drawLine(0f, bottom, w, bottom, grid)
        if (ticks.isEmpty()) return
        val gap = (slot * 0.28f).coerceAtLeast(Ui.dpf(context, 2f))
        val r = Ui.dpf(context, 4f).coerceAtMost((slot - gap) / 2)
        for (h in 0 until 24) {
            if (hours[h] <= 0L) continue
            val barH = ((bottom - top) * perDay[h] / scaleMin).coerceAtLeast(Ui.dpf(context, 3f))
            rect.set(h * slot + gap / 2, bottom - barH, (h + 1) * slot - gap / 2, bottom + r)
            // The hour picked stands out; with none, the peak.
            bar.alpha = if (h == (picked ?: peak)) 255 else 110
            // Rounded at the top only: the bottom corners sit below the baseline, clipped away.
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), bottom)
            canvas.drawRoundRect(rect, r, r, bar)
            canvas.restore()
        }
        val p = picked ?: return
        val text = when {
            hours[p] <= 0L -> "Not used"
            days > 1 -> Storage.fmtExact(hours[p] / days) + " a day on average"
            else -> Storage.fmtExact(hours[p])
        }
        bubble.draw(canvas, (p + 0.5f) * slot, w, Storage.hourRange(p), listOf(null to text))
        contentDescription = "${Storage.hourRange(p)}: $text"
    }
}
