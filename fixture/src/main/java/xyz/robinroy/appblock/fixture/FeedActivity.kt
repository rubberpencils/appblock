package xyz.robinroy.appblock.fixture

import android.app.Activity
import android.os.Bundle

/** Like YouTube's Shorts: the player fills the window. Back returns home. */
class FeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Views.player(this))
    }
}
