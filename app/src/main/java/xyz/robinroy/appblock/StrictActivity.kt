package xyz.robinroy.appblock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Strict mode, from the home screen's pill. Off: choose the way out (plug
 * in, or a cooldown), which leads to StrictSetupActivity. On: the way out
 * itself (the Turn off button that only works plugged in, or the cooldown
 * to start and cancel, after which Turn off works), and the emergency pass,
 * if there is one. The page
 * follows strict mode as it changes, ticking every second.
 */
class StrictActivity : Activity() {

    /** What the page shows; it's built anew when this changes, and only updated in between. */
    private enum class Phase { CHOOSE, PLUG, COOLDOWN, COOLING, UNLOCKED, PASS, ENDED }

    private val handler = Handler(Looper.getMainLooper())
    private var phase: Phase? = null
    private var update: () -> Unit = {}
    private var wasOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        wasOn = Storage.loadStrict(this).on
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private val tick: Runnable = Runnable {
        val s = Strict.check(this)
        val now = System.currentTimeMillis()
        val next = when {
            s.on -> {
                wasOn = true
                when {
                    Storage.passRunning(s, now) -> Phase.PASS
                    s.wayOut == Storage.WayOut.PLUG -> Phase.PLUG
                    Storage.cooldownDone(s, now) -> Phase.UNLOCKED
                    Storage.coolingDown(s) -> Phase.COOLING
                    else -> Phase.COOLDOWN
                }
            }
            // Ended while this page was open (Turn off, or safe mode or adb).
            wasOn -> Phase.ENDED
            else -> Phase.CHOOSE
        }
        if (next != phase) {
            phase = next
            build(next, s)
        }
        update()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    private fun build(p: Phase, s: Storage.Strict) {
        update = {}
        val page = when (p) {
            Phase.CHOOSE -> choosePage()
            Phase.PLUG -> plugPage()
            Phase.COOLDOWN, Phase.COOLING -> cooldownPage(p == Phase.COOLING, s)
            Phase.UNLOCKED -> unlockedPage()
            Phase.PASS -> passPage()
            Phase.ENDED -> endedPage()
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val side = Ui.dp(context, 20)
        setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 24))
        addView(Ui.iconButton(context, R.drawable.ic_back, "Back") { finish() }, Ui.lp(Ui.dp(context, 44), Ui.dp(context, 44)))
    }

