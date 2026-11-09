package xyz.robinroy.appblock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * When an app gets used: a bar per hour of the clock, midnight to 11 PM,
 * each its share of the busiest hour (Storage.AppStats.hours). One series,
 * in the app's color on the day chart (the accent if it's limited); the
 * peak hour in full, the rest softer. Rounded tops, flat on the baseline;
 * labels at midnight, 6 AM, noon and 6 PM, as on the day chart.
 */
class HourBarsView(context: Context) : View(context) {
    private var hours = LongArray(24)
    private var peak: Int? = null

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

    fun set(hours: LongArray, peak: Int?, colorRes: Int) {
        this.hours = hours
        this.peak = peak
        bar.color = context.getColor(colorRes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = Ui.dpf(context, 20f)
        val top = Ui.dpf(context, 4f)
        val bottom = height - labelH
        val w = width.toFloat()
        val slot = w / 24f
        for ((h, text) in listOf(0 to "12 AM", 6 to "6 AM", 12 to "Noon", 18 to "6 PM")) {
            val tw = label.measureText(text)
            val lx = if (h == 0) 0f else h * slot - tw / 2
            canvas.drawText(text, lx.coerceIn(0f, w - tw), height - Ui.dpf(context, 4f), label)
        }
        canvas.drawLine(0f, bottom, w, bottom, grid)
        val max = hours.maxOrNull() ?: 0L
        if (max <= 0L) return
        val gap = (slot * 0.28f).coerceAtLeast(Ui.dpf(context, 2f))
        val r = Ui.dpf(context, 4f).coerceAtMost((slot - gap) / 2)
        for (h in 0 until 24) {
            if (hours[h] <= 0L) continue
            val barH = ((bottom - top) * hours[h] / max).coerceAtLeast(Ui.dpf(context, 3f))
            rect.set(h * slot + gap / 2, bottom - barH, (h + 1) * slot - gap / 2, bottom + r)
            bar.alpha = if (h == peak) 255 else 110
            // Rounded at the top only: the bottom corners sit below the baseline, clipped away.
            canvas.save()
            canvas.clipRect(0f, 0f, w, bottom)
            canvas.drawRoundRect(rect, r, r, bar)
            canvas.restore()
        }
    }
}
