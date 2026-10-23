package xyz.robinroy.appblock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * What a rolling window is. The tinted span is the last two hours, pinned
 * between "2 hrs ago" and "now". Stretches of use sit on that timeline,
 * each labelled with its minutes, and age left. Inside the span they
 * count; once one crosses the back edge it lifts, turns green, and hands
 * its minutes back. The same three repeat, and a full turn lands on its
 * first frame. Onboarding's "The rule" page and How it works both show it.
 * (sr2echa's, from redesign-zen-ui.)
 */
class RollingWindow(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent_soft) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = 12f * density
        typeface = Ui.sans(context)
    }
    // In dp, not sp: each box is sized to fit its label.
    private val minutes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_ink)
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        typeface = Ui.sans(context, medium = true)
    }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        textSize = 17f * density
        textAlign = Paint.Align.CENTER
        typeface = Ui.serif(context, italic = true)
    }
    private val ink = context.getColor(R.color.ink)
    private val accent = context.getColor(R.color.accent)
    private val quiet = context.getColor(R.color.line)
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Minutes of use ageing out of the last two hours, " +
            "each handing its minutes back as it leaves"
    }

    fun play() {
        if (!Ui.motionOn(context)) { shown = 0f; invalidate(); return }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 350L
            duration = 6400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val running = animator ?: return
        if (isShown) { if (running.isPaused) running.resume() }
        else if (running.isStarted) running.pause()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val span = width * 0.70f
        val leftEdge = (width - span) / 2f
        val rightEdge = leftEdge + span
        val axis = height * 0.64f
        val washTop = axis - 40f * density
        val washBot = axis + 8f * density
        val radius = 12f * density

        // The window is a span of time, not a control. Time keeps running
        // on either side of it; only what sits inside counts.
        canvas.drawRoundRect(leftEdge, washTop, rightEdge, washBot, radius, radius, wash)

        mark.style = Paint.Style.STROKE
        mark.strokeCap = Paint.Cap.ROUND
        mark.color = quiet
        mark.alpha = 255
        mark.strokeWidth = 1.5f * density
        canvas.drawLine(18f * density, axis, width - 18f * density, axis, mark)

        // Three stretches of use. age 0 is born at "now"; age 1 has just
        // crossed "2 hrs ago". Offsets are far enough apart that only one
        // is ever leaving.
        val bornAt = floatArrayOf(0.06f, 0.40f, 0.72f)
        val mins = intArrayOf(2, 3, 1)
        val wide = floatArrayOf(50f, 60f, 44f)
        val tall = floatArrayOf(28f, 34f, 24f)
        for (s in bornAt.indices) {
            val age = (shown + bornAt[s]) % 1f
            val w = wide[s] * density
            val h = tall[s] * density
            val right = rightEdge - age * span
            val left = right - w
            // 0 while the stretch is still inside; 1 once it has cleared
            // the back edge. The label rides that crossing, not a timer.
            val past = ((leftEdge - left) / w).coerceIn(0f, 1.4f)
            val leaving = (past / 1.15f).coerceIn(0f, 1f)
            val fadeIn = (age / 0.06f).coerceIn(0f, 1f)
            // Stay solid through the crossing, then fade once it's clearly out.
            val fadeOut = (1f - ((leaving - 0.42f) / 0.58f).coerceIn(0f, 1f))
            val alpha = fadeIn * fadeOut
            val lift = leaving * 18f * density
            val top = axis - h - lift
            val bot = axis - lift
            val rad = 6f * density
            val handedBack = leaving > 0.2f
            val label = "${mins[s]} min"
            val baseline = (top + bot) / 2f - (minutes.descent() + minutes.ascent()) / 2f

            fun box(clipL: Float, clipR: Float, color: Int, a: Float) {
                canvas.save()
                canvas.clipRect(clipL, 0f, clipR, height.toFloat())
                mark.color = color
                mark.alpha = (a * 255).toInt()
                canvas.drawRoundRect(left, top, right, bot, rad, rad, mark)
                minutes.alpha = (a * 255).toInt()
                canvas.drawText(label, (left + right) / 2f, baseline, minutes)
                canvas.restore()
            }
            mark.style = Paint.Style.FILL
            if (!handedBack) box(leftEdge, rightEdge, ink, alpha * 235 / 255)
            if (left < leftEdge || handedBack) box(0f, if (handedBack) width.toFloat() else leftEdge, accent, alpha)

            // Hand the minutes back once most of the box is out.
            if (leaving > 0.35f) {
                val hold = (1f - ((leaving - 0.62f) / 0.38f).coerceIn(0f, 1f))
                plus.alpha = (hold * alpha * 255).toInt()
                val x = ((left + right) / 2f).coerceIn(40f * density, leftEdge + 40f * density)
                canvas.drawText("+$label", x, top - 10f * density, plus)
            }
        }

        // The two edges of the window, drawn over the minutes so the span
        // stays readable while one is crossing out.
        mark.style = Paint.Style.STROKE
        mark.strokeWidth = 1.5f * density
        mark.color = ink
        mark.alpha = 90
        canvas.drawLine(leftEdge, washTop + 8f * density, leftEdge, washBot - 4f * density, mark)
        mark.alpha = 220
        mark.strokeWidth = 2f * density
        canvas.drawLine(rightEdge, washTop + 6f * density, rightEdge, washBot - 3f * density, mark)
        mark.style = Paint.Style.FILL

        val base = washBot + 18f * density
        caption.alpha = 190
        caption.textAlign = Paint.Align.LEFT
        canvas.drawText("2 hrs ago", leftEdge, base, caption)
        caption.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", rightEdge, base, caption)
    }
}
