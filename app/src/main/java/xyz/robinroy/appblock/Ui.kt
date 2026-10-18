package xyz.robinroy.appblock

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The design system in one place: type, color roles, pills, cards, and the
 * three motions the app uses (press, enter, pop). Screens stay plain views.
 *
 * Motion rules: transform and opacity only, ease-out only, nothing over
 * 320 ms, nothing on actions repeated all day, and all of it off when the
 * system animator scale is 0.
 */
object Ui {
    /** Strong ease-out: quick off the line, long soft landing. */
    val settle = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(context: Context, v: Float) = v * context.resources.displayMetrics.density

    /**
     * Lay the screen out behind the status and navigation bars, and pad its
     * content clear of them (and of a display cutout or the keyboard). Apps
     * targeting Android 15+ get the first half forced on them there; doing it
     * from Android 11 up keeps every version alike. The canvas-colored window
     * background shows through behind the bars.
     */
    fun edgeToEdge(window: Window) {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
            val clear = insets.getInsets(WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(clear.left, clear.top, clear.right, clear.bottom)
            WindowInsets.CONSUMED
        }
    }

    /**
     * A bottom sheet's window: reach down behind the navigation bar, and keep
     * `content` (the view with the sheet's background) clear of it by adding
     * to its bottom padding. Android 15+ ignores a navigation bar color, so
     * this is how the sheet still meets the screen edge.
     */
    fun sheetEdgeToEdge(window: Window, content: View) {
        if (Build.VERSION.SDK_INT < 30) return
        // A dialog's window stops above the bars by default. Only the keyboard
        // still pushes it up now; the navigation bar is padded for below.
        window.attributes = window.attributes.apply { fitInsetsTypes = WindowInsets.Type.ime() }
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val base = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + nav)
            insets
        }
    }

    fun motionOn(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    fun lp(w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h)

    fun LinearLayout.LayoutParams.margins(
        context: Context, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0
    ) = apply {
        marginStart = dp(context, start); topMargin = dp(context, top)
        marginEnd = dp(context, end); bottomMargin = dp(context, bottom)
    }

    // ---------------------------------------------------------------- shapes

    fun shape(
        context: Context, colorRes: Int, radiusDp: Float,
        strokeRes: Int? = null, oval: Boolean = false
    ) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dpf(context, radiusDp)
        setColor(context.getColor(colorRes))
        if (strokeRes != null) setStroke(dp(context, 1).coerceAtLeast(1), context.getColor(strokeRes))
    }

    // ---------------------------------------------------------------- motion

    /** Sink a touch on press-down, spring back on release. Feedback is instant. */
    fun press(view: View, to: Float = 0.97f) {
        view.setOnTouchListener { v, e ->
            if (motionOn(v.context)) when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(to).scaleY(to).setDuration(110L)
                        .setInterpolator(settle).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220L)
                        .setInterpolator(settle).start()
            }
            false
        }
    }

    /** Fade up into place. Stagger by index, short and capped so nothing waits. */
    fun enter(view: View, index: Int = 0) {
        if (!motionOn(view.context)) return
        view.alpha = 0f
        view.translationY = dpf(view.context, 12f)
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(index.coerceAtMost(7) * 40L)
            .setDuration(320L).setInterpolator(settle).start()
    }

    /** Small pop for state that just flipped (a tick landing, a number changing). */
    fun pop(view: View) {
        if (!motionOn(view.context)) return
        view.scaleX = 0.8f; view.scaleY = 0.8f
        view.animate().scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(settle).start()
    }

    // ------------------------------------------------------------------ type

    // Two families, both bundled (res/font, SIL Open Font License): Plus
    // Jakarta Sans for everything you read or tap, Instrument Serif for the
    // few moments that should feel editorial: titles, the big numbers.
    private val fontCache = mutableMapOf<Int, Typeface>()

    private fun font(context: Context, res: Int): Typeface =
        fontCache.getOrPut(res) { context.resources.getFont(res) }

    /** Plus Jakarta Sans: regular, or semibold for emphasis. */
    fun sans(context: Context, medium: Boolean = false): Typeface =
        font(context, if (medium) R.font.jakarta_semibold else R.font.jakarta_regular)

    /** Instrument Serif, for display type. */
    fun serif(context: Context, italic: Boolean = false): Typeface =
        font(context, if (italic) R.font.instrument_serif_italic else R.font.instrument_serif)

    /** Applies a font to part of a string (TypefaceSpan(Typeface) needs API 28; we support 26). */
    class FontSpan(private val face: Typeface) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = face }
        override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = face }
    }

    fun txt(
        context: Context, text: CharSequence, sizeSp: Float, colorRes: Int = R.color.ink,
        medium: Boolean = false, tracking: Float = 0f, lineSpacing: Float = 1.3f,
        editorial: Boolean = false
    ) = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(context.getColor(colorRes))
        typeface = if (editorial) serif(context) else sans(context, medium)
        letterSpacing = tracking
        setLineSpacing(0f, lineSpacing)
        includeFontPadding = false
    }

    /**
     * Big title, set in the serif. The serif runs small for its size, so
     * the sp value is lifted to match the sans' presence.
     */
    fun display(context: Context, text: CharSequence, sizeSp: Float = 32f) =
        txt(context, text, sizeSp * 1.14f, tracking = -0.01f, lineSpacing = 0.98f, editorial = true)

    fun title(context: Context, text: CharSequence, sizeSp: Float = 17f) =
        txt(context, text, sizeSp, medium = true, tracking = -0.01f)

    fun body(context: Context, text: CharSequence, sizeSp: Float = 16f) =
        txt(context, text, sizeSp, R.color.ink, lineSpacing = 1.45f)

    fun muted(context: Context, text: CharSequence, sizeSp: Float = 14f) =
        txt(context, text, sizeSp, R.color.muted, lineSpacing = 1.4f)

    fun eyebrow(context: Context, text: CharSequence) =
        txt(context, text.toString().uppercase(), 11f, R.color.muted, medium = true, tracking = 0.14f)

    // ----------------------------------------------------------------- icons

    fun icon(context: Context, res: Int, sizeDp: Int = 22, colorRes: Int = R.color.ink) =
        ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(context.getColor(colorRes))
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Round 44 dp icon button with a hairline: header actions, row actions. */
    fun iconButton(
        context: Context, res: Int, description: String, sizeDp: Int = 44,
        filled: Boolean = true, onClick: () -> Unit
    ) = FrameLayout(context).apply {
        background = if (filled) shape(context, R.color.surface, 0f, R.color.line, oval = true)
        else null
        contentDescription = description
        isClickable = true
        isFocusable = true
        addView(icon(context, res, 20).apply {
            layoutParams = FrameLayout.LayoutParams(dp(context, 20), dp(context, 20), Gravity.CENTER)
        })
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        setOnClickListener { onClick() }
        press(this, 0.92f)
    }

    // --------------------------------------------------------------- buttons

    enum class Look { PRIMARY, SECONDARY, GHOST, DANGER }

    /**
     * Pill button. The label is its own TextView so screens (and the e2e
     * tests) can find it by text, and enabling or disabling the pill fades
     * and disables the label with it. `trailing` nests an icon in a small
     * circle flush with the right padding.
     */
    class Pill(
        context: Context, label: String, look: Look = Look.PRIMARY,
        trailing: Int? = null, onClick: () -> Unit
    ) : LinearLayout(context) {
        val label: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val ink = when (look) {
                Look.PRIMARY -> R.color.on_ink
                Look.DANGER -> R.color.danger
                else -> R.color.ink
            }
            background = when (look) {
                Look.PRIMARY -> RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF),
                    shape(context, R.color.ink, 999f), null)
                Look.SECONDARY -> shape(context, R.color.surface, 999f, R.color.line)
                else -> null
            }
            val v = dp(context, 15)
            val startPad = dp(context, if (look == Look.GHOST || look == Look.DANGER) 14 else 24)
            val endPad = if (trailing != null) dp(context, 8) else startPad
            setPadding(startPad, if (trailing != null) dp(context, 8) else v, endPad,
                if (trailing != null) dp(context, 8) else v)
            this.label = txt(context, label, 16f, ink, medium = true, tracking = -0.01f).apply {
                maxLines = 1
            }
            addView(this.label)
            if (trailing != null) {
                addView(FrameLayout(context).apply {
                    background = shape(context, R.color.on_ink, 0f, oval = true).apply { alpha = 36 }
                    addView(icon(context, trailing, 18, ink).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            dp(context, 18), dp(context, 18), Gravity.CENTER)
                    })
                }, LayoutParams(dp(context, 36), dp(context, 36))
                    .apply { marginStart = dp(context, 14) })
            }
            setOnClickListener { onClick() }
            press(this, 0.98f)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            label.isEnabled = enabled
            animate().alpha(if (enabled) 1f else 0.4f).setDuration(120L).start()
        }
    }

    fun primaryButton(context: Context, label: String, trailing: Int? = null, onClick: () -> Unit) =
        Pill(context, label, Look.PRIMARY, trailing, onClick)

    fun secondaryButton(context: Context, label: String, onClick: () -> Unit) =
        Pill(context, label, Look.SECONDARY, null, onClick)

    fun ghostButton(context: Context, label: String, danger: Boolean = false, onClick: () -> Unit) =
        Pill(context, label, if (danger) Look.DANGER else Look.GHOST, null, onClick)


    /**
     * Grayed-out secondary action: small, muted, a chevron and nothing else.
     * For things worth having close but not worth competing with the page.
     */
    fun quietLink(context: Context, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(0, dp(context, 10), dp(context, 6), dp(context, 10))
        addView(txt(context, label, 14f, R.color.muted, medium = true))
        addView(icon(context, R.drawable.ic_chevron, 14, R.color.muted).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(context, 2)
        })
        setOnClickListener { onClick() }
        press(this, 0.97f)
    }

    // ---------------------------------------------------------------- layout

    /** Plain surface card with a hairline. */
    fun card(context: Context, radiusDp: Float = 22f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(context, R.color.surface, radiusDp, R.color.line)
    }

    /**
     * Double bezel: a thin tray around an inner plate, radii concentric.
     * The outer tray carries the hairline, the inner plate the content.
     */
    fun bezel(
        context: Context, inner: View, outerRadius: Float = 28f, innerColor: Int = R.color.surface
    ): LinearLayout {
        val gap = 5
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(context, R.color.surface_alt, outerRadius, R.color.line)
            setPadding(dp(context, gap), dp(context, gap), dp(context, gap), dp(context, gap))
            inner.background = shape(context, innerColor, outerRadius - gap)
            addView(inner, lp())
        }
    }

    /** "On" / "Off" chip. Green when on, red when off; text carries the meaning too. */
    fun statusChip(context: Context, on: Boolean) = TextView(context).apply { setChip(this, on) }

    fun setChip(view: TextView, on: Boolean) {
        val context = view.context
        view.text = if (on) "On" else "Off"
        view.textSize = 12f
        view.typeface = sans(context, medium = true)
        view.setTextColor(context.getColor(if (on) R.color.accent else R.color.danger))
        view.background = shape(context, if (on) R.color.accent_soft else R.color.danger_soft, 99f)
        view.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
    }

    /**
     * Icon in a soft circle, a title, an optional second line, and either a
     * custom trailing view or a chevron. The whole row is the touch target.
     */
    fun row(
        context: Context, iconRes: Int?, title: String, subtitle: String? = null,
        trailing: View? = null, onClick: (() -> Unit)? = null
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 10), 0, dp(context, 10))
        // No icon: a flat text row, for places that are already inside a container.
        if (iconRes != null) addView(FrameLayout(context).apply {
            background = shape(context, R.color.surface_alt, 0f, oval = true)
            addView(icon(context, iconRes, 20).apply {
                layoutParams = FrameLayout.LayoutParams(dp(context, 20), dp(context, 20), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(dp(context, 42), dp(context, 42)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(txt(context, title, 16f, medium = true, tracking = -0.01f))
            if (subtitle != null) addView(muted(context, subtitle, 13f).apply {
                setPadding(0, dp(context, 2), 0, 0)
            })
        }, lp(0).apply {
            weight = 1f
            marginStart = if (iconRes != null) dp(context, 14) else 0
            marginEnd = dp(context, 10)
        })
        if (trailing != null) addView(trailing)
        else if (onClick != null) addView(icon(context, R.drawable.ic_chevron, 18, R.color.muted))
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            press(this, 0.98f)
        }
    }

    /** Header row: optional back button, title, optional trailing action. */
    fun header(
        activity: Activity, heading: String, back: Boolean = true,
        trailingIcon: Int? = null, trailingDescription: String = "", onTrailing: () -> Unit = {}
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (back) addView(iconButton(activity, R.drawable.ic_back, "Back") { activity.finish() })
        addView(title(activity, heading, 20f).apply {
            if (back) setPadding(dp(activity, 14), 0, 0, 0)
        }, lp(0).apply { weight = 1f })
        if (trailingIcon != null) addView(iconButton(activity, trailingIcon, trailingDescription) {
            onTrailing()
        })
    }

    /** Rounded-square app icon: clips any launcher icon to one consistent shape. */
    fun appIcon(context: Context, drawable: android.graphics.drawable.Drawable, sizeDp: Int) =
        ImageView(context).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val r = dpf(context, sizeDp * 0.27f)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Usage level to a color: calm until close, amber near the end, red when spent. */
    fun levelColor(fraction: Float) = when {
        fraction >= 1f -> R.color.danger
        fraction >= 0.75f -> R.color.warn
        else -> R.color.accent
    }

    /**
     * Slim progress bar. The fill is full width and scaled from the left, so
     * growing it is a transform, not a layout pass.
     */
    fun progressBar(context: Context, fraction: Float, animate: Boolean): FrameLayout {
        val f = fraction.coerceIn(0f, 1f)
        return FrameLayout(context).apply {
            background = shape(context, R.color.surface_alt, 99f)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, 6))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
            val fill = View(context).apply {
                background = shape(context, levelColor(fraction), 99f)
                pivotX = 0f
            }
            addView(fill, FrameLayout.LayoutParams(MATCH, MATCH))
            // A sliver always shows for used time, so 1 min of 60 isn't invisible.
            val shown = if (f > 0f) f.coerceAtLeast(0.04f) else 0f
            fill.post { fill.pivotX = 0f }
            if (animate && motionOn(context)) {
                fill.scaleX = 0f
                fill.animate().scaleX(shown).setStartDelay(120L).setDuration(520L)
                    .setInterpolator(settle).start()
            } else fill.scaleX = shown
        }
    }
}
