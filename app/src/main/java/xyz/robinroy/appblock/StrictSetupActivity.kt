package xyz.robinroy.appblock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Setting up strict mode with the way out chosen on StrictActivity: how it
 * works, the cooldown's length (for a cooldown), the optional emergency
 * passes, then "Turn on strict mode". That asks the user to make Moly a
 * device admin, which is when strict mode goes on (Strict.adminEnabled).
 */
class StrictSetupActivity : Activity() {

    private lateinit var way: Storage.WayOut

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        way = Storage.WayOut.valueOf(intent.getStringExtra(EXTRA_WAY) ?: Storage.WayOut.COOLDOWN.name)
        val last = Storage.loadStrict(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = Ui.dp(context, 20)
            setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 24))
        }
        fun put(v: View, topDp: Int) = page.addView(v, ((v.layoutParams as? LinearLayout.LayoutParams) ?: Ui.lp())
            .apply { topMargin = Ui.dp(this@StrictSetupActivity, topDp) })
        page.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() },
            Ui.lp(Ui.dp(this, 44), Ui.dp(this, 44)))

        val plug = way == Storage.WayOut.PLUG
        put(FrameLayout(this).apply {
            background = Ui.shape(context, if (plug) R.color.accent_soft else R.color.warn_soft, 0f, oval = true)
            addView(Ui.icon(context, if (plug) R.drawable.ic_bolt else R.drawable.ic_timer, 28,
                if (plug) R.color.accent else R.color.warn).apply {
                layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56))
        }, 20)
        put(Ui.display(this, if (plug) "Plug in, then tap Turn off." else "Press stop, wait, it's over.", 34f), 16)
        val steps = if (plug) listOf(
            "While strict mode is on, the Turn off button is greyed out.",
            "Plug your phone in and the button lights up.",
            "Tap it to turn strict mode off. Unplug first and it greys out again.")
        else listOf(
            "You tap Stop strict mode.",
            "You wait out the cooldown. Still locked, and you can change your mind any time.",
            "It's over: strict mode turns off by itself.")
        steps.forEachIndexed { i, text -> put(step(i + 1, text), if (i == 0) 18 else 12) }

        // The cooldown: how long Stop takes.
        var cooldown: SpanPicker? = null
        val cooldownNote = Ui.muted(this, "", 14f)
        if (!plug) {
            put(hairline(), 24)
            val picker = SpanPicker(this, "Cooldown", Storage.SpanUnit.entries, last.cooldownMin)
            cooldown = picker
            put(picker.view, 20)
            put(chips(Storage.COOLDOWN_PRESETS.map { Storage.fmtSpan(it) to { picker.setMinutes(it) } },
                { Storage.COOLDOWN_PRESETS.indexOf(picker.minutes) }).also { c -> picker.changed = { c.paint(); paintCooldown(picker, cooldownNote) } }.view, 12)
            put(cooldownNote, 8)
            paintCooldown(picker, cooldownNote)
        }

        // Emergency passes.
        put(hairline(), 24)
        val passOn = Switch(this).apply { isChecked = last.pass != null; contentDescription = "Emergency passes" }
        put(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.display(context, "Emergency passes", 24f).apply {
                typeface = Ui.serif(context, italic = true)
            }, Ui.lp(0).apply { weight = 1f })
            addView(passOn)
            isClickable = true
            setOnClickListener { passOn.toggle() }
        }, 20)
        put(Ui.muted(this, "For real emergencies. A pass opens your blocked apps, and only counts down while you're in one. " +
            "It doesn't switch off strict mode, so Moly still can't be uninstalled or stopped. Used time " +
            "comes back as it rolls out of the window, like your app limits.", 14f), 8)
        val startPass = last.pass ?: Storage.PASS_PRESETS.first()
        val allow = SpanPicker(this, "Allow", listOf(Storage.SpanUnit.MIN, Storage.SpanUnit.HR), startPass.allowMin)
        val window = SpanPicker(this, "In any", Storage.SpanUnit.entries, startPass.windowMin)
        val summary = Ui.display(this, "", 26f)
        val note = Ui.muted(this, "", 13f)
        val passChips = chips(Storage.PASS_PRESETS.map { r ->
            "${Storage.fmtSpan(r.allowMin)} in ${Storage.fmtSpan(r.windowMin)}" to {
                allow.setMinutes(r.allowMin); window.setMinutes(r.windowMin)
            }
        }, { Storage.PASS_PRESETS.indexOf(passRule(allow, window)) })
        val passCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(context, R.color.surface, 20f, R.color.line)
            val p = Ui.dp(context, 16)
            setPadding(p, Ui.dp(context, 18), p, Ui.dp(context, 18))
            addView(summary)
            addView(note, Ui.lp().apply { topMargin = Ui.dp(context, 4) })
            addView(allow.view, Ui.lp().apply { topMargin = Ui.dp(context, 16) })
            addView(window.view, Ui.lp().apply { topMargin = Ui.dp(context, 16) })
            addView(passChips.view, Ui.lp().apply { topMargin = Ui.dp(context, 14) })
        }
        put(passCard, 14)

        val turnOn = Ui.primaryButton(this, "Turn on strict mode", R.drawable.ic_lock) {
            val cooldownMin = cooldown?.minutes ?: last.cooldownMin
            val pass = if (passOn.isChecked) passRule(allow, window) ?: return@primaryButton else null
            confirm(cooldownMin, pass)
        }
        fun paint() {
            val r = passRule(allow, window)
            if (r != null) {
                summary.text = "${Storage.fmtSpan(r.allowMin)} in any ${Storage.fmtSpan(r.windowMin)}"
                summary.alpha = 1f
                note.setTextColor(getColor(R.color.muted))
                note.text = "Use it in one go or in bits. It refills as old use rolls out."
            } else {
                summary.alpha = 0.3f
                val err = Storage.limitError(allow.amount, allow.unit, window.amount, window.unit)
                note.setTextColor(getColor(if (err != null) R.color.danger else R.color.muted))
                note.text = if (err != null) "A pass has to be shorter than its window." else "Enter how long, and over how long."
            }
            passChips.paint()
            passCard.visibility = if (passOn.isChecked) View.VISIBLE else View.GONE
            turnOn.isEnabled = (cooldown == null || cooldown.minutes != null) && (!passOn.isChecked || r != null)
        }
        allow.changed = ::paint
        window.changed = ::paint
        passOn.setOnCheckedChangeListener { _, _ -> paint() }
        cooldown?.let { c -> val before = c.changed; c.changed = { before(); paint() } }

        page.addView(View(this), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        put(turnOn, 28)
        paint()

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }

    private fun passRule(allow: SpanPicker, window: SpanPicker) =
        Storage.limitRule(allow.amount, allow.unit, window.amount, window.unit)

    private fun paintCooldown(picker: SpanPicker, note: TextView) {
        val min = picker.minutes
        note.text = if (min == null) "Enter how long Stop takes." else "Stop takes ${Storage.fmtSpan(min)}."
    }

    /**
     * Before the system's page: what being a device admin means here. Then
     * that page; strict mode goes on when the user taps Activate there.
     */
    private fun confirm(cooldownMin: Int, pass: Rule?) {
        Sheet.confirm(this, "One more step",
            "Let Moly stop uninstalls: Android asks you to make it a device admin. It gets no " +
                "other powers, and lets go as soon as strict mode ends.\n\n" +
                "While strict mode is on, Moly also looks for its own name on the screens of " +
                "Settings, the uninstall prompt and the permission pages, and covers the ones that " +
                "would switch it off. It reads nothing else there, and nothing leaves your phone.\n\n" +
                "On the next screen, tap Activate.",
            yes = "Open settings", no = "Not now") {
            Strict.choose(this, way, cooldownMin, pass)
            if (Strict.adminActive(this)) done()
            else startActivityForResult(Strict.activateIntent(this), ACTIVATE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ACTIVATE) return
        // The admin's "enabled" broadcast can come after this: don't wait for it.
        if (Strict.adminActive(this)) done() else Strict.unchoose(this)
    }

    private fun done() {
        Strict.adminEnabled(this)
        if (Storage.loadStrict(this).on) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun step(n: Int, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Ui.txt(context, n.toString(), 13f, R.color.on_ink, medium = true).apply {
            gravity = Gravity.CENTER
            background = Ui.shape(context, R.color.ink, 0f, oval = true)
        }, LinearLayout.LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28)))
        addView(Ui.body(context, text, 15f).apply { setPadding(0, Ui.dp(context, 4), 0, 0) },
            Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 14) })
    }

    private fun hairline() = View(this).apply {
        setBackgroundColor(getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, 1)
    }

    /** A row of one-tap chips; `lit` names the one the fields match (-1: none), repainted by `paint`. */
    private class Chips(val view: View, val paint: () -> Unit)

    private fun chips(items: List<Pair<String, () -> Unit>>, lit: () -> Int): Chips {
        val views = items.map { (label, pick) ->
            Ui.txt(this, label, 14f, medium = true).apply {
                setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 14), Ui.dp(context, 9))
                isClickable = true
                Ui.press(this, 0.95f)
                setOnClickListener { SpanPicker.tick(context); pick() }
            }
        }
        val paint = {
            val on = lit()
            views.forEachIndexed { i, chip ->
                chip.setTextColor(getColor(if (i == on) R.color.on_ink else R.color.ink))
                chip.background = if (i == on) Ui.shape(this, R.color.ink, 99f)
                    else Ui.shape(this, R.color.surface, 99f, R.color.line)
            }
        }
        paint()
        val row = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (v in views) addView(v, Ui.lp(Ui.WRAP).apply { marginEnd = Ui.dp(context, 8) })
            })
        }
        return Chips(row, paint)
    }

    companion object {
        const val EXTRA_WAY = "way"
        private const val ACTIVATE = 1
    }
}
