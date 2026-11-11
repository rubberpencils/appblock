package xyz.robinroy.appblock

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * Opened from the "Help & feedback" launcher shortcut (long-press the app
 * icon): the info sheet's form (FeedbackPages), ready as a support
 * request, since that is what brings someone here in a hurry. Closing the
 * sheet closes this.
 */
class SupportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        setContentView(View(this).apply { setBackgroundColor(getColor(R.color.canvas)) })
        val sheet = InfoSheet(this)
        sheet.setOnDismissListener { finish() }
        sheet.show(FeedbackPages.form(this, sheet, Storage.FeedbackKind.SUPPORT))
    }

    companion object {
        const val EMAIL = "support@trymoly.com"
    }
}
