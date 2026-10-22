package xyz.robinroy.appblock.fixture

import android.app.Activity
import android.os.Bundle

/**
 * Like Instagram's Reels: the viewer fills the window, however it was
 * opened (the "Open reels" button, or a reel link). Back returns.
 */
class ReelsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Views.reels(this))
    }
}
