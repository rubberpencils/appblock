package xyz.robinroy.appblock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.ZoneId

/**
 * A day's screen time across the day, as two lines from the baseline:
 * limited apps in the accent, other apps in a quiet neutral, each softly
 * filled. A point per slice of the day (Storage.buckets): how much of it
 * was spent on screen. The height is scaled to the day's busiest slice, so
 * the day's shape shows; the totals beside it carry the size. The curve is
 * monotone (it never overshoots a slice), so it shows no peak that wasn't
 * there. The day's remaining hours, not yet lived, are left blank; labels
 * mark midnight, 6 AM, noon and 6 PM by the clock. On the right, the minutes
 * of each slice spent on screen, in round steps (Storage.niceTicks), the
 * height scaled to the top one. A tap or a drag on it reads a slice off
 * exactly (Readout); `single` is one app's chart, one series, no names.
 */
class UsageChartView(context: Context) : View(context) {
    private var limited = FloatArray(0)
    private var other = FloatArray(0)
    private var known = 0
    private var from = 0L
    private var to = 0L
    private var bucketMs = 1L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var reveal = 1f
    private var upTo = 0L
    private var single = false
    private var picked: Int? = null

    /** What it says to TalkBack (and the tests) with nothing picked; a pick says its values. */
    var summary: String = ""
        set(v) { field = v; if (picked == null) contentDescription = v }
    private var plotW = 0f
    private val bubble = Readout.Bubble(context)
    private val touch = Readout.Touch(this) { x -> pick(x) }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink)
        strokeWidth = Ui.dpf(context, 1f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun fill(color: Int, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = context.getColor(color)
        this.alpha = alpha
    }
    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dpf(context, 2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = context.getColor(color)
    }
    private val otherFill = fill(R.color.chart_other, 70)
    private val otherLine = stroke(R.color.chart_other_line)
    private val limitedFill = fill(R.color.accent, 56)
    private val limitedLine = stroke(R.color.accent)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        strokeWidth = Ui.dpf(context, 1f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = Ui.dpf(context, 11f)
        typeface = Ui.sans(context)
    }

    fun set(b: Storage.Buckets, from: Long, to: Long, bucketMs: Long, zone: ZoneId, animate: Boolean,
            upTo: Long = to, single: Boolean = false) {
        this.bucketMs = bucketMs
        this.upTo = upTo
        this.single = single
        unpick()
        limited = b.limited
        other = b.other
        known = b.known
        this.from = from
        this.to = to
        this.zone = zone
        if (animate && Ui.motionOn(context)) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700L
                interpolator = Ui.settle
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { reveal = 1f; invalidate() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = if (known == 0) super.onTouchEvent(e) else touch.handle(e)

    /** The slice under `x`, if the day has got that far. */
    private fun pick(x: Float) {
        if (plotW <= 0f || known == 0) return
        val t = from + ((x / plotW).coerceIn(0f, 0.9999f) * (to - from)).toLong()
        val i = ((t - from) / bucketMs).toInt().coerceIn(0, known - 1)
        if (picked != i) {
            picked = i
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

    /** A slice's time on screen, exactly: its share of the part of it lived. */
    private fun sliceMs(share: Float, i: Int): Long {
        val start = from + i * bucketMs
        val lived = minOf(start + bucketMs, to, maxOf(upTo, start)) - start
        return Math.round(share.toDouble() * lived)
    }

    override fun onDraw(canvas: Canvas) {
        val n = limited.size
        if (n == 0 || to <= from) return
        val labelH = Ui.dpf(context, 20f)
        val top = Ui.dpf(context, 8f)
        val bottom = height - labelH
        val plotH = bottom - top
        // The minute marks: from the day's busiest slice, in minutes.
        val bucketMin = (bucketMs / 60_000L).toInt().coerceAtLeast(1)
        var peak = 0f
        for (i in 0 until known.coerceAtMost(n)) peak = maxOf(peak, limited[i], other[i])
        val ticks = Storage.niceTicks(peak * bucketMin, bucketMin)
        val gutter = label.measureText(Storage.fmtTick(bucketMin)) + Ui.dpf(context, 8f)
        val w = width - gutter
        plotW = w
        fun x(t: Long) = (t - from).toFloat() / (to - from) * w
        val scaleMin = (ticks.lastOrNull() ?: bucketMin).toFloat()
        fun y(share: Float) = bottom - share * bucketMin / scaleMin * plotH
        for (m in ticks) {
            val gy = bottom - m / scaleMin * plotH
            canvas.drawLine(0f, gy, w, gy, grid)
            canvas.drawText(Storage.fmtTick(m), w + Ui.dpf(context, 6f), gy + label.textSize * 0.35f, label)
        }

        // Gridlines and labels at midnight, 6 AM, noon, 6 PM.
        val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        for (h in listOf(0, 6, 12, 18)) {
            // Clock hours: on a 23 or 25 hour day, 6 AM isn't six hours after midnight.
            val t = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
            if (t < from || t >= to) continue
            val gx = x(t)
            if (h != 0) canvas.drawLine(gx, top, gx, bottom, grid)
            val text = when (h) { 0 -> "12 AM"; 6 -> "6 AM"; 12 -> "Noon"; else -> "6 PM" }
            val tw = label.measureText(text)
            val lx = if (h == 0) 0f else gx - tw / 2
            canvas.drawText(text, lx.coerceIn(0f, w - tw), height - Ui.dpf(context, 4f), label)
        }
        canvas.drawLine(0f, bottom, w, bottom, grid)

        val shown = (known * reveal).toInt().coerceIn(0, n)
        if (shown == 0) return
        if (peak <= 0f) return
        fun series(values: FloatArray, fillPaint: Paint, line: Paint) {
            // A series with nothing in it isn't drawn: no line along the baseline.
            if ((0 until known.coerceAtMost(n)).none { values[it] > 0f }) return
            val xs = FloatArray(shown + 2)
            val ys = FloatArray(shown + 2)
            xs[0] = 0f; ys[0] = bottom
            for (i in 0 until shown) {
                xs[i + 1] = x(minOf(to, from + i * bucketMs + bucketMs / 2))
                ys[i + 1] = y(values[i])
            }
            // The whole day: back to the baseline at its end. Today: cut off where now is.
            xs[shown + 1] = x(minOf(to, from + shown * bucketMs))
            ys[shown + 1] = if (shown == n) bottom else ys[shown]
            monotone(xs, ys, bottom)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(topEdge, line)
        }
        series(other, otherFill, otherLine)
        series(limited, limitedFill, limitedLine)

        // The slice picked: a line through it, a dot on each series, and its exact values.
        val i = picked ?: return
        if (i >= known) return
        val start = from + i * bucketMs
        val end = minOf(to, start + bucketMs, maxOf(upTo, start + 1))
        val px = x(minOf(to, start + bucketMs / 2))
        canvas.drawLine(px, top, px, bottom, marker)
        val rows = mutableListOf<Pair<Int?, String>>()
        fun mark(values: FloatArray, color: Int, name: String) {
            dot.color = context.getColor(color)
            canvas.drawCircle(px, y(values[i]), Ui.dpf(context, 5f), ring)
            canvas.drawCircle(px, y(values[i]), Ui.dpf(context, 3.5f), dot)
            val text = Storage.fmtExact(sliceMs(values[i], i))
            rows += if (single) (null to text) else (color to "$name: $text")
        }
        if (single) mark(limited, R.color.accent, "")
        else { mark(limited, R.color.accent, "Limited apps"); mark(other, R.color.chart_other_line, "Other apps") }
        bubble.draw(canvas, px, w, Storage.sliceRange(start, end, zone), rows)
        contentDescription = "${Storage.sliceRange(start, end, zone)}: " + rows.joinToString(", ") { it.second }
    }

    private val topEdge = Path()
    private val path = Path()

    /**
     * `path`: the area under a monotone cubic through the points (Fritsch-Carlson:
     * no overshoot, so never above a slice's value or below zero); `topEdge`:
     * its top line alone.
     */
    private fun monotone(xs: FloatArray, ys: FloatArray, base: Float) {
        val m = xs.size
        val d = FloatArray(m - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-3f) }
        val t = FloatArray(m)
        t[0] = d[0]; t[m - 1] = d[m - 2]
        for (i in 1 until m - 1) t[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until m - 1) {
            if (d[i] == 0f) { t[i] = 0f; t[i + 1] = 0f; continue }
            val a = t[i] / d[i]
            val b = t[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val k = 3f / Math.sqrt(s.toDouble()).toFloat(); t[i] = k * a * d[i]; t[i + 1] = k * b * d[i] }
        }
        path.reset(); topEdge.reset()
        path.moveTo(xs[0], base); path.lineTo(xs[0], ys[0]); topEdge.moveTo(xs[0], ys[0])
        for (i in 0 until m - 1) {
            val h = xs[i + 1] - xs[i]
            val c1x = xs[i] + h / 3; val c1y = ys[i] + t[i] * h / 3
            val c2x = xs[i + 1] - h / 3; val c2y = ys[i + 1] - t[i + 1] * h / 3
            path.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
            topEdge.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        }
        path.lineTo(xs[m - 1], base); path.close()
    }
}
