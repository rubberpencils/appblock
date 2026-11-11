package xyz.robinroy.appblock

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Help & feedback": the form at the foot of the info sheet and behind the
 * app icon's long-press menu (a switch at its top says which of the two a
 * message is), what it turns into once a message is sent or the day's ten
 * are used up, and the past messages.
 */
object FeedbackPages {

    const val TITLE = "Help & feedback"

    private fun box(host: Activity, hintText: String) = EditText(host).apply {
        hint = hintText
        setHintTextColor(host.getColor(R.color.muted))
        setTextColor(host.getColor(R.color.ink))
        textSize = 16f
        typeface = Ui.sans(host)
        background = Ui.shape(host, R.color.canvas, 18f, R.color.line)
        setPadding(Ui.dp(host, 16), Ui.dp(host, 14), Ui.dp(host, 16), Ui.dp(host, 14))
    }

    private fun emailSupport(host: Activity, body: String = "") {
        val uri = Uri.parse("mailto:${SupportActivity.EMAIL}" +
            "?subject=${Uri.encode("Moly")}" + if (body.isEmpty()) "" else "&body=${Uri.encode(body)}")
        try {
            host.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (e: Exception) {
            Sheet.info(host, "No email app", "Write to ${SupportActivity.EMAIL} from any email app.")
        }
    }

    /** "Your past messages (3)", once there are any. */
    private fun pastLink(host: Activity, sheet: InfoSheet): View? {
        val n = Storage.loadSentFeedback(host).size
        if (n == 0) return null
        return Ui.quietLink(host, "Your past messages ($n)") { sheet.push(past(host, sheet)) }
    }

    /**
     * Feedback or Support: two words side by side, the chosen one filled.
     * `chosen` is told of a change.
     */
    private fun kindSwitch(
        host: Activity, first: Storage.FeedbackKind, chosen: (Storage.FeedbackKind) -> Unit
    ): LinearLayout {
        val options = mutableMapOf<Storage.FeedbackKind, TextView>()
        fun paint(now: Storage.FeedbackKind) {
            for ((kind, view) in options) {
                val on = kind == now
                view.isSelected = on
                view.background = if (on) Ui.shape(host, R.color.ink, 999f) else null
                view.setTextColor(host.getColor(if (on) R.color.on_ink else R.color.ink))
            }
        }
        return LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.shape(host, R.color.canvas, 999f, R.color.line)
            val gap = Ui.dp(host, 4)
            setPadding(gap, gap, gap, gap)
            for (kind in Storage.FeedbackKind.entries) {
                val option = Ui.txt(host, kind.label, 15f, medium = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(Ui.dp(host, 22), Ui.dp(host, 9), Ui.dp(host, 22), Ui.dp(host, 9))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { paint(kind); chosen(kind) }
                }
                options[kind] = option
                addView(option, Ui.lp(Ui.WRAP))
            }
            paint(first)
        }
    }

