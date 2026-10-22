package xyz.robinroy.appblock.fixture

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout

internal object Views {
    /** A "player": the id the rules look for, and in the accessibility tree like YouTube's. */
    fun player(context: Context, id: Int = R.id.reel_watch_player): View = View(context).apply {
        this.id = id
        setBackgroundColor(Color.DKGRAY)
        contentDescription = "player"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Like Instagram's Reels viewer: a pager holding the video, both with Instagram's ids. */
    fun reels(context: Context): View = FrameLayout(context).apply {
        id = R.id.clips_viewer_view_pager
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(player(context, R.id.clips_video_container))
    }
}
