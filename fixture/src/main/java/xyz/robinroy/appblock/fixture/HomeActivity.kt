package xyz.robinroy.appblock.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Like YouTube's home tab and Instagram's Explore: a small player in a
 * shelf and a small reel tile (thumbnails, not the feed), and a way into
 * each feed.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Fixture home"
            textSize = 24f
        })
        root.addView(Views.player(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Open feed"
            setOnClickListener { startActivity(Intent(this@HomeActivity, FeedActivity::class.java)) }
        })
        // An Explore tile: a reel's preview, with the Reels player's id, a fifth of the screen tall.
        root.addView(Views.player(this, R.id.clips_video_container), LinearLayout.LayoutParams(0, 0, 1f).apply {
            width = resources.displayMetrics.widthPixels / 3
        })
        root.addView(Button(this).apply {
            text = "Open reels"
            setOnClickListener { startActivity(Intent(this@HomeActivity, ReelsActivity::class.java)) }
        })
        // The shelf and the tile take a fifth of the screen each: weight 1 each against 3 of empty space.
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f))
        setContentView(root)
    }
}