    /**
     * The form, opened as feedback or as a support request; or, with the
     * day's ten already sent, where to write instead.
     */
    fun form(host: Activity, sheet: InfoSheet, opened: Storage.FeedbackKind = Storage.FeedbackKind.FEEDBACK): View =
        sheet.page(TITLE) {
            // Below the title, the page is one of three things; each clears the last.
            val start = childCount

            fun clear() { while (childCount > start) removeViewAt(childCount - 1) }

            fun showLimit() {
                clear()
                val wait = Storage.feedbackWaitMs(Storage.loadFeedbackTimes(host), System.currentTimeMillis())
                // To the minute, rounded up: "in 0 minutes" would be a lie.
                val waitMin = ((wait + 59_999) / 60_000).toInt()
                addView(Ui.body(host,
                    "You've sent ${Storage.FEEDBACK_DAILY} messages in the last 24 hours, which is as many as " +
                        "this form takes in a day. For anything more, write to ${SupportActivity.EMAIL}.", 16f),
                    Ui.lp().apply { topMargin = Ui.dp(host, 16) })
                addView(Ui.muted(host, "You can send from here again in ${Storage.fmtSpan(waitMin)}.", 14f),
                    Ui.lp().apply { topMargin = Ui.dp(host, 10) })
                addView(Ui.primaryButton(host, "Email ${SupportActivity.EMAIL}") { emailSupport(host) },
                    Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 24) })
                pastLink(host, sheet)?.let { addView(it, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 18) }) }
            }

            fun showSent(kind: Storage.FeedbackKind, address: String?) {
                clear()
                addView(Ui.display(host, "Sent. Thank you.", 30f), Ui.lp().apply { topMargin = Ui.dp(host, 28) })
                addView(Ui.body(host, when {
                    kind == Storage.FeedbackKind.SUPPORT -> "We'll write back to $address."
                    address != null -> "It goes straight to the person who makes Moly. A reply, if there is " +
                        "one, will come to $address."
                    else -> "It goes straight to the person who makes Moly."
                }, 16f), Ui.lp().apply { topMargin = Ui.dp(host, 12) })
                addView(Ui.secondaryButton(host, "Done") { sheet.pop() },
                    Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 24) })
                pastLink(host, sheet)?.let { addView(it, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 18) }) }
            }

            fun showForm() {
                clear()
                var kind = opened
                val intro = Ui.muted(host, "", 15f).apply { setLineSpacing(0f, 1.45f) }
                val message = box(host, "").apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    gravity = Gravity.TOP or Gravity.START
                    minLines = 5
                    // A long message scrolls inside the box: Send stays in reach under it.
                    maxLines = 10
                    isVerticalScrollBarEnabled = true
                    // As long as anyone writes; the box just stops there.
                    filters = arrayOf(InputFilter.LengthFilter(Storage.FEEDBACK_MAX_CHARS))
                }
                val contact = box(host, "").apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    maxLines = 1
                }
                val problem = Ui.txt(host, "", 14f, R.color.danger).apply {
                    visibility = View.GONE
                    setLineSpacing(0f, 1.35f)
                }
                val byEmail = Ui.quietLink(host, "Or email ${SupportActivity.EMAIL}") {
                    emailSupport(host, message.text.toString().trim())
                }.apply { visibility = View.GONE }
                lateinit var send: Ui.Pill

                fun say(text: String?, offerEmail: Boolean = false) {
                    problem.text = text.orEmpty()
                    problem.visibility = if (text == null) View.GONE else View.VISIBLE
                    byEmail.visibility = if (offerEmail) View.VISIBLE else View.GONE
                }
                // What the page asks for follows the kind; what is already written stays.
                fun ask(now: Storage.FeedbackKind) {
                    kind = now
                    if (now == Storage.FeedbackKind.SUPPORT) {
                        intro.text = "Stuck, or something not working as it should? Say what's happening and " +
                            "we'll write back."
                        message.hint = "What's going wrong?"
                        contact.hint = "Your email, so we can write back"
                    } else {
                        intro.text = "What's working, what isn't, what you wish it did. It goes straight to " +
                            "the person who makes Moly."
                        message.hint = "Your message"
                        contact.hint = "Your email, if you'd like a reply"
                    }
                    say(null)
                }
                val switch = kindSwitch(host, kind, ::ask)
                fun sending(now: Boolean) {
                    message.isEnabled = !now
                    contact.isEnabled = !now
                    for (i in 0 until switch.childCount) switch.getChildAt(i).isEnabled = !now
                    send.isEnabled = !now && Storage.feedbackMessage(message.text.toString()) != null
                }

                send = Ui.primaryButton(host, "Send") {
                    val text = Storage.feedbackMessage(message.text.toString()) ?: return@primaryButton
                    val reply = Storage.feedbackContact(contact.text.toString(), kind)
                    if (!reply.ok) {
                        say(if (contact.text.toString().isBlank()) "Add your email so we can write back."
                            else "That email address doesn't look right." +
                                if (kind == Storage.FeedbackKind.SUPPORT) "" else " Fix it, or leave it empty.")
                        return@primaryButton
                    }
                    if (Storage.feedbackLeft(Storage.loadFeedbackTimes(host), System.currentTimeMillis()) == 0) {
                        showLimit()
                        return@primaryButton
                    }
                    say(null)
                    send.label.text = "Sending…"
                    sending(true)
                    host.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(message.windowToken, 0)
                    val sentAs = kind
                    Feedback.send(host, sentAs, text, reply.address) { outcome ->
                        // The sheet may have been closed while it went.
                        if (!sheet.isShowing) return@send
                        when (outcome) {
                            is Feedback.Outcome.Sent -> showSent(sentAs, reply.address)
                            Feedback.Outcome.Failed -> {
                                send.label.text = "Try again"
                                sending(false)
                                say("Couldn't send it. Check your connection and try again: what you wrote is " +
                                    "still here.", offerEmail = true)
                            }
                        }
                    }
                }.apply { isEnabled = false }

                message.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable) {
                        send.isEnabled = Storage.feedbackMessage(s.toString()) != null
                    }
                    override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                })
                ask(kind)

                addView(switch, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 14) })
                addView(intro, Ui.lp().apply { topMargin = Ui.dp(host, 14) })
                addView(message, Ui.lp().apply { topMargin = Ui.dp(host, 14) })
                addView(contact, Ui.lp().apply { topMargin = Ui.dp(host, 10) })
                addView(Ui.muted(host, "Goes with it: Moly's version, your phone's model and Android version, " +
                    "and whether Moly's permissions are on. Never which apps you limit.", 13f).apply {
                    setLineSpacing(0f, 1.4f)
                }, Ui.lp().apply { topMargin = Ui.dp(host, 12) })
                addView(problem, Ui.lp().apply { topMargin = Ui.dp(host, 14) })
                addView(send, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 16) })
                addView(byEmail, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 6) })
                pastLink(host, sheet)?.let { addView(it, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 14) }) }
            }

            if (Storage.feedbackLeft(Storage.loadFeedbackTimes(host), System.currentTimeMillis()) == 0) showLimit()
            else showForm()
        }

    private val day = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

    /** "11 Nov 2026, 7:52 pm", in the phone's time zone. */
    fun sentWhen(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        day.format(Instant.ofEpochMilli(at).atZone(zone)).replace("AM", "am").replace("PM", "pm")

    /** How many lines of a past message show before it is tapped open. */
    const val PAST_LINES = 3

    /**
     * Every message sent that is still kept, newest first: when and which
     * kind, where a reply was asked for, and the message, cut to a few
     * lines until it is tapped.
     */
    fun past(host: Activity, sheet: InfoSheet): View = sheet.page("Your past messages") {
        for (sent in Storage.loadSentFeedback(host).sortedByDescending { it.sentAt }) {
            addView(View(host).apply { setBackgroundColor(host.getColor(R.color.line)) },
                LinearLayout.LayoutParams(Ui.MATCH, 1).apply { topMargin = Ui.dp(host, 18) })
            addView(Ui.muted(host, Storage.phrases(sentWhen(sent.sentAt), sent.kind.label), 13f),
                Ui.lp().apply { topMargin = Ui.dp(host, 14) })
            if (sent.contact != null) addView(Ui.muted(host, "Reply to ${sent.contact}", 13f),
                Ui.lp().apply { topMargin = Ui.dp(host, 2) })
            addView(Ui.body(host, sent.message, 16f).apply {
                maxLines = PAST_LINES
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
                // A tap shows the rest; another folds it back.
                setOnClickListener { maxLines = if (maxLines == PAST_LINES) Int.MAX_VALUE else PAST_LINES }
            }, Ui.lp().apply { topMargin = Ui.dp(host, 6) })
        }
    }
}
