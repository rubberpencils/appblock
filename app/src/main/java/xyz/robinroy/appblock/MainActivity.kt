package xyz.robinroy.appblock

import android.app.Activity
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

/**
 * Home. One of four states, top to bottom:
 *   setup incomplete  -> a checklist that fixes each permission in a tap
 *   nothing blocked   -> three quiet gray lines and a nudge to pick an app
 *   blocking          -> the count, then one flat row per app
 *   setup off, rules  -> a note that blocking is paused, the rules kept
 * A floating "Block an app" pill is always within reach.
 */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private var firstRender = true

    // Lets onResume refresh the permissions sheet's On/Off chips while it's
    // open, so fixing a setting and coming back shows it without reopening.
    private var refreshPermRows: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(window)
        // Since when Moly has been installed: kept (and backed up) from the
        // first start, so it is there to go with feedback on a later phone.
        try { Storage.noteInstallTimes(this, Feedback.androidInstallTimes(this)) } catch (e: Exception) {}

        if (!Storage.onboardingSeen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        val side = Ui.dp(this, 20)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, Ui.dp(context, 12), side, Ui.dp(context, 132))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true   // lets the footer sit at the bottom on short pages
            addView(content)
        }

        // Floating action: fades into the page so rows scroll under it softly.
        val canvas = getColor(R.color.canvas)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, Ui.dp(context, 36), side, Ui.dp(context, 20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(canvas and 0x00FFFFFF, canvas, canvas))
            addView(Ui.primaryButton(context, "Block an app", R.drawable.ic_plus) { onAddApp() }.apply {
                label.typeface = Ui.serif(context, italic = true)
                label.textSize = 18f
            }, Ui.lp(Ui.WRAP))
        }
        setContentView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        })
    }

    /** A tap outside a chart's card puts the chart back (Readout). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        Readout.outside(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // A cooldown that ran out, or strict mode ended in safe mode, shows now.
        Strict.check(this)
        render()
        refreshPermRows?.invoke()
    }

    // ------------------------------------------------------------- rendering

    /** Rebuild the page from storage. Entrance motion plays on the first build only. */
    private fun render() {
        content.removeAllViews()
        val animate = firstRender
        firstRender = false
        var index = 0
        fun put(view: View, topDp: Int = 0) {
            content.addView(view, Ui.lp().apply { topMargin = Ui.dp(this@MainActivity, topDp) })
            if (animate) Ui.enter(view, index++)
        }

        val rules = Storage.loadRules(this)
        val ready = allRequirementsMet(this)
        put(topBar())

        if (!ready) put(setupCard(), 24)

        when (Storage.homeList(ready, rules.size)) {
            Storage.HomeList.PAUSED_NOTE -> put(
                Ui.muted(this,
                    "Blocking is paused because a required setting " +
                        "above is off. Your ${rules.size} blocked " +
                        (if (rules.size == 1) "app is" else "apps are") +
                        " saved and will reappear once it's back on.")
                    .apply { gravity = Gravity.CENTER }, 28)
            Storage.HomeList.EMPTY_HINT -> if (ready) {
                screenTimeCard(animate)?.let { put(it, 28) }
                if (!notificationsEnabled() && !Storage.notifReminderDismissed(this)) {
                    put(notifReminder(), 20)
                }
                // Takes the rest of the page and centers itself in it.
                content.addView(emptyState(animate), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            }
            Storage.HomeList.CARDS -> {
                val entries = rules.entries.sortedBy { labelFor(it.key).lowercase() }
                val today = todayUsageMs()
                val now = System.currentTimeMillis()
                screenTimeCard(animate)?.let { put(it, 28) }
                if (!notificationsEnabled() && !Storage.notifReminderDismissed(this)) {
                    put(notifReminder(), 12)
                }
                put(sectionTitle("Blocked apps", entries.size.toString()), 36)
                for (entry in entries) {
                    put(appRow(entry.key, entry.value, today?.let { it[entry.key] ?: 0L }, now, animate))
                }
            }
        }
    }

    private fun topBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(logoMark(22))
        addView(Ui.display(context, "Moly", 19f).apply {
            typeface = Ui.serif(context, italic = true)
            setPadding(Ui.dp(context, 10), 0, 0, 0)
        }, Ui.lp(0).apply { weight = 1f })
        addView(strictPill(), Ui.lp(Ui.WRAP).apply { marginEnd = Ui.dp(context, 8) })
        addView(aboutButton())
    }

    /**
     * "Strict mode" and a lock: light and open while it's off, ink with a
     * green, shut lock while it's on. Opens strict mode's screen either way.
     */
    private fun strictPill(): View {
        val on = Storage.loadStrict(this).on
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = if (on) "Strict mode is on" else "Strict mode is off"
            isClickable = true
            isFocusable = true
            background = Ui.shape(context, if (on) R.color.ink else R.color.surface, 99f,
                if (on) null else R.color.line)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 5), Ui.dp(context, 5), Ui.dp(context, 5))
            addView(Ui.txt(context, "Strict mode", 14f, if (on) R.color.on_ink else R.color.ink, medium = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(FrameLayout(context).apply {
                background = Ui.shape(context, if (on) R.color.accent else R.color.surface_alt, 0f, oval = true)
                addView(Ui.icon(context, if (on) R.drawable.ic_lock else R.drawable.ic_lock_open, 15,
                    if (on) R.color.on_ink else R.color.muted).apply {
                    layoutParams = FrameLayout.LayoutParams(Ui.dp(context, 15), Ui.dp(context, 15), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)).apply {
                marginStart = Ui.dp(context, 8)
            })
            setOnClickListener { onStrict() }
            Ui.press(this, 0.95f)
        }
    }

    private fun onStrict() {
        // Strict mode guards the blocker, so it needs the blocker working first.
        val missing = missingRequirements(this)
        if (missing.isNotEmpty() && !Storage.loadStrict(this).on) {
            Sheet.info(this, "Finish setting up first", Storage.requirementsMessage(missing))
            return
        }
        startActivity(Intent(this, StrictActivity::class.java))
    }

    /**
     * The About button: one hairline circle holding an italic serif "i", an
     * editorial mark instead of an icon-in-a-circle (two containers deep).
     */
    private fun aboutButton() = FrameLayout(this).apply {
        background = Ui.shape(context, R.color.surface, 0f, R.color.line, oval = true)
        contentDescription = "About"
        isClickable = true
        isFocusable = true
        addView(Ui.txt(context, "i", 26f, tracking = 0f, editorial = true).apply {
            typeface = Ui.serif(context, italic = true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER).apply {
            leftMargin = Ui.dp(context, 1)   // italic leans: nudge so it reads centered
            bottomMargin = Ui.dp(context, 1)
        })
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44))
        setOnClickListener { showAbout() }
        Ui.press(this, 0.92f)
    }

    /** The same ring glyph as before, with no plate behind it. */
    private fun logoMark(sizeDp: Int) = Ui.icon(this, R.drawable.ic_stat, sizeDp, R.color.ink)

    private fun sectionTitle(title: String, count: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.display(context, title, 22f).apply {
            typeface = Ui.serif(context, italic = true)
        }, Ui.lp(0).apply { weight = 1f })
        addView(Ui.muted(context, count, 15f))
        setPadding(0, 0, 0, Ui.dp(context, 14))
    }

    // ----------------------------------------------------------------- setup

    /** The required settings as a checklist: tap a row, fix it, come back to a tick. */
    private fun setupCard(): View {
        val reqs = Storage.Requirement.entries
        val done = reqs.count { requirementMet(this, it) }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(context, 20)
            setPadding(p, p, p, Ui.dp(context, 10))
            addView(Ui.display(context, "Finish setup", 24f))
            addView(Ui.muted(context, "$done of ${reqs.size} done. Blocking starts when " +
                (if (reqs.size == 2) "both are on." else "all ${reqs.size} are on."))
                .apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
            // Segmented progress: one segment per requirement.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                reqs.forEachIndexed { i, req ->
                    addView(View(context).apply {
                        background = Ui.shape(context,
                            if (requirementMet(context, req)) R.color.accent else R.color.surface_alt, 99f)
                    }, Ui.lp(0, Ui.dp(context, 5)).apply {
                        weight = 1f
                        if (i > 0) marginStart = Ui.dp(context, 6)
                    })
                }
            }, Ui.lp().apply { topMargin = Ui.dp(context, 16); bottomMargin = Ui.dp(context, 8) })
            for (req in reqs) addView(requirementRow(req))
        }
        return Ui.bezel(this, inner)
    }

    private fun requirementRow(req: Storage.Requirement): View {
        val met = requirementMet(this, req)
        val trailing: View = if (met) FrameLayout(this).apply {
            background = Ui.shape(context, R.color.accent, 0f, oval = true)
            addView(Ui.icon(context, R.drawable.ic_check, 16, R.color.on_ink).apply {
                layoutParams = FrameLayout.LayoutParams(
                    Ui.dp(context, 16), Ui.dp(context, 16), Gravity.CENTER)
            })
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28))
        } else Ui.txt(this, "Allow", 14f, R.color.on_ink, medium = true).apply {
            background = Ui.shape(context, R.color.ink, 99f)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 9), Ui.dp(context, 16), Ui.dp(context, 9))
        }
        return Ui.row(this, requirementIcon(req), req.title, req.permsNote.removePrefix("Required. "), trailing) {
            requirementFix(req)
        }
    }

    // ----------------------------------------------------------- status/empty

    private var screenTime: ScreenTimeView? = null

    /**
     * The top card: screen time with Usage access, a day, a week, a month or
     * a year of it (tap it for the Usage page, on the same one), a card
     * asking for it without, or nothing once that's dismissed (the ⓘ page
     * still has the switch).
     */
    private fun screenTimeCard(animate: Boolean): View? {
        return when (Storage.usageCard(usageAccessGranted(this), Storage.usageCardDismissed(this))) {
            Storage.UsageCard.STATS -> {
                // The same view across rebuilds of the page: it keeps its day (or week, month, year) and what it shows while it refreshes.
                val kept = screenTime
                val view = if (kept != null) {
                    (kept.parent as? android.view.ViewGroup)?.removeView(kept)
                    kept.load()
                    kept
                } else ScreenTimeView(this, null, animate).also { screenTime = it }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = Ui.dp(context, 20)
                    setPadding(p, p, p, Ui.dp(context, 8))
                    addView(view, Ui.lp())
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
                        addView(Ui.txt(context, "See apps", 14f, medium = true))
                        addView(Ui.icon(context, R.drawable.ic_chevron, 14, R.color.ink).apply {
                            (layoutParams as LinearLayout.LayoutParams).marginStart = Ui.dp(context, 6)
                        })
                    }, Ui.lp())
                }
                Ui.bezel(this, inner).apply {
                    contentDescription = "Screen time, see apps"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(Intent(context, UsageActivity::class.java)
                            .putExtra(UsageActivity.EXTRA_DAY, view.day.toString())
                            .putExtra(UsageActivity.EXTRA_PERIOD, view.period.name))
                    }
                }
            }
            Storage.UsageCard.ASK -> ReminderCard.make(
                this,
                message = "See where your time goes. Allow usage access for your daily screen time.",
                onFix = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                confirmTitle = "Hide screen time?",
                confirmBody = "You can turn it on any time: tap ⓘ at the top of the home screen, then Usage access.",
                onHideForever = { Storage.setUsageCardDismissed(this) },
                icon = R.drawable.ic_stat)
            Storage.UsageCard.NONE -> null
        }
    }

    /** Nothing here yet: three quiet gray lines, centered in the page. */
    private fun emptyState(animate: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(Ui.display(context, "No apps blocked yet.", 22f).apply {
            setTextColor(getColor(R.color.muted))
            gravity = Gravity.CENTER
        }, Ui.lp())
        addView(Ui.muted(context, "Pick an app and give it a small allowance.", 15f).apply {
            gravity = Gravity.CENTER
        }, Ui.lp().apply { topMargin = Ui.dp(context, 10) })
        addView(Ui.txt(context, "How it works", 14f, R.color.muted, medium = true).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
            isClickable = true
            setOnClickListener { Guides.openHowItWorks(this@MainActivity) }
            Ui.press(this, 0.97f)
        }, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(context, 6) })
        if (animate) Ui.enter(this)
    }
    private fun notifReminder() = ReminderCard.make(
        this,
        message = "Notifications are off, so Moly can't warn you " +
            "when you're close to using up an app's allowance. " +
            "Tap to turn them on.",
        confirmTitle = "Skip usage warnings?",
        confirmBody = "Without notifications, Moly can't let you know " +
            "when you're about to use up an app's allowance. The first " +
            "you'll hear of it is the block wall.\n\nHide this reminder " +
            "anyway? (You can still enable notifications later from the " +
            "About page.)",
        onFix = ::openNotificationSettings,
        onHideForever = { Storage.setNotifReminderDismissed(this) })

    // ------------------------------------------------------------------ list

    /**
     * One row per blocked app, divided by a hairline: icon, name, how long
     * until it's back, a slim bar, and the rule in words. The whole row
     * edits the limit.
     */
    private fun appRow(pkg: String, rule: Rule, todayMs: Long?, now: Long, animate: Boolean): View {
        val usedMs = Storage.usedMs(Storage.loadIntervals(this, pkg), rule.windowMin, now)
        val allowMs = rule.allowMin * 60_000f
        val fraction = if (allowMs <= 0f) 1f else usedMs / allowMs
        val isSpent = fraction >= 1f
        val name = labelFor(pkg)

        val status: View = if (isSpent) {
            val waitMin = Storage.ceilMin(Storage.msUntilUnblocked(this, pkg, rule))
            Ui.txt(this, "Blocked, back in ${Storage.fmtSpan(waitMin.toInt())}", 13f, R.color.danger,
                medium = true)
        } else if (todayMs != null) Ui.muted(this, Storage.fmtToday(todayMs), 13f)
        else Ui.muted(this, Storage.fmtLeft(usedMs, rule.allowMin), 13f)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "Set limit for $name"
            isClickable = true
            isFocusable = true
            setOnClickListener { showLimitDialog(pkg, rule) }
            Ui.press(this, 0.98f)

            addView(View(context).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams(Ui.MATCH, 1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
                addView(Ui.appIcon(context, iconFor(pkg), 34))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.title(context, name, 15f).apply { maxLines = 1 })
                    addView(status, Ui.lp().apply { topMargin = Ui.dp(context, 2) })
                    addView(Ui.progressBar(context, fraction, animate),
                        Ui.lp(Ui.MATCH, Ui.dp(context, 3)).apply { topMargin = Ui.dp(context, 7) })
                    addView(Ui.muted(context, Storage.phrases(
                        "${Storage.fmtSpan(rule.allowMin)} in any ${Storage.fmtSpan(rule.windowMin)}",
                        Storage.fmtUsed(usedMs, rule.allowMin)), 12f),
                        Ui.lp().apply { topMargin = Ui.dp(context, 5) })
                }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 12) })
                addView(Ui.icon(context, R.drawable.ic_chevron, 16, R.color.muted).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = Ui.dp(context, 10)
                })
            })
            // YouTube's Shorts, Instagram's Reels: blocked outright, on top of the limit.
            Storage.FEEDS[pkg]?.let { feed ->
                addView(feedSwitch(pkg, feed), Ui.lp().apply {
                    marginStart = Ui.dp(context, 46)
                    bottomMargin = Ui.dp(context, 6)
                })
            }
        }
    }

    /** "Block YouTube Shorts" and its switch, under the app's row; the whole line is the touch target. */
    private fun feedSwitch(pkg: String, feed: Storage.Feed): View {
        val on = pkg in Storage.loadFeedBlocks(this)
        // Strict mode: blocking a feed is stricter, unblocking it isn't.
        val locked = on && Storage.loadStrict(this).on
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = !locked
            isFocusable = !locked
            if (locked) alpha = 0.5f
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.txt(context, feed.label, 14f, medium = true))
                addView(Ui.muted(context, if (locked) "Locked by strict mode" else "Always, even with time left", 12f))
            }, Ui.lp(0).apply { weight = 1f })
            addView(Switch(context).apply {
                isChecked = on
                isClickable = false   // the line takes the tap
                isEnabled = !locked
            })
            if (!locked) setOnClickListener {
                if (on) { Storage.setFeedBlocked(this@MainActivity, pkg, false); render() }
                else feedDisclosure(pkg, feed)
            }
        }
    }

    /**
     * What blocking a feed means and what it looks at, before it's on. Play
     * requires this: it's the one time Moly looks inside another app,
     * and the one time it uses the network.
     */
    private fun feedDisclosure(pkg: String, feed: Storage.Feed) {
        val app = labelFor(pkg)
        val name = feed.name
        Sheet.confirm(this, "${feed.label}?",
            "Moly will put a wall over $app whenever $name is on screen, even " +
                "with time left, and take it down as soon as you go back. However " +
                "it opens (a tab, a link, a message), the wall goes up; thumbnails " +
                "stay.\n\n" +
                "To spot $name, it looks inside $app for the $name player, by its " +
                "name in $app's code. It reads no text and nothing about the " +
                "videos, and nothing leaves your phone.\n\n" +
                "$app changes often, so while $app is open Moly checks every " +
                "few hours for updated rules at ${Storage.RULES_HOST}. That " +
                "download sends nothing about you.",
            yes = "Block $name", no = "Not now") {
            Storage.setFeedBlocked(this, pkg, true)
            render()
        }
    }

    // ----------------------------------------------------------- add / about

    private fun onAddApp() {
        // Blocking only works with every required setting on; adding apps
        // before then would silently do nothing. One sheet names everything
        // still missing; the checklist on the home screen fixes them.
        val missing = missingRequirements(this)
        if (missing.isNotEmpty()) {
            Sheet.info(this, "Finish setting up first", Storage.requirementsMessage(missing))
            return
        }
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    /**
     * The "i": one fixed frame (InfoSheet). The first page is About with
     * every permission listed (tap one to fix it) and two serif buttons that
     * slide in How it works and Philosophy. Swipe down to close; there's no
     * Done button.
     */
    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "unknown" }
        val sheet = InfoSheet(this)
        val updates = mutableListOf<() -> Unit>()
        fun hairline() = View(this).apply { setBackgroundColor(getColor(R.color.line)) }
            .also { it.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, 1) }

        /** A permission row, small and indented under its heading. Tap to fix. */
        fun permission(icon: Int, title: String, why: String, enabled: () -> Boolean, fix: () -> Unit): View {
            val chip = TextView(this)
            fun paint() {
                Ui.setChip(chip, enabled())
                chip.textSize = 11f
                chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3))
            }
            paint()
            updates += ::paint
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(context, 14), Ui.dp(context, 9), 0, Ui.dp(context, 9))
                addView(Ui.icon(context, icon, 16, R.color.muted).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = Ui.dp(context, 12)
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.txt(context, title, 14f, medium = true, tracking = -0.01f))
                    addView(Ui.muted(context, why, 12.5f).apply { setLineSpacing(0f, 1.3f) },
                        Ui.lp().apply { topMargin = Ui.dp(context, 1) })
                }, Ui.lp(0).apply { weight = 1f; marginEnd = Ui.dp(context, 12) })
                addView(chip)
                isClickable = true
                isFocusable = true
                setOnClickListener { fix() }
                Ui.press(this, 0.98f)
            }
        }

        refreshPermRows = { updates.forEach { it() } }
        sheet.setOnDismissListener { refreshPermRows = null }

        /** Serif link into a reading page: type and a chevron, no container behind it. */
        fun reading(title: String, caption: String, page: () -> View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 14))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.display(context, title, 26f))
                addView(Ui.muted(context, caption, 13f), Ui.lp().apply { topMargin = Ui.dp(context, 5) })
            }, Ui.lp(0).apply { weight = 1f })
            addView(Ui.icon(context, R.drawable.ic_chevron, 18, R.color.muted))
            setOnClickListener { sheet.push(page()) }
            Ui.press(this, 0.98f)
        }
        val links = Ui.muted(this, "", 13f).apply {
            gravity = Gravity.CENTER
            text = Html.fromHtml(
                "<a href=\"https://github.com/rubberpencils/moly\">Open source</a> under MIT. " +
                    "Made by <a href=\"https://x.com/_RobinRoy\">robin</a>.",
                Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(getColor(R.color.ink))
        }

        val first = sheet.page {
            // Name and mark, side by side: small, so the page is for the content.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(RingView(context, 5f).apply { drawIn() },
                    LinearLayout.LayoutParams(Ui.dp(context, 60), Ui.dp(context, 60)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.display(context, "Moly", 30f))
                    addView(Ui.muted(context, "Version $version", 14f),
                        Ui.lp().apply { topMargin = Ui.dp(context, 4) })
                }, Ui.lp(0).apply { weight = 1f; marginStart = Ui.dp(context, 18) })
            }, Ui.lp().apply { topMargin = Ui.dp(context, 8) })

            addView(Ui.muted(context, "Set the rule while you're calm.", 19f).apply {
                typeface = Ui.serif(context, italic = true)
            }, Ui.lp().apply { topMargin = Ui.dp(context, 18) })

            // Every permission, in place: a small group under its heading.
            addView(Ui.eyebrow(context, "Permissions"), Ui.lp().apply { topMargin = Ui.dp(context, 28) })
            val group = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                for (req in Storage.Requirement.entries) {
                    addView(permission(requirementIcon(req), req.title, req.permsNote, { requirementMet(this@MainActivity, req) },
                        { requirementFix(req) }))
                }
                addView(permission(R.drawable.ic_clock, "Usage access",
                    "Optional. Shows your screen time, day by day, on the home screen and in the picker.",
                    { usageAccessGranted(this@MainActivity) },
                    { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }))
                addView(permission(R.drawable.ic_bell, "Notifications",
                    "Optional. Counts down your time and warns you at 50% and 90%.",
                    ::notificationsEnabled, ::openNotificationSettings))
            }
            addView(group, Ui.lp().apply { topMargin = Ui.dp(context, 6) })

            addView(View(context), LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(context, 16), 1f))

            // The reading: two serif links, set apart by hairlines.
            addView(hairline())
            addView(reading("Philosophy", "Why it exists: tie yourself to the mast") {
                Guides.philosophy(sheet)
            })
            addView(hairline())
            addView(reading("How it works", "Allowances, the wall, the warnings") {
                Guides.howItWorks(sheet)
            })
            addView(hairline())
            addView(View(context), LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(context, 20), 1f))
            // A word to the maker, or a call for help, at the foot: a form of its own (FeedbackPages).
            addView(Ui.secondaryButton(context, FeedbackPages.TITLE) {
                sheet.push(FeedbackPages.form(this@MainActivity, sheet))
            }, Ui.lp(Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(context, 16) })
            addView(links)
        }
        sheet.show(first)
    }
    // ----------------------------------------------------------- permissions

    private fun notificationsEnabled() =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    // Fix path per requirement (live state lives in Perms.kt). The `when` is
    // exhaustive, so adding a Storage.Requirement entry won't compile until
    // it's wired here, and everything else (checklist, sheet, About rows)
    // follows for free.
    private fun requirementFix(req: Storage.Requirement) {
        when (req) {
            Storage.Requirement.ACCESSIBILITY ->
                if (accessibilityOn(this)) openAccessibilitySettings() else accessibilityDisclosure()
            // The direct "allow?" dialog can only grant. Once granted, tapping
            // the row goes to the battery-optimization list instead, where the
            // exemption can be reviewed and undone (find Moly there).
            Storage.Requirement.BATTERY ->
                if (batteryExempt(this))
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else requestBatteryExemption()
        }
    }

    private fun requirementIcon(req: Storage.Requirement) = when (req) {
        Storage.Requirement.ACCESSIBILITY -> R.drawable.ic_layers
        Storage.Requirement.BATTERY -> R.drawable.ic_battery
    }

    /**
     * What the accessibility service sees and why, before the user is sent
     * to switch it on. Play requires this disclosure and an explicit yes
     * ahead of the system's own (scarier) "full control" dialog.
     */
    private fun accessibilityDisclosure() {
        Sheet.confirm(this, "Turn on Moly in Accessibility",
            "Moly uses Android's accessibility service to see which apps are on " +
                "screen, split screen and pop-up windows included, and to put the " +
                "block wall over an app whose time is up.\n\n" +
                "It only looks at which app each window belongs to (and, if you " +
                "block YouTube Shorts or Instagram Reels, for their player). It " +
                "never reads what's on screen or what you type, and nothing about " +
                "you leaves your phone. (In strict mode, if you turn it on, it also " +
                "looks for its own name on Settings' screens, to cover the ones that " +
                "would switch it off.)\n\n" +
                "On the next screen, find Moly (under Installed apps or Downloaded " +
                "apps) and turn it on. Android will warn that it can see and control " +
                "the screen: that's what every accessibility service is allowed, not " +
                "what Moly does.\n\n" +
                "Is the switch greyed out? Open Moly's App info, tap ⋮ at the top, " +
                "choose \"Allow restricted settings\", then try again.",
            yes = "Agree and continue", no = "Not now", onYes = ::openAccessibilitySettings)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** System dialog asking to exempt Moly from battery optimization. */
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(this)))
    }

    /** The app's own notification-settings page: works even after a permanent
     *  "don't allow" on the runtime prompt. */
    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    // ----------------------------------------------------------------- limit

    /**
     * "Allow X in any Y". Each side is a number you can type or nudge with
     * the minus and plus buttons, plus a unit (minutes, hours, days) you tap. Quick
     * picks fill both sides at once and light up whenever the fields match
     * one. One layout covers everything from "1 minute in any 15 minutes" to "6 hours
     * in any week", so there is no separate typing mode. OK (enabled only
     * for a valid rule) saves straight away; Remove lives here too.
     */
    private fun showLimitDialog(pkg: String, rule: Rule) {
        val name = labelFor(pkg)
        val sheet = Sheet(this).header("Set limit", name, Ui.appIcon(this, iconFor(pkg), 48))

        val summary = Ui.display(this, "", 26f)
        val note = Ui.muted(this, "", 14f)

        val allow = SpanPicker(this, "Allow", listOf(Storage.SpanUnit.MIN, Storage.SpanUnit.HR), rule.allowMin)
        val window = SpanPicker(this, "In any", Storage.SpanUnit.entries, rule.windowMin)
        // Strict mode: the limit can get stricter, never looser (Storage.loosens).
        val strict = Storage.loadStrict(this).on

        fun picked(): Rule? =
            Storage.limitRule(allow.amount, allow.unit, window.amount, window.unit)

        // Quick picks: tap to fill both sides; the matching one stays lit.
        val chips = Storage.PRESETS.associateWith { preset ->
            Ui.txt(this, "${Storage.fmtSpan(preset.allowMin)} in ${Storage.fmtSpan(preset.windowMin)}",
                14f, medium = true).apply {
                setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 14), Ui.dp(context, 9))
                isClickable = true
                Ui.press(this, 0.95f)
                setOnClickListener {
                    SpanPicker.tick(context)
                    allow.setMinutes(preset.allowMin)
                    window.setMinutes(preset.windowMin)
                }
            }
        }

        val ok = sheet.primary("OK") {
            val r = picked() ?: return@primary
            if (strict && Storage.loosens(rule, r)) return@primary
            val rules = Storage.loadRules(this).toMutableMap()
            rules[pkg] = r
            Storage.saveRules(this, rules)
            sheet.dismiss()
            render()
        }

        fun describe() {
            val r = picked()
            val looser = strict && r != null && Storage.loosens(rule, r)
            if (looser) {
                summary.alpha = 0.3f
                note.setTextColor(getColor(R.color.danger))
                note.text = "Strict mode is on: this limit can get stricter, not looser."
            } else if (r != null) {
                summary.text = "${Storage.fmtSpan(r.allowMin)} in any ${Storage.fmtSpan(r.windowMin)}"
                summary.alpha = 1f
                note.setTextColor(getColor(R.color.muted))
                note.text = "Time comes back as old use rolls out of the window, so there's " +
                    "no midnight reset."
            } else {
                // Keep the last good summary, dimmed, and say what's wrong.
                summary.alpha = 0.3f
                val err = Storage.limitError(allow.amount, allow.unit, window.amount, window.unit)
                note.setTextColor(getColor(if (err != null) R.color.danger else R.color.muted))
                note.text = err ?: "Enter how much time, and over how long."
            }
            ok.isEnabled = r != null && !looser
            for ((preset, chip) in chips) {
                val on = r == preset
                chip.setTextColor(getColor(if (on) R.color.on_ink else R.color.ink))
                chip.background = if (on) Ui.shape(this, R.color.ink, 99f)
                else Ui.shape(this, R.color.surface, 99f, R.color.line)
            }
        }
        allow.changed = ::describe
        window.changed = ::describe

        sheet.add(summary, 20)
        sheet.add(note, 6)
        sheet.add(allow.view, 24)
        sheet.add(window.view, 20)
        sheet.add(Ui.eyebrow(this, "Quick picks"), 24)
        sheet.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (chip in chips.values) addView(chip, Ui.lp(Ui.WRAP).apply {
                    marginEnd = Ui.dp(context, 8)
                })
            })
        }, 10)
        sheet.actions(
            sheet.secondary("Cancel"), ok,
            // Removing an app gives all its time back: not in strict mode.
            leading = Ui.ghostButton(this, "Remove", danger = true) {
                sheet.dismiss()
                removeApp(pkg)
            }.apply { isEnabled = !strict })
        sheet.setOnShowListener { describe() }
        sheet.show()
    }

    // Confirmation guards against accidental taps on Remove.
    private fun removeApp(pkg: String) {
        Sheet.confirm(this, "Remove ${labelFor(pkg)}?",
            "Are you sure you want to remove the limits for this app?",
            yes = "Yes", no = "No", icon = Ui.appIcon(this, iconFor(pkg), 48)) {
            Storage.removeApp(this, pkg)
            render()
        }
    }

    /** Screen time per package since midnight; null without (the optional) Usage access. */
    private fun todayUsageMs(): Map<String, Long>? {
        if (!usageAccessGranted(this)) return null
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return usm.queryAndAggregateUsageStats(midnight, System.currentTimeMillis())
            .mapValues { it.value.totalTimeInForeground }
    }

    private fun labelFor(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun iconFor(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) { getDrawable(android.R.drawable.sym_def_app_icon)!! }
}
