package xyz.robinroy.appblock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.lang.ref.WeakReference

/**
 * Reading a chart: a tap on it, or a drag along it, picks the slice (or
 * the hour) under the finger, and the chart marks it with a label of its
 * exact values. The pick stays until a tap anywhere outside the chart's
 * card (Readout.outside, from the activity's dispatchTouchEvent) or a new
 * day on it. A drag up or down still scrolls the page.
 */
object Readout {
    /** A chart showing a pick: `card` is what counts as inside, `clear` takes the pick away. */
    private class Shown(val card: WeakReference<View>, val clear: () -> Unit)
    private val shown = mutableListOf<Shown>()

    fun showing(card: View, clear: () -> Unit) {
        shown.removeAll { it.card.get().let { c -> c == null || c === card } }
        shown += Shown(WeakReference(card), clear)
    }

    fun hidden(card: View) {
        shown.removeAll { it.card.get().let { c -> c == null || c === card } }
    }

    /** A touch down outside a chart's card takes its pick away. Call first in Activity.dispatchTouchEvent. */
    fun outside(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_DOWN || shown.isEmpty()) return
        val r = Rect()
        for (s in shown.toList()) {
            val card = s.card.get()
            if (card == null || !card.isAttachedToWindow || !card.getGlobalVisibleRect(r) ||
                !r.contains(e.rawX.toInt(), e.rawY.toInt())) s.clear()
        }
    }

    /**
     * The touch on a chart: a tap picks where it landed, a sideways drag
     * picks as it goes (the page doesn't scroll), an up-and-down drag is
     * left to the page. `pick` gets the x in the view.
     */
    class Touch(private val view: View, private val pick: (Float) -> Unit) {
        private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var scrubbing = false

        fun handle(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; scrubbing = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(e.x - downX)
                    if (!scrubbing && dx > slop && dx > Math.abs(e.y - downY)) {
                        scrubbing = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (scrubbing) pick(e.x)
                }
                MotionEvent.ACTION_UP -> {
                    if (scrubbing || Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < slop) pick(e.x)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
    }

    /** The label: a title, then a line per value with its series' dot. Text in ink, never the series color. */
    class Bubble(private val context: Context) {
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.dpf(context, 1f)
            color = context.getColor(R.color.line)
        }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.muted)
            textSize = Ui.dpf(context, 11f)
            typeface = Ui.sans(context)
        }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.ink)
            textSize = Ui.dpf(context, 12f)
            typeface = Ui.sans(context, medium = true)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        /** Drawn at the top, centred on `x` but kept inside 0..`maxX`. Rows: a series color (or null) and its text. */
        fun draw(canvas: Canvas, x: Float, maxX: Float, heading: String, rows: List<Pair<Int?, String>>) {
            val pad = Ui.dpf(context, 8f)
            val dotW = Ui.dpf(context, 12f)
            val lineH = Ui.dpf(context, 16f)
            var w = title.measureText(heading)
            for ((c, t) in rows) w = maxOf(w, value.measureText(t) + if (c != null) dotW else 0f)
            w += 2 * pad
            val h = pad * 2 + lineH * (1 + rows.size) - Ui.dpf(context, 2f)
            val left = (x - w / 2).coerceIn(0f, maxOf(0f, maxX - w))
            rect.set(left, 0f, left + w, h)
            val r = Ui.dpf(context, 10f)
            canvas.drawRoundRect(rect, r, r, back)
            canvas.drawRoundRect(rect, r, r, edge)
            var baseline = pad + Ui.dpf(context, 11f)
            canvas.drawText(heading, left + pad, baseline, title)
            for ((c, t) in rows) {
                baseline += lineH
                var tx = left + pad
                if (c != null) {
                    dot.color = context.getColor(c)
                    canvas.drawCircle(tx + Ui.dpf(context, 4f), baseline - Ui.dpf(context, 4f), Ui.dpf(context, 4f), dot)
                    tx += dotW
                }
                canvas.drawText(t, tx, baseline, value)
            }
        }
    }
}
