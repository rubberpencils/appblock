package xyz.robinroy.appblock.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Like YouTube's home tab: a small player in a shelf (not the feed), and a way into the feed. */
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
        // The shelf takes a quarter of the screen: weight 1 against 3 of empty space.
        root.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f))
        setContentView(root)
    }
}