    private fun filler(page: LinearLayout) = page.addView(View(this), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

    // ------------------------------------------------------------- choosing

    private fun choosePage() = column().apply {
        addView(Ui.eyebrow(context, "Strict mode"), Ui.lp().apply { topMargin = Ui.dp(context, 20) })
        addView(Ui.display(context, "Choose your way out.", 34f), Ui.lp().apply { topMargin = Ui.dp(context, 8) })
        addView(Ui.body(context, "Strict mode locks AppBlock in: no uninstalling, no force stopping, no " +
            "switching off its permissions, and limits only get stricter. The way out you pick is the only one.", 15f)
            .apply { setTextColor(getColor(R.color.muted)) }, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
        addView(wayCard(R.drawable.ic_bolt, R.color.accent_soft, R.color.accent, "Plug in to unlock",
            "The Turn off button stays greyed out until your phone is plugged in.", Storage.WayOut.PLUG),
            Ui.lp().apply { topMargin = Ui.dp(context, 24) })
        addView(wayCard(R.drawable.ic_timer, R.color.warn_soft, R.color.warn, "Cooldown",
            "Tap stop, wait out a timer you set, then turn it off.", Storage.WayOut.COOLDOWN),
            Ui.lp().apply { topMargin = Ui.dp(context, 14) })
        addView(Ui.muted(context, "Both come with optional emergency passes.", 13f).apply {
            gravity = Gravity.CENTER
        }, Ui.lp().apply { topMargin = Ui.dp(context, 18) })
        Ui.enter(this)
    }

    private fun wayCard(icon: Int, soft: Int, color: Int, title: String, text: String, way: Storage.WayOut) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(context, R.color.surface, 24f, R.color.line)
            val p = Ui.dp(context, 22)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = title
            addView(FrameLayout(context).apply {
                background = Ui.shape(context, soft, 0f, oval = true)
                addView(Ui.icon(context, icon, 28, color).apply {
                    layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56)))
            addView(Ui.display(context, title, 28f), Ui.lp().apply { topMargin = Ui.dp(context, 28) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(Ui.muted(context, text, 15f), Ui.lp(0).apply { weight = 1f; marginEnd = Ui.dp(context, 12) })
                addView(FrameLayout(context).apply {
                    background = Ui.shape(context, R.color.ink, 0f, oval = true)
                    addView(Ui.icon(context, R.drawable.ic_chevron, 18, R.color.on_ink).apply {
                        layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18), Gravity.CENTER)
                    })
                }, LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)))
            }, Ui.lp().apply { topMargin = Ui.dp(context, 8) })
            setOnClickListener {
                startActivityForResult(Intent(context, StrictSetupActivity::class.java)
                    .putExtra(StrictSetupActivity.EXTRA_WAY, way.name), SETUP)
            }
            Ui.press(this, 0.98f)
        }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Strict mode went on: back to the home screen, its pill now locked.
        if (requestCode == SETUP && resultCode == RESULT_OK) finish()
    }

    // --------------------------------------------------------------- on: plug

    private fun plugPage() = column().apply {
        val circle = FrameLayout(context)
        val bolt = Ui.icon(context, R.drawable.ic_bolt, 48, R.color.muted).apply {
            layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48), Gravity.CENTER)
        }
        circle.addView(bolt)
        val chip = Ui.txt(context, "", 14f, medium = true)
        val title = Ui.display(context, "", 32f).apply { gravity = Gravity.CENTER }
        val body = Ui.muted(context, "", 16f).apply { gravity = Gravity.CENTER }
        val turnOff = Ui.secondaryButton(context, "Turn off strict mode") {
            if (Strict.turnOff(this@StrictActivity)) tick.run()
        }
        addView(centered(circle, 120, chip, title, body), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        passButton()?.let { addView(it, Ui.lp().apply { bottomMargin = Ui.dp(context, 10) }) }
        addView(turnOff, Ui.lp())
        update = {
            val plugged = Strict.pluggedIn(this@StrictActivity)
            circle.background = Ui.shape(context, if (plugged) R.color.accent_soft else R.color.surface_alt, 0f,
                if (plugged) R.color.accent else R.color.line, oval = true)
            bolt.imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (plugged) R.color.accent else R.color.muted))
            setChip(chip, if (plugged) "Plugged in" else "Not plugged in", if (plugged) R.color.accent_soft else R.color.surface_alt,
                if (plugged) R.color.accent else R.color.muted)
            title.text = if (plugged) "Plugged in. Turn off is unlocked." else "Plug in to unlock Turn off."
            body.text = if (plugged) "Tap Turn off strict mode below. Unplug first and it greys out again."
                else "Strict mode is on. The Turn off button below lights up once your phone is plugged in."
            turnOff.isEnabled = plugged
        }
    }

    // ----------------------------------------------------------- on: cooldown

    private fun cooldownPage(counting: Boolean, s: Storage.Strict) = column().apply {
        val total = s.cooldownMin * 60_000L
        val ring = Ring(context)
        val clock = Ui.display(context, "", 34f).apply { gravity = Gravity.CENTER }
        val label = Ui.eyebrow(context, if (counting) "left" else "cooldown").apply { gravity = Gravity.CENTER }
        val face = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(clock, Ui.lp())
            addView(label, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
        }
        ring.addView(face, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val chip = Ui.txt(context, "", 14f, medium = true)
        if (counting) setChip(chip, "Cooling down", R.color.warn_soft, R.color.warn)
        else setChip(chip, "Strict mode on", R.color.ink, R.color.on_ink)
        val title = Ui.display(context, if (counting) "Hang tight. Still locked." else "Want out? You'll have to wait.", 32f)
            .apply { gravity = Gravity.CENTER }
        val body = Ui.muted(context, if (counting) "Most urges fade before the timer does. Leave this screen and it keeps counting."
            else "Tap Stop, and after a ${Storage.fmtSpan(s.cooldownMin)} cooldown you can turn strict mode off. " +
                "Everything stays locked until then.", 16f).apply { gravity = Gravity.CENTER }
        addView(centered(ring, 168, chip, title, body), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        if (counting) {
            addView(Ui.primaryButton(context, "Never mind, keep it on") {
                Strict.cancelCooldown(this@StrictActivity); tick.run()
            }, Ui.lp())
        } else {
            passButton()?.let { addView(it, Ui.lp().apply { bottomMargin = Ui.dp(context, 10) }) }
            addView(Ui.secondaryButton(context, "Stop strict mode") {
                Strict.startCooldown(this@StrictActivity); tick.run()
            }, Ui.lp())
        }
        update = {
            val left = if (counting) Storage.cooldownLeftMs(Storage.loadStrict(this@StrictActivity), System.currentTimeMillis())
                else total
            clock.text = Storage.fmtClock(left)
            ring.set(if (counting) 1f - left.toFloat() / total else 0f, R.color.warn)
        }
    }

    // ------------------------------------------------ on: cooldown run out

    /** The cooldown ran out: Turn off works, but strict mode stays on until it's tapped. */
    private fun unlockedPage() = column().apply {
        val ring = Ring(context).apply { set(1f, R.color.accent) }
        ring.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(Ui.display(context, Storage.fmtClock(0), 34f).apply { gravity = Gravity.CENTER }, Ui.lp())
            addView(Ui.eyebrow(context, "left").apply { gravity = Gravity.CENTER }, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val chip = Ui.txt(context, "", 14f, medium = true)
        setChip(chip, "Cooldown over", R.color.accent_soft, R.color.accent)
        addView(centered(ring, 168, chip,
            Ui.display(context, "Cooldown over. Turn off is unlocked.", 32f).apply { gravity = Gravity.CENTER },
            Ui.muted(context, "Strict mode stays on until you turn it off. Changed your mind? Keep it on.", 16f)
                .apply { gravity = Gravity.CENTER }),
            LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        addView(Ui.primaryButton(context, "Keep strict mode on") {
            Strict.cancelCooldown(this@StrictActivity); tick.run()
        }, Ui.lp().apply { bottomMargin = Ui.dp(context, 10) })
        addView(Ui.secondaryButton(context, "Turn off strict mode") {
            if (Strict.turnOff(this@StrictActivity)) tick.run()
        }, Ui.lp())
    }

    // ------------------------------------------------------------- the pass

    /** "Use emergency pass" and the time saved, when strict mode has passes; dimmed with none left. */
    private fun passButton(): View? {
        val s = Storage.loadStrict(this)
        if (s.pass == null) return null
        val saved = Storage.passSavedMs(s, System.currentTimeMillis())
        return Ui.Pill(this, "Use emergency pass", Ui.Look.PRIMARY, R.drawable.ic_hourglass) {
            Strict.startPass(this); tick.run()
        }.apply {
            label.text = if (saved > 0) "Use emergency pass · ${Storage.fmtClock(saved)} left" else "Emergency pass used up"
            isEnabled = saved > 0
        }
    }

    private fun passPage() = column().apply {
        val ring = Ring(context)
        val clock = Ui.display(context, "", 34f).apply { gravity = Gravity.CENTER }
        ring.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(clock, Ui.lp())
            addView(Ui.eyebrow(context, "left").apply { gravity = Gravity.CENTER }, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val chip = Ui.txt(context, "", 14f, medium = true)
        val title = Ui.display(context, "Your blocked apps are open.", 32f).apply { gravity = Gravity.CENTER }
        val body = Ui.muted(context, "The pass only counts down while you're in a blocked app, so nothing's " +
            "used up here. Done? Stop it and keep the rest. Strict mode stays on either way.", 16f)
            .apply { gravity = Gravity.CENTER }
        addView(centered(ring, 168, chip, title, body), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        val stop = Ui.primaryButton(context, "Stop pass, save the rest") {
            Strict.stopPass(this@StrictActivity); tick.run()
        }
        addView(stop, Ui.lp())
        update = {
            val s = Storage.loadStrict(this@StrictActivity)
            val left = Storage.passLeftMs(s, System.currentTimeMillis())
            val total = maxOf(1L, (s.pass?.allowMin ?: 0) * 60_000L)
            clock.text = Storage.fmtClock(left)
            ring.set(left.toFloat() / total, R.color.accent)
            // Counting only in split screen or a pop-up, with a blocked app beside this page.
            if (Storage.passCounting(s)) setChip(chip, "Pass counting down", R.color.accent_soft, R.color.accent)
            else setChip(chip, "Emergency pass on", R.color.surface_alt, R.color.ink)
        }
    }

    // ---------------------------------------------------------------- ended

    private fun endedPage() = column().apply {
        val ring = Ring(context).apply { set(1f, R.color.accent) }
        ring.addView(FrameLayout(context).apply {
            addView(Ui.icon(context, R.drawable.ic_lock_open, 44, R.color.accent).apply {
                layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44), Gravity.CENTER)
            })
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val chip = Ui.txt(context, "", 14f, medium = true)
        setChip(chip, "Strict mode off", R.color.accent_soft, R.color.accent)
        addView(centered(ring, 168, chip,
            Ui.display(context, "Strict mode is off.", 32f).apply { gravity = Gravity.CENTER },
            Ui.muted(context, "AppBlock can be uninstalled and its limits changed again.", 16f).apply { gravity = Gravity.CENTER }),
            LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        addView(Ui.primaryButton(context, "Back to home") { finish() }, Ui.lp())
    }

    // ---------------------------------------------------------------- parts

    /** The big circle, a chip, a title and a line, centered in the page. */
    private fun centered(top: View, sizeDp: Int, chip: View, title: View, body: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(Ui.dp(context, 4), Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 24))
        addView(top, LinearLayout.LayoutParams(Ui.dp(context, sizeDp), Ui.dp(context, sizeDp)))
        addView(chip, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 20) })
        addView(title, Ui.lp().apply { topMargin = Ui.dp(context, 16) })
        addView(body, Ui.lp().apply { topMargin = Ui.dp(context, 12) })
    }

    private fun setChip(chip: TextView, text: String, bg: Int, fg: Int) {
        chip.text = text
        chip.setTextColor(getColor(fg))
        chip.background = Ui.shape(this, bg, 99f)
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5))
    }

    /** A countdown ring: the track, and an arc of `fraction` from 12 o'clock, clockwise. */
    private class Ring(context: Context) : FrameLayout(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = context.getColor(R.color.line)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()
        private var fraction = 0f

        init { setWillNotDraw(false) }

        fun set(f: Float, colorRes: Int) {
            fraction = f.coerceIn(0f, 1f)
            arc.color = context.getColor(colorRes)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = Ui.dpf(context, 8f)
            track.strokeWidth = stroke
            arc.strokeWidth = stroke
            val inset = stroke / 2f
            box.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(box, 0f, 360f, false, track)
            if (fraction > 0f) canvas.drawArc(box, -90f, fraction * 360f, false, arc)
        }
    }

    companion object {
        private const val SETUP = 1
    }
}
