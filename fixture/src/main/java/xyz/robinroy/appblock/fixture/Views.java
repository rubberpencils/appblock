package xyz.robinroy.appblock.fixture;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

final class Views {
    private Views() {}

    /** A "player": the id the rules look for, and in the accessibility tree like YouTube's. */
    static View player(Context context) {
        View v = new View(context);
        v.setId(R.id.reel_watch_player);
        v.setBackgroundColor(Color.DKGRAY);
        v.setContentDescription("player");
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return v;
    }
}
