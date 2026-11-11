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
 * The feedback pages of the info sheet: the form ("Send feedback" at the
 * foot of About), what it turns into once a message is sent or the day's
 * ten are used up, and the past feedback kept on the phone.
 */
object FeedbackPages {

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
            "?subject=${Uri.encode("Moly feedback")}" + if (body.isEmpty()) "" else "&body=${Uri.encode(body)}")
        try {
            host.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (e: Exception) {
            Sheet.info(host, "No email app", "Write to ${SupportActivity.EMAIL} from any email app.")
        }
    }

    /** "Your past feedback (3)", once there is any. */
    private fun pastLink(host: Activity, sheet: InfoSheet): View? {
        val n = Storage.loadSentFeedback(host).size
        if (n == 0) return null
        return Ui.quietLink(host, "Your past feedback ($n)") { sheet.push(past(host, sheet)) }
    }

    /** The form; or, with the day's ten already sent, where to write instead. */
    fun form(host: Activity, sheet: InfoSheet): View = sheet.page("Send feedback") {
        // Below the title, the page is one of three things; each clears the last.
        val start = childCount

        fun clear() { while (childCount > start) removeViewAt(childCount - 1) }

        fun showLimit() {
            clear()
            val wait = Storage.feedbackWaitMs(Storage.loadFeedbackTimes(host), System.currentTimeMillis())
            // To the minute, rounded up: "in 0 seconds" would be a lie.
            val waitMin = ((wait + 59_999) / 60_000).toInt()
            addView(Ui.body(host,
                "You've sent ${Storage.FEEDBACK_DAILY} messages in the last 24 hours, which is as many as this " +
                    "form takes in a day. For anything more, write to ${SupportActivity.EMAIL}.", 16f),
                Ui.lp().apply { topMargin = Ui.dp(host, 16) })
            addView(Ui.muted(host, "You can send from here again in ${Storage.fmtSpan(waitMin)}.", 14f),
                Ui.lp().apply { topMargin = Ui.dp(host, 10) })
            addView(Ui.primaryButton(host, "Email ${SupportActivity.EMAIL}") { emailSupport(host) },
                Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 24) })
            pastLink(host, sheet)?.let { addView(it, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 18) }) }
        }

        fun showSent() {
            clear()
            addView(Ui.display(host, "Sent. Thank you.", 30f), Ui.lp().apply { topMargin = Ui.dp(host, 28) })
            addView(Ui.body(host,
                "It goes straight to the person who makes Moly. If you left an address, that's where a reply " +
                    "will come.", 16f), Ui.lp().apply { topMargin = Ui.dp(host, 12) })
            addView(Ui.secondaryButton(host, "Done") { sheet.pop() },
                Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 24) })
            pastLink(host, sheet)?.let { addView(it, Ui.lp(Ui.WRAP).apply { topMargin = Ui.dp(host, 18) }) }
        }

        fun showForm() {
            clear()
            addView(Ui.muted(host, "What's working, what isn't, what you wish it did. It goes straight to the " +
                "person who makes Moly.", 15f).apply { setLineSpacing(0f, 1.45f) },
                Ui.lp().apply { topMargin = Ui.dp(host, 10) })

            val message = box(host, "Your message").apply {
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
            val contact = box(host, "Your email, if you'd like a reply").apply {
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
            fun sending(now: Boolean) {
                message.isEnabled = !now
                contact.isEnabled = !now
                send.isEnabled = !now && Storage.feedbackMessage(message.text.toString()) != null
            }

            send = Ui.primaryButton(host, "Send") {
                val text = Storage.feedbackMessage(message.text.toString()) ?: return@primaryButton
                val reply = Storage.feedbackContact(contact.text.toString())
                if (!reply.ok) {
                    say("That email address doesn't look right. Fix it, or leave it empty.")
                    return@primaryButton
                }
                if (Storage.feedbackLeft(Storage.loadFeedbackTimes(host), System.currentTimeMillis()) == 0) {
                    showLimit()
                    return@primaryButton
                }
                say(null)
                send.label.text = "Sending…"
                sending(true)
                host.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(message.windowToken, 0)
                Feedback.send(host, text, reply.address) { outcome ->
                    // The sheet may have been closed while it went.
                    if (!sheet.isShowing) return@send
                    when (outcome) {
                        is Feedback.Outcome.Sent -> showSent()
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

            addView(message, Ui.lp().apply { topMargin = Ui.dp(host, 18) })
            addView(contact, Ui.lp().apply { topMargin = Ui.dp(host, 10) })
            addView(Ui.muted(host, "Goes with it: Moly's version, your phone's model and Android version, and " +
                "whether Moly's permissions are on. Never which apps you limit.", 13f).apply {
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

    /** Everything sent from this phone that is still kept, newest first. */
    fun past(host: Activity, sheet: InfoSheet): View =
        sheet.page("Your past feedback", "What you've sent from this form, kept on your phone.") {
            for (sent in Storage.loadSentFeedback(host).sortedByDescending { it.sentAt }) {
                addView(View(host).apply { setBackgroundColor(host.getColor(R.color.line)) },
                    LinearLayout.LayoutParams(Ui.MATCH, 1).apply { topMargin = Ui.dp(host, 18) })
                addView(Ui.muted(host, sentWhen(sent.sentAt), 13f), Ui.lp().apply { topMargin = Ui.dp(host, 14) })
                addView(Ui.body(host, sent.message, 16f).apply { setTextIsSelectable(true) },
                    Ui.lp().apply { topMargin = Ui.dp(host, 6) })
            }
        }
}
