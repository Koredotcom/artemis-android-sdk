package com.artemis.uisdk.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Contact-center cards are native controls and take part in the existing bounded view cache. */
internal class ContactCenterRenderer(
    private val features: com.artemis.uisdk.ArtemisFeatureConfig,
    private val callbacks: ChatMessageAdapter.Callbacks,
    private val handle: com.artemis.uisdk.ArtemisUiHandle? = null
) {
    private fun label(context: Context, id: Int, vararg args: Any) =
        handle?.presentation?.value?.strings?.text(context, id, *args) ?: context.getString(
            id,
            *args
        )

    fun render(context: Context, message: ChatMessage, container: LinearLayout) {
        val attachments = message.attachments.takeIf { features.attachments }.orEmpty()
        if (attachments.isNotEmpty()) container.addView(
            createAttachmentGroup(
                context,
                attachments,
                message.role == MessageRole.USER,
                handle?.presentation?.value?.strings ?: com.artemis.uisdk.ArtemisStrings(),
                handle?.branding?.theme ?: com.artemis.uisdk.ArtemisThemeConfig()
            ) { attachment ->
                callbacks.onAttachment(message, attachment)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin =
                    if (message.text.isNotBlank()) (8 * context.resources.displayMetrics.density).toInt() else 0
            })
        message.agentLink?.takeIf { features.contactCenter && (it.kind != AgentLinkKind.ATTACHMENT || features.attachments) }
            ?.let { link ->
                container.addView(Button(context).apply {
                    text = link.title; isAllCaps = false
                    setOnClickListener {
                        contactCenterUrl(link.url)?.let { url ->
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(url)
                                    )
                                )
                            }
                                .onFailure {
                                    callbacks.onContactCenterError(
                                        label(
                                            context,
                                            com.artemis.uisdk.R.string.artemis_no_browser
                                        )
                                    )
                                }
                        }
                    }
                })
            }
        val survey = message.transferSurvey?.takeIf { features.contactCenter } ?: return
        val defaults = AgentTransferSurvey(survey.surveyType, survey.artemisSessionId)
        fun surveyLabel(key: String, value: String, default: String, id: Int) =
            if (key in survey.suppliedLabels || value != default) value else label(context, id)

        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(card)
        if (survey.status == SurveySubmissionStatus.SUBMITTED) {
            card.addView(TextView(context).apply {
                text = surveyLabel(
                    "successMessage",
                    survey.successMessage,
                    defaults.successMessage,
                    com.artemis.uisdk.R.string.artemis_survey_success
                ); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            return
        }
        var draft = callbacks.surveyDraft(message)
        val pending = survey.status == SurveySubmissionStatus.PENDING
        val ratings = mutableListOf<Pair<Int, Button>>()
        survey.choices.chunked(6).forEach { scores ->
            val row = LinearLayout(context)
            card.addView(row)
            scores.forEach { score ->
                val button = Button(context).apply {
                    isAllCaps = false; minWidth = 0
                    setPadding(0, paddingTop, 0, paddingBottom)
                    contentDescription = if (survey.surveyType == "likeDislike") {
                        if (score == 1) label(
                            context,
                            com.artemis.uisdk.R.string.artemis_yes
                        ) else label(context, com.artemis.uisdk.R.string.artemis_no)
                    } else label(
                        context,
                        com.artemis.uisdk.R.string.artemis_rate,
                        score,
                        survey.max
                    )
                    isEnabled = !pending
                }
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                ratings.add(score to button)
            }
        }
        val commentLabel = TextView(context).apply { text = survey.commentPrompt.orEmpty() }
        card.addView(commentLabel)
        val comments = EditText(context).apply {
            hint = surveyLabel(
                "commentPlaceholder",
                survey.commentPlaceholder,
                defaults.commentPlaceholder,
                com.artemis.uisdk.R.string.artemis_survey_comment
            ); minLines = 2; maxLines = 5
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
            setText(draft.comments); isEnabled = !pending
        }
        card.addView(comments)
        val status = TextView(context).apply {
            text = when (survey.status) {
                SurveySubmissionStatus.PENDING -> surveyLabel(
                    "pendingMessage",
                    survey.pendingMessage,
                    defaults.pendingMessage,
                    com.artemis.uisdk.R.string.artemis_survey_pending
                ); SurveySubmissionStatus.FAILED -> surveyLabel(
                    "errorMessage",
                    survey.errorMessage,
                    defaults.errorMessage,
                    com.artemis.uisdk.R.string.artemis_survey_error
                ); else -> ""
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(status)
        val submit = Button(context).apply {
            text = surveyLabel(
                "submitLabel",
                survey.submitLabel,
                defaults.submitLabel,
                com.artemis.uisdk.R.string.artemis_submit
            ); isAllCaps = false
        }
        card.addView(submit)
        fun refresh() {
            ratings.forEach { (score, button) ->
                val active =
                    if (survey.surveyType == "csat") draft.score?.let { score <= it } == true else score == draft.score
                button.isSelected = active
                button.text = when (survey.surveyType) {
                    "csat" -> if (active) "★" else "☆"
                    "likeDislike" -> if (score == 1) "👍 ${
                        label(
                            context,
                            com.artemis.uisdk.R.string.artemis_yes
                        )
                    }" else "👎 ${label(context, com.artemis.uisdk.R.string.artemis_no)}"

                    else -> score.toString()
                }
                button.alpha = if (draft.score == null || active) 1f else .55f
            }
            val show = survey.showComment(draft.score)
            comments.visibility = if (show) View.VISIBLE else View.GONE
            commentLabel.visibility =
                if (show && !survey.commentPrompt.isNullOrBlank()) View.VISIBLE else View.GONE
            submit.isEnabled = !pending && draft.score?.let(survey::validScore) == true
        }
        ratings.forEach { (score, button) ->
            button.setOnClickListener {
                draft = draft.copy(score = score)
                callbacks.onSurveyDraft(message, draft)
                refresh()
            }
        }
        comments.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft = draft.copy(comments = s?.toString().orEmpty()); callbacks.onSurveyDraft(
                    message,
                    draft
                )
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        submit.setOnClickListener {
            draft.score?.let { score ->
                callbacks.onTransferSurvey(
                    message,
                    score,
                    draft.comments.takeIf { survey.showComment(score) })
            }
        }
        refresh()
    }
}
