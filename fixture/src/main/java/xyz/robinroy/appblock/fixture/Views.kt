package xyz.robinroy.appblock.fixture

import android.content.Context
import android.graphics.Color
import android.view.View

internal object Views {
    /** A "player": the id the rules look for, and in the accessibility tree like YouTube's. */
    fun player(context: Context): View = View(context).apply {
        id = R.id.reel_watch_player
        setBackgroundColor(Color.DKGRAY)
        contentDescription = "player"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
