package xyz.robinroy.appblock.fixture;

import android.app.Activity;
import android.os.Bundle;

/** Like YouTube's Shorts: the player fills the window. Back returns home. */
public class FeedActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(Views.player(this));
    }
}
