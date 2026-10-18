package xyz.robinroy.appblock.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Like YouTube's home tab: a small player in a shelf (not the feed), and a way into the feed. */
public class HomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        TextView title = new TextView(this);
        title.setText("Fixture home");
        title.setTextSize(24);
        root.addView(title);
        root.addView(Views.player(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        Button open = new Button(this);
        open.setText("Open feed");
        open.setOnClickListener(v -> startActivity(new Intent(this, FeedActivity.class)));
        root.addView(open);
        // The shelf takes a quarter of the screen: weight 1 against 3 of empty space.
        root.addView(new android.view.View(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3));
        setContentView(root);
    }
}
