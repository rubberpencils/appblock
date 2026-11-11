package xyz.robinroy.appblock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Opened from the "Contact support" launcher shortcut (long-press the app
 * icon). A placeholder for now: a line and the support address, which
 * opens the email app.
 */
class SupportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)

        val title = Ui.display(this, "Contact support", 34f).apply { gravity = Gravity.CENTER }
        val note = Ui.muted(this, "Stuck, or something not working as it should? Write to us and " +
            "we'll get back to you.", 16f).apply { gravity = Gravity.CENTER }
        val email = Ui.primaryButton(this, EMAIL) {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")))
            } catch (e: Exception) {
                Sheet.info(this, "No email app", "Write to $EMAIL from any email app.")
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val side = Ui.dp(context, 28)
            setPadding(side, side, side, side)
            addView(title, Ui.lp())
            addView(note, Ui.lp().apply { topMargin = Ui.dp(context, 12); bottomMargin = Ui.dp(context, 32) })
            addView(email, Ui.lp(Ui.WRAP))
            Ui.enter(title, 0); Ui.enter(note, 1); Ui.enter(email, 2)
        })
    }

    companion object {
        const val EMAIL = "support@trymoly.com"
    }
}
