package xyz.robinroy.appblock

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The amber "something could be better" card, shared by the home screen's
 * notification reminder and the picker's usage-access hint: tap the body to
 * go fix the setting.
 *
 * Dismissal is opt-in: pass the confirm strings and onHideForever to get a
 * close button that hides the card for good, behind an "are you sure" sheet
 * whose body should mention where the setting can still be fixed later.
 * Leave them out for a card that can't be dismissed.
 */
object ReminderCard {

    fun make(
        activity: Activity,
        message: String,
        onFix: () -> Unit,
        confirmTitle: String? = null,
        confirmBody: String? = null,
        onHideForever: (() -> Unit)? = null,
        icon: Int = R.drawable.ic_bell,
    ): LinearLayout {
        lateinit var card: LinearLayout
        card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(activity, 14), Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4))
            background = Ui.shape(activity, R.color.warn_soft, 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onFix() }
            Ui.press(this, 0.98f)
            addView(Ui.icon(activity, icon, 18, R.color.warn))
            addView(Ui.txt(activity, message, 14f, R.color.warn, lineSpacing = 1.35f).apply {
                setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10))
            }, Ui.lp(0).apply { weight = 1f })
            if (onHideForever != null) addView(
                Ui.iconButton(activity, R.drawable.ic_close, "Hide this hint", 40, filled = false) {
                    Sheet.confirm(
                        activity, confirmTitle ?: "Hide this hint?", confirmBody ?: "",
                        yes = "Yes, hide it", no = "No"
                    ) {
                        onHideForever()
                        card.visibility = View.GONE
                    }
                })
        }
        return card
    }
}
