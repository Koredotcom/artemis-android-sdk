package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
import com.google.android.material.button.MaterialButton

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
    private val submit = MaterialButton(context)
    private val skip = MaterialButton(context)
    private val accent = context.artemisColor(theme, R.color.artemis_primary)
    private val foreground = runCatching { Color.parseColor(theme.primaryForeground) }.getOrDefault(
        if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > .5) Color.BLACK else Color.WHITE)

    init {
        orientation = VERTICAL
        val model = template
        if (model == null) addView(label(text(R.string.artemis_feedback_unavailable))) else {
            data.string("prompt")?.takeIf(String::isNotBlank)?.let { addView(label(it).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
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
            if (model.type != "text") commentGroup.addView(label(authored("comment_prompt", fallback = R.string.artemis_feedback_comment_prompt)).apply {
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.artemisColor(theme, R.color.artemis_muted))
            })
            comments = field(authored("comment_placeholder", fallback = R.string.artemis_survey_comment), draft.comments) {
                draft = draft.copy(comments = it); callbacks.onFeedbackDraft(message, data, draft)
                refreshSubmit()
            }
            commentGroup.addView(comments)
            addView(commentGroup)
            addView(followGroup)
            addView(status)
            submit.apply {
                styleButton(this)
                text = authored("submit_label", "submitLabel", fallback = R.string.artemis_submit)
                setOnClickListener { send(false) }
            }
            addView(submit)
            skip.apply {
                styleButton(this)
                text = text(R.string.artemis_feedback_rating_only)
                setOnClickListener { send(true) }
            }
            addView(skip)
            refresh()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(id: Int, vararg args: Any) = strings.text(context, id, *args)
    private fun authored(vararg keys: String, fallback: Int) = keys.firstNotNullOfOrNull { data.string(it)?.takeIf(String::isNotBlank) } ?: text(fallback)
    private fun styleButton(button: MaterialButton) {
        button.isAllCaps = false
        button.textSize = 13f
        button.minHeight = 0
        button.insetTop = 0; button.insetBottom = 0
        button.cornerRadius = dp(18)
        button.strokeWidth = dp(1)
        button.strokeColor = android.content.res.ColorStateList.valueOf(accent)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        button.setTextColor(accent)
        button.setPadding(dp(14), dp(7), dp(14), dp(7))
    }
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 13f; setTextColor(context.artemisColor(theme, R.color.artemis_text))
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun field(placeholder: String, value: String, changed: (String) -> Unit) = EditText(context).apply {
        hint = placeholder; minLines = 2; maxLines = 5; textSize = 13f
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(5000))
        setTextColor(context.artemisColor(theme, R.color.artemis_text))
        setHintTextColor(context.artemisColor(theme, R.color.artemis_muted))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(context.artemisColor(theme, R.color.artemis_background))
            setStroke(dp(1), context.artemisColor(theme, R.color.artemis_border))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
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
                    type == "star" || type == "stars" -> "★"
                    else -> score.toString()
                }
                val button = Button(context).apply {
                    text = if (emoji && caption.contains('\n')) SpannableString(caption).apply {
                        val lineBreak = caption.indexOf('\n')
                        setSpan(RelativeSizeSpan(1.5f), 0, lineBreak, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(.8f), lineBreak + 1, caption.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else caption
                    textSize = if (type == "star" || type == "stars") 18f else 12f; isAllCaps = false; minWidth = 0
                    gravity = android.view.Gravity.CENTER
                    minHeight = dp(if (emoji) 48 else 38)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    contentDescription = when {
                        type == "thumbs" -> text(if (score == 1) R.string.artemis_feedback_positive else R.string.artemis_feedback_negative)
                        emoji -> "$score, ${caption.substringAfter('\n')}"
                        else -> text(R.string.artemis_rate, score, values.last)
                    }
                    setOnClickListener { selected(score) }
                }
                buttons.add(score to button)
                row.addView(button, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(if (emoji) 4 else 8); bottomMargin = dp(if (emoji) 4 else 8) })
            }
        }
    }
    private fun locked() = response.status in setOf(FeedbackSubmissionStatus.PENDING, FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)
    private fun paint(buttons: List<Pair<Int, Button>>, selected: Int?) {
        buttons.forEach { (score, button) ->
            val isStar = template?.type in setOf("star", "stars")
            val isNps = template?.type == "scale" && (template?.ratings?.last ?: 0) - (template?.ratings?.first ?: 0) >= 8
            val active = selected != null && if (isStar) score <= selected else score == selected
            button.isSelected = active
            button.isEnabled = !locked() && message.serverMessageId != null
            button.alpha = if (button.isEnabled) 1f else .65f
            val ratingColor = if (isNps) when {
                score <= 6 -> Color.rgb(185, 28, 28)
                score <= 8 -> Color.rgb(180, 83, 9)
                else -> Color.rgb(21, 128, 61)
            } else accent
            button.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                val fill = when {
                    active && isNps -> ratingColor
                    active -> androidx.core.graphics.ColorUtils.blendARGB(context.artemisColor(theme, R.color.artemis_background), ratingColor, .14f)
                    isNps -> androidx.core.graphics.ColorUtils.blendARGB(context.artemisColor(theme, R.color.artemis_background), ratingColor, .1f)
                    else -> context.artemisColor(theme, R.color.artemis_background)
                }
                setColor(fill)
                setStroke(dp(1).coerceAtLeast(1), if (active || isNps) ratingColor else context.artemisColor(theme, R.color.artemis_border))
            }
            button.setTextColor(when {
                isStar && active -> Color.rgb(245, 158, 11)
                isNps && active -> Color.WHITE
                isNps -> ratingColor
                else -> context.artemisColor(theme, R.color.artemis_text)
            })
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
                followGroup.addView(label(question).apply {
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(context.artemisColor(theme, R.color.artemis_muted))
                })
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
