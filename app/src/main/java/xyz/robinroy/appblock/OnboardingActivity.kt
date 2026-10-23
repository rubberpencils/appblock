package xyz.robinroy.appblock

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * First run: four swipeable pages that explain the idea in the order a
 * person needs it (why, how, what you'll see, what it never does). Shown
 * once, on first run. The pager follows the finger one-to-one and
 * settles on release with the finger's velocity, so a flick is enough.
 */
class OnboardingActivity : Activity() {

    private lateinit var pager: Pager
    private lateinit var cta: Ui.Pill
    private lateinit var skip: TextView
    private lateinit var activePill: View
    private val played = mutableSetOf<Int>()
    private val intros = mutableMapOf<Int, () -> Unit>()
    private var shownLabelIndex = -1
    private val count = 4

    // Back steps to the page before. Android 13+ dispatches back to callbacks
    // registered ahead of time (16 stops calling onBackPressed), so this one
    // is registered only off the first page; there back is the system's.
    private val backToPage =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { pager.goTo(pager.page - 1) } else null
    private var backToPageOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        title = "Tie yourself to the mast"

        val pages = listOf(mastPage(), allowancePage(), wallPage(), privatePage())
        pager = Pager(this).also { it.setPages(pages) }

        skip = Ui.txt(this, "Skip", 15f, R.color.muted, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 16), 0, 0, 0)
            isClickable = true
            setOnClickListener { finishOnboarding() }
        }

        val slot = Ui.dp(this, 14)
        val dots = FrameLayout(this).apply {
            for (i in 0 until count) addView(View(context).apply {
                background = Ui.shape(context, R.color.muted, 0f, oval = true).apply { alpha = 90 }
            }, FrameLayout.LayoutParams(Ui.dp(context, 6), Ui.dp(context, 6)).apply {
                leftMargin = i * slot + Ui.dp(context, 4); topMargin = Ui.dp(context, 1)
            })
            activePill = View(context).apply { background = Ui.shape(context, R.color.ink, 99f) }
            addView(activePill, FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 6)).apply {
                topMargin = Ui.dp(context, 1)
            })
        }

        cta = Ui.primaryButton(this, "Continue", R.drawable.ic_chevron) {
            if (pager.page >= count - 1) finishOnboarding() else pager.goTo(pager.page + 1)
        }

        // Same row as the home screen's header: 44 dp tall, 20 dp from the edges.
        val top = FrameLayout(this).apply {
            addView(skip, FrameLayout.LayoutParams(Ui.WRAP, Ui.dp(context, 44), Gravity.END))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val side = Ui.dp(context, 20)
            setPadding(side, Ui.dp(context, 8), side, Ui.dp(context, 24))
            addView(dots, Ui.lp(Ui.dp(context, count * 14 + 4), Ui.dp(context, 8)))
            addView(cta, Ui.lp().apply { topMargin = Ui.dp(context, 20) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = Ui.dp(context, 20)
            addView(top, Ui.lp().apply {
                marginStart = side; marginEnd = side
                topMargin = Ui.dp(context, 12)
            })
            addView(pager, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(bottom)
        })

        pager.onPosition = { pos ->
            activePill.translationX = pos * slot
            val index = pos.roundToInt().coerceIn(0, count - 1)
            setBackToPage(index > 0)
            if (index != shownLabelIndex) {
                shownLabelIndex = index
                cta.label.text = if (index == count - 1) "Get started" else "Continue"
                if (played.add(index)) intros[index]?.invoke()
            }
            skip.alpha = (count - 1 - pos).coerceIn(0f, 1f)
            skip.isEnabled = skip.alpha > 0.5f
            skip.visibility = if (skip.alpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        }
        pager.onPosition?.invoke(0f)
    }

    private fun setBackToPage(on: Boolean) {
        if (backToPage == null || on == backToPageOn) return
        backToPageOn = on
        if (on) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToPage)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToPage)
    }

    /** Android 12 and older: back still comes here. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pager.page > 0) pager.goTo(pager.page - 1)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun finishOnboarding() {
        Storage.setOnboardingSeen(this)
        finish()
    }

    // ----------------------------------------------------------------- pages

    /** Hero on top (takes the spare height), then eyebrow, title and body. */
    private fun page(hero: View, eyebrow: String, heading: String, body: String): View {
        val side = Ui.dp(this, 20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 8))
            addView(hero, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply {
                bottomMargin = Ui.dp(context, 24)
            })
            addView(Ui.eyebrow(context, eyebrow))
            addView(Ui.display(context, heading, 30f), Ui.lp().apply { topMargin = Ui.dp(context, 10) })
            addView(Ui.body(context, body, 16f).apply { setTextColor(context.getColor(R.color.muted)) },
                Ui.lp().apply { topMargin = Ui.dp(context, 12) })
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
    }

    private fun roundOff(view: View, radiusDp: Float) {
        val r = Ui.dpf(this, radiusDp)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        view.clipToOutline = true
    }

    private fun mastPage(): View {
        val painting = ImageView(this).apply {
            setImageResource(R.drawable.ulysses_sirens)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ulysses tied to the mast of his ship while " +
                "winged Sirens circle the rowing crew"
        }
        roundOff(painting, 28f)
        val caption = Ui.muted(this, "Ulysses and the Sirens, J. W. Waterhouse, 1891", 15f).apply {
            typeface = Ui.serif(context, italic = true)
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(painting, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(caption, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
        }
        return page(
            hero, "The idea", "Tie yourself to the mast.",
            "Odysseus knew the Sirens would win in the moment, so he decided " +
                "beforehand and had his crew tie him down. AppBlock is your mast: " +
                "set the rules while you're clear-headed, and let the phone keep them."
        )
    }

    private fun allowancePage(): View {
        val window = RollingWindow(this)
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.display(context, "5 min", 34f).apply { gravity = Gravity.CENTER })
            addView(Ui.muted(context, "in any 2 hours", 14f).apply { gravity = Gravity.CENTER },
                Ui.lp().apply { topMargin = Ui.dp(context, 4) })
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label)
            addView(window, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(context, 168)).apply {
                topMargin = Ui.dp(context, 28)
            })
        }
        intros[1] = { window.play() }
        return page(
            hero, "The rule", "A small allowance, on a loop.",
            "Each app gets a few minutes inside a longer window. As the window " +
                "moves forward, used time drops out and the minutes come back. " +
                "No midnight reset to burn through in one sitting."
        )
    }

    private fun wallPage(): View {
        // A miniature of the real block wall.
        val lock = FrameLayout(this).apply {
            background = Ui.shape(context, R.color.on_ink, 0f, oval = true).apply { alpha = 30 }
            addView(Ui.icon(context, R.drawable.ic_lock, 26, R.color.on_ink).apply {
                layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 26), Ui.dp(context, 26), Gravity.CENTER)
            })
        }
        val plate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 24), Ui.dp(context, 28), Ui.dp(context, 24), Ui.dp(context, 28))
            background = Ui.shape(context, R.color.ink, 24f)
            addView(lock, Ui.lp(Ui.dp(context, 64), Ui.dp(context, 64)))
            addView(Ui.txt(context, "This app is blocked.", 26f, R.color.on_ink, tracking = -0.01f, editorial = true),
                Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 18) })
            addView(Ui.txt(context, "Try again in 42 min.", 15f, R.color.on_ink).apply { alpha = 0.6f },
                Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 6) })
            addView(TextView(context).apply {
                text = "Go to home screen"
                textSize = 14f
                typeface = Ui.sans(context, medium = true)
                setTextColor(context.getColor(R.color.ink))
                background = Ui.shape(context, R.color.on_ink, 99f)
                setPadding(Ui.dp(context, 20), Ui.dp(context, 11), Ui.dp(context, 20), Ui.dp(context, 11))
            }, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 24) })
        }
        val hero = FrameLayout(this).apply {
            addView(Ui.bezel(context, plate, 32f, R.color.ink),
                FrameLayout.LayoutParams(Ui.dp(context, 280), Ui.WRAP, Gravity.CENTER))
        }
        intros[2] = { Ui.pop(hero.getChildAt(0)) }
        return page(
            hero, "The wall", "A calm stop, not a nag.",
            "When the time is gone, a wall covers the app, even in split screen " +
                "or a pop-up window, and shows you the way out. With notifications on, you get a gentle note at halfway " +
                "and again near the end."
        )
    }

    private fun privatePage(): View {
        fun fact(icon: Int, heading: String, copy: String) =
            Ui.row(this, icon, heading, copy)
        val facts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 18), Ui.dp(context, 8), Ui.dp(context, 18), Ui.dp(context, 8))
            addView(fact(R.drawable.ic_shield, "Stays on your phone", "No account, no analytics, nothing about you sent."))
            addView(fact(R.drawable.ic_bell, "Leaves messages alone", "Notifications and downloads keep running."))
            addView(fact(R.drawable.ic_clock, "Counts screen time only", "Just time you spend in the app."))
        }
        val hero = FrameLayout(this).apply {
            addView(Ui.bezel(context, facts, 30f),
                FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        }
        intros[3] = { Ui.pop(hero.getChildAt(0)) }
        return page(
            hero, "The promise", "Private, and easy to leave.",
            "Next you'll turn on two settings: AppBlock in Accessibility, to see " +
                "which apps are on screen and draw the wall, and Unrestricted battery " +
                "so Android doesn't stop the blocker. Usage access (screen-time " +
                "stats) and notifications (the countdowns) are optional."
        )
    }

    /**
     * What a rolling window is. The tinted span is the last two hours, pinned
     * between "2 hrs ago" and "now". Stretches of use sit on that timeline,
     * each labelled with its minutes, and age left. Inside the span they
     * count; once one crosses the back edge it lifts, turns green, and hands
     * its minutes back. The same three repeat, and a full turn lands on its
     * first frame. (sr2echa's, from redesign-zen-ui.)
     */
    private class RollingWindow(context: Context) : View(context) {
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

    // ----------------------------------------------------------------- pager

    /**
     * Horizontal pager. `pos` is a float page position; each page sits at
     * (index - pos) widths from the edge, so dragging and settling are the
     * same code path, and moving pages is only translation.
     */
    private class Pager(context: Context) : FrameLayout(context) {
        var onPosition: ((Float) -> Unit)? = null
        private var pos = 0f
        private var animator: ValueAnimator? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFling = 500f * context.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var dragStartX = 0f
        private var dragStartPos = 0f
        private var tracker: VelocityTracker? = null

        val page get() = pos.roundToInt().coerceIn(0, childCount - 1)

        fun setPages(views: List<View>) {
            views.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            place(pos)
        }

        private fun place(p: Float) {
            pos = p
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.translationX = (i - p) * width
                // Pages off screen don't draw or take touches.
                child.visibility = if (abs(i - p) < 1f) View.VISIBLE else View.INVISIBLE
            }
            onPosition?.invoke(p)
        }

        fun goTo(target: Int) {
            val to = target.coerceIn(0, childCount - 1).toFloat()
            animator?.cancel()
            if (!Ui.motionOn(context)) { place(to); return }
            animator = ValueAnimator.ofFloat(pos, to).apply {
                duration = (180 + 160 * abs(to - pos)).toLong().coerceAtMost(340L)
                interpolator = Ui.settle
                addUpdateListener { place(it.animatedValue as Float) }
                start()
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = false
                    // Catching a page mid-settle: take over straight away.
                    if (animator?.isRunning == true) { animator?.cancel(); beginDrag(e); return true }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) { beginDrag(e); return true }
                }
            }
            return false
        }

        private fun beginDrag(e: MotionEvent) {
            dragging = true
            dragStartX = e.x
            dragStartPos = pos
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
            tracker?.addMovement(e)
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) return e.actionMasked == MotionEvent.ACTION_DOWN
            tracker?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    var raw = dragStartPos - (e.x - dragStartX) / width.coerceAtLeast(1)
                    val last = (childCount - 1).toFloat()
                    // Friction past the ends instead of a hard wall.
                    if (raw < 0f) raw *= 0.3f
                    if (raw > last) raw = last + (raw - last) * 0.3f
                    place(raw)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    dragging = false
                    val target = when {
                        e.actionMasked == MotionEvent.ACTION_CANCEL -> pos.roundToInt()
                        vx < -minFling -> floor(pos).toInt() + 1
                        vx > minFling -> ceil(pos).toInt() - 1
                        else -> pos.roundToInt()
                    }
                    goTo(target)
                }
            }
            return true
        }
    }
}
