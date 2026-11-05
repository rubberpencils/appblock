package xyz.robinroy.appblock

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One span picker: a label, minus and plus buttons around a number you can
 * type, and the units (min, hr, day) to tap. The limit sheet's "Allow" and
 * "In any" sides, strict mode's cooldown and emergency pass. `changed` runs
 * whenever the number or the unit changes.
 */
class SpanPicker(private val context: Context, label: String, private val units: List<Storage.SpanUnit>,
                 startMin: Int) {
    var unit = Storage.unitFor(startMin, units)
    val field = numberField(context).apply { setText((startMin / unit.minutes).toString()) }
    private val segments = mutableMapOf<Storage.SpanUnit, TextView>()
    var changed: () -> Unit = {}
    val amount get() = field.text.toString()

    /** The span in minutes, or null while the field isn't a number. */
    val minutes get() = Storage.parseAmount(amount)?.let { it * unit.minutes }

    private fun paintUnits() {
        for ((u, v) in segments) {
            val on = u == unit
            v.setTextColor(context.getColor(if (on) R.color.on_ink else R.color.muted))
            v.background = if (on) Ui.shape(context, R.color.ink, 11f) else null
        }
    }

    fun setMinutes(min: Int) {
        unit = Storage.unitFor(min, units)
        paintUnits()
        field.setText((min / unit.minutes).toString())
    }

    private fun step(up: Boolean) {
        val next = Storage.stepAmount(Storage.parseAmount(amount) ?: 1, unit, up)
        tick(context)
        field.setText(next.toString())
        field.setSelection(field.text.length)
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.eyebrow(context, label))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconButton(context, R.drawable.ic_minus, "$label: less", 40) { step(false) })
            addView(field, Ui.lp(Ui.dp(context, 64)).apply {
                marginStart = Ui.dp(context, 6); marginEnd = Ui.dp(context, 6)
            })
            addView(Ui.iconButton(context, R.drawable.ic_plus, "$label: more", 40) { step(true) })
            addView(View(context), Ui.lp(0, 0).apply { weight = 1f })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ui.shape(context, R.color.surface_alt, 14f)
                val p = Ui.dp(context, 3)
                setPadding(p, p, p, p)
                for (u in units) {
                    val seg = Ui.txt(context, u.label, 14f, R.color.muted, medium = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(Ui.dp(context, 11), Ui.dp(context, 8), Ui.dp(context, 11), Ui.dp(context, 8))
                        isClickable = true
                        setOnClickListener { unit = u; paintUnits(); changed() }
                    }
                    segments[u] = seg
                    addView(seg)
                }
            })
        }, Ui.lp().apply { topMargin = Ui.dp(context, 8) })
    }

    init {
        paintUnits()
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) = changed()
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
        })
    }

    companion object {
        /** Typed-number box: soft plate, big digits, one tap selects all. */
        private fun numberField(context: Context) = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Ui.sans(context, medium = true)
            setTextColor(context.getColor(R.color.ink))
            setSelectAllOnFocus(true)
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(5))
            background = Ui.shape(context, R.color.surface_alt, 14f)
            val h = Ui.dp(context, 6)
            val v = Ui.dp(context, 9)
            setPadding(h, v, h, v)
        }

        /**
         * One short click of the vibration motor per stepper tap: a plain 12 ms
         * pulse, tagged as a media vibration. Anything Android or OxygenOS files
         * as touch feedback (performHapticFeedback, the predefined TICK, even an
         * untagged short pulse) is silent while the phone's "touch feedback"
         * setting is off, and the tick is the point.
         */
        fun tick(context: Context) {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            val pulse = VibrationEffect.createOneShot(12,
                if (vibrator.hasAmplitudeControl()) 180 else VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                vibrator.vibrate(pulse, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            else vibrator.vibrate(pulse)
        }
    }
}
