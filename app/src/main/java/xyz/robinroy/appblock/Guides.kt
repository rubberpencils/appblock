package xyz.robinroy.appblock

import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The two reading pages behind the About page: how the app works, and why it
 * exists. Each is a page of the one InfoSheet frame, and each is flat text on
 * the sheet itself: no cards, no nested boxes.
 */
object Guides {

    private data class Point(val icon: Int, val heading: String, val copy: String)

    private val POINTS = listOf(
        Point(R.drawable.ic_clock, "A few minutes, on a loop",
            "Each app gets a short allowance inside a longer window. New blocks start at " +
                "5 minutes in any 2 hours. As that window moves forward, used time drops " +
                "out and the minutes come back. There is no midnight reset to spend in " +
                "one sitting."),
        Point(R.drawable.ic_wall, "A wall when the time is gone",
            "Opening the app, tapping its notification, or staying in it past the limit " +
                "covers it with a wall and a button home. In split screen or a pop-up " +
                "window, the wall covers just that app, so the rest of the screen still " +
                "works; a pop-up is closed from Recents. AppBlock sees an app the moment it opens, and checks again every " +
                "few seconds while it stays on screen."),
        Point(R.drawable.ic_bell, "A countdown before the wall",
            "With notifications on, each limited app on screen gets a countdown of its " +
                "time left, which turns into a warning at halfway and again near the end."),
        Point(R.drawable.ic_shield, "What it leaves alone",
            "Notifications still arrive, and replies from the notification still work. " +
                "Messages, downloads, and audio keep running. Only time on screen counts. " +
                "Nothing about you is sent off the phone: no account, no analytics. The " +
                "one download is the rules for spotting YouTube's Shorts and " +
                "Instagram's Reels.")
    )

    /** The page itself, for pushing onto an open sheet. */
    fun howItWorks(sheet: InfoSheet): View = sheet.page(
        "How it works", "You choose the rule while you're calm. The phone keeps it later."
    ) {
        POINTS.forEachIndexed { i, p ->
            // The loop is easier watched than read: the first point shows it.
            val extra = if (i == 0) RollingWindow(context).apply { play() } else null
            addView(point(context, p, last = i == POINTS.lastIndex, extra), Ui.lp().apply {
                topMargin = Ui.dp(context, if (i == 0) 12 else 0)
            })
        }
    }

    /** Open the sheet straight on "How it works" (the empty state's link). */
    fun openHowItWorks(activity: Activity) = InfoSheet(activity).let { it.show(howItWorks(it)) }
    /**
     * An icon, a heading and a paragraph, then `extra` (an illustration) if
     * given, divided from the next by a hairline.
     */
    private fun point(context: Context, p: Point, last: Boolean, extra: View? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 18))
            addView(Ui.icon(context, p.icon, 20, R.color.muted).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = Ui.dp(context, 1); marginEnd = Ui.dp(context, 16)
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(context, p.heading, 16f))
                addView(Ui.muted(context, p.copy, 15f).apply { setLineSpacing(0f, 1.45f) },
                    Ui.lp().apply { topMargin = Ui.dp(context, 6) })
            }, Ui.lp(0).apply { weight = 1f })
        })
        // 132 dp: the least that still fits a stretch lifting with its "+N min" over it.
        if (extra != null) addView(extra, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(context, 132)).apply {
            bottomMargin = Ui.dp(context, 8)
        })
        if (!last) addView(View(context).apply { setBackgroundColor(context.getColor(R.color.line)) },
            LinearLayout.LayoutParams(Ui.MATCH, 1))
    }

    fun philosophy(sheet: InfoSheet): View = sheet.page("Tie yourself to the mast.") {
        val painting = ImageView(context).apply {
            setImageResource(R.drawable.ulysses_sirens)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Ulysses tied to the mast of his ship while winged " +
                "Sirens circle the rowing crew"
            val r = Ui.dpf(context, 22f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
            clipToOutline = true
        }
        addView(painting, Ui.lp(Ui.MATCH, Ui.dp(context, 190)).apply { topMargin = Ui.dp(context, 18) })
        addView(Ui.muted(context, "Ulysses and the Sirens, J. W. Waterhouse, 1891", 15f).apply {
            typeface = Ui.serif(context, italic = true)
        }, Ui.lp().apply { topMargin = Ui.dp(context, 8) })

        addView(Ui.body(context,
            "Odysseus had to sail past the Sirens, whose song lured every sailor to " +
                "shipwreck. Every captain before him knew the danger. Knowing was never " +
                "enough.\n\n" +
                "Odysseus wasn't stronger than the others. He was wiser: he knew that in " +
                "the moment, with the song in his ears, he'd be as weak as anyone. So he " +
                "had his crew tie him to the mast before the singing started.\n\n" +
                "Your phone sings too. And you already know knowing isn't enough. You've " +
                "promised yourself \"just five minutes\" before.\n\n" +
                "AppBlock is your mast. You decide the rules now, while you're " +
                "clear-headed: a few minutes per app, every few hours. Later, when the " +
                "pull comes, the decision is already made. Not zero access, no daily " +
                "quota to blow through by 5am. Just enough to check in, never enough to " +
                "drown.", 16f), Ui.lp().apply { topMargin = Ui.dp(context, 20) })

        addView(Ui.eyebrow(context, "Why it exists"), Ui.lp().apply { topMargin = Ui.dp(context, 28) })
        addView(Ui.muted(context,
            "One morning I woke up at 5am and scrolled Instagram, X and YouTube for an " +
                "hour straight. My entire daily quota, gone before sunrise. That's when " +
                "it clicked: daily limits fail because one binge empties them, and then " +
                "you override. AppBlock gives you a small allowance every time window " +
                "instead. Enough to check in, never enough to binge.", 15f).apply {
            setLineSpacing(0f, 1.45f)
        }, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
    }
}