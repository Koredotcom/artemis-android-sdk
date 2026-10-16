package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisStrings
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.artemis.uisdk.artemisColor
import com.google.gson.JsonObject

/** The view edits a retained draft; only explicit submission uses the feedback transport. */
@SuppressLint("ViewConstructor") // Programmatic card requires a message and callbacks; never inflated from XML.
internal class FeedbackCardView(
    context: Context,
    private val message: ChatMessage,
    private val data: JsonObject,
    private val theme: ArtemisThemeConfig,
    private val strings: ArtemisStrings,
    private val callbacks: ChatMessageAdapter.Callbacks,
) : LinearLayout(context) {
    private val template = FeedbackContracts.parse(data)
    private var response = callbacks.feedbackState(message, data)
    private var draft = template?.normalized(response.draft) ?: response.draft
    private val primaryButtons = mutableListOf<Pair<Int, Button>>()
    private val followButtons = mutableListOf<Pair<Int, Button>>()
    private val commentGroup = LinearLayout(context).apply { orientation = VERTICAL }
    private val followGroup = LinearLayout(context).apply { orientation = VERTICAL }
    private lateinit var comments: EditText
    private var followText: EditText? = null
    private var shownQuestion: String? = null
    private val status = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setTextColor(context.artemisColor(theme, R.color.artemis_text)) }
    private val submit = Button(context)
    private val skip = Button(context)
    private val accent = context.artemisColor(theme, R.color.artemis_primary)
    private val foreground = runCatching { Color.parseColor(theme.primaryForeground) }.getOrDefault(
        if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > .5) Color.BLACK else Color.WHITE)

    init {
        orientation = VERTICAL
        val model = template
        if (model == null) addView(label(text(R.string.artemis_feedback_unavailable))) else {
            data.string("prompt")?.takeIf(String::isNotBlank)?.let { addView(label(it)) }
            if (model.type != "text") ratings(this, model.ratings, model.type, model.emoji, primaryButtons) { score ->
                if (locked()) return@ratings
                draft = model.normalized(draft.copy(rating = score, followUpRating = null, followUpText = ""))
                // Even an identical question under a new score starts a fresh follow-up answer.
                shownQuestion = null; followGroup.removeAllViews(); followButtons.clear(); followText = null
                if (!model.showComment(score) && comments.text.isNotEmpty()) comments.setText("")
                callbacks.onFeedbackDraft(message, data, draft)
                if (response.status == FeedbackSubmissionStatus.FAILED) response = response.copy(status = FeedbackSubmissionStatus.READY)
                refresh()
                if (model.type == "thumbs" && !model.showComment(score) && model.followUpQuestion(score) == null) send(false)
            }
            if (model.type != "text") commentGroup.addView(label(authored("comment_prompt", fallback = R.string.artemis_feedback_comment_prompt)))
            comments = field(authored("comment_placeholder", fallback = R.string.artemis_survey_comment), draft.comments) {
                draft = draft.copy(comments = it); callbacks.onFeedbackDraft(message, data, draft)
                refreshSubmit()
            }
            commentGroup.addView(comments)
            addView(commentGroup)
            addView(followGroup)
            addView(status)
            submit.apply {
                text = authored("submit_label", "submitLabel", fallback = R.string.artemis_submit)
                isAllCaps = false
                setOnClickListener { send(false) }
            }
            addView(submit)
            skip.apply {
                text = text(R.string.artemis_feedback_rating_only)
                isAllCaps = false
                setOnClickListener { send(true) }
            }
            addView(skip)
            refresh()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(id: Int, vararg args: Any) = strings.text(context, id, *args)
    private fun authored(vararg keys: String, fallback: Int) = keys.firstNotNullOfOrNull { data.string(it)?.takeIf(String::isNotBlank) } ?: text(fallback)
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 14f; setTextColor(context.artemisColor(theme, R.color.artemis_text))
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun field(placeholder: String, value: String, changed: (String) -> Unit) = EditText(context).apply {
        hint = placeholder; minLines = 2; maxLines = 5; textSize = 14f
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(5000))
        setTextColor(context.artemisColor(theme, R.color.artemis_text))
        setHintTextColor(context.artemisColor(theme, R.color.artemis_muted))
        setText(value)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun ratings(parent: LinearLayout, values: IntRange, type: String, emoji: Boolean,
                        buttons: MutableList<Pair<Int, Button>>, selected: (Int) -> Unit) {
        val ordered = if (type == "thumbs") listOf(1, 0) else values.toList()
        ordered.chunked(6).forEach { scores ->
            val row = LinearLayout(context)
            parent.addView(row)
            scores.forEach { score ->
                val caption = when {
                    type == "thumbs" -> if (score == 1) "👍" else "👎"
                    emoji && score in 1..5 -> "${listOf("😠", "🙁", "😐", "🙂", "😀")[score - 1]}\n${text(listOf(R.string.artemis_feedback_terrible, R.string.artemis_feedback_poor, R.string.artemis_feedback_okay, R.string.artemis_feedback_good, R.string.artemis_feedback_great)[score - 1])}"
                    type == "star" -> "★ $score"
                    else -> score.toString()
                }
                val button = Button(context).apply {
                    text = caption; textSize = 12f; isAllCaps = false; minWidth = 0
                    minHeight = dp(if (emoji) 56 else 40)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    contentDescription = when {
                        type == "thumbs" -> text(if (score == 1) R.string.artemis_feedback_positive else R.string.artemis_feedback_negative)
                        emoji -> "$score, ${caption.substringAfter('\n')}"
                        else -> text(R.string.artemis_rate, score, values.last)
                    }
                    setOnClickListener { selected(score) }
                }
                buttons.add(score to button)
                row.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3); bottomMargin = dp(3) })
            }
        }
    }
    private fun locked() = response.status in setOf(FeedbackSubmissionStatus.PENDING, FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)
    private fun paint(buttons: List<Pair<Int, Button>>, selected: Int?) {
        buttons.forEach { (score, button) ->
            val active = score == selected
            button.isSelected = active
            button.isEnabled = !locked() && message.serverMessageId != null
            button.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (active) accent else context.artemisColor(theme, R.color.artemis_surface))
                setStroke(dp(1).coerceAtLeast(1), if (active) accent else context.artemisColor(theme, R.color.artemis_border))
            }
            button.setTextColor(if (active) foreground else context.artemisColor(theme, R.color.artemis_text))
        }
    }
    private fun refresh() {
        val model = template ?: return
        val terminal = response.status in setOf(FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)
        paint(primaryButtons, draft.rating)
        commentGroup.visibility = if (!terminal && model.showComment(draft.rating)) VISIBLE else GONE
        comments.isEnabled = !locked()
        val question = model.followUpQuestion(draft.rating).takeUnless { terminal }
        if (shownQuestion != question) {
            shownQuestion = question
            followGroup.removeAllViews(); followButtons.clear(); followText = null
            if (question != null) {
                followGroup.addView(label(question))
                if (model.followUpType == "text") {
                    followText = field(text(R.string.artemis_survey_comment), draft.followUpText) {
                        draft = draft.copy(followUpText = it); callbacks.onFeedbackDraft(message, data, draft)
                    }.also(followGroup::addView)
                } else ratings(followGroup, model.followUpRatings, model.followUpRatingType.orEmpty(), model.emoji && model.followUpRatingType == "star", followButtons) { score ->
                    if (!locked()) {
                        draft = draft.copy(followUpRating = score); callbacks.onFeedbackDraft(message, data, draft)
                        paint(followButtons, score)
                    }
                }
            }
        }
        followGroup.visibility = if (question != null) VISIBLE else GONE
        followText?.isEnabled = !locked()
        paint(followButtons, draft.followUpRating)
        status.text = when (response.status) {
            FeedbackSubmissionStatus.PENDING -> authored("pending_message", fallback = R.string.artemis_survey_pending)
            FeedbackSubmissionStatus.SUBMITTED -> authored("success_message", fallback = R.string.artemis_survey_success)
            FeedbackSubmissionStatus.FAILED -> if (response.errorCode == "NOT_CONNECTED") text(R.string.artemis_feedback_reconnect) else authored("error_message", fallback = R.string.artemis_survey_error)
            FeedbackSubmissionStatus.EXPIRED -> text(R.string.artemis_feedback_expired)
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) GONE else VISIBLE
        submit.visibility = if (terminal || (model.type == "thumbs" && !model.showComment(draft.rating) && question == null && response.status != FeedbackSubmissionStatus.FAILED)) GONE else VISIBLE
        submit.text = if (response.status == FeedbackSubmissionStatus.FAILED) text(R.string.artemis_retry_upload_short) else authored("submit_label", "submitLabel", fallback = R.string.artemis_submit)
        submit.setTextColor(accent)
        skip.visibility = if (!terminal && model.type != "text" && (model.showComment(draft.rating) || question != null)) VISIBLE else GONE
        skip.setTextColor(accent)
        refreshSubmit()
    }
    private fun refreshSubmit() {
        val model = template ?: return
        val enabled = !locked() && message.serverMessageId != null && model.valid(draft.rating)
        submit.isEnabled = enabled && (model.type != "text" || draft.comments.isNotBlank())
        skip.isEnabled = enabled
    }
    private fun send(ratingOnly: Boolean) {
        val model = template ?: return
        if (locked() || message.serverMessageId == null || !model.valid(draft.rating)) return
        val answer = model.normalized(if (ratingOnly) draft.copy(comments = "", followUpRating = null, followUpText = "") else draft)
        if (model.type == "text" && answer.comments.isBlank()) return
        response = response.copy(status = FeedbackSubmissionStatus.PENDING, draft = answer)
        refresh()
        callbacks.onFeedbackResponse(message, data, answer)
        // A synchronous failure can leave StateFlow equal to its previous value. Read it back
        // so a second offline retry cannot leave this view visually pending without a request.
        val current = callbacks.feedbackState(message, data)
        if (current.status != FeedbackSubmissionStatus.READY) {
            response = current; draft = model.normalized(current.draft); refresh()
        }
    }
}
