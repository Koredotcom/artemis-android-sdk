package com.artemis.uisdk.chat

import android.icu.text.DateTimePatternGenerator
import android.icu.util.ULocale
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisTimestampDateFormat
import com.artemis.uisdk.ArtemisMessageTimestampSettings
import com.artemis.uisdk.ArtemisTimestampPosition
import com.artemis.uisdk.ArtemisTimestampTimeFormat
import com.artemis.uisdk.ArtemisThemeConfig
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

internal class ChatMessageAdapter(
    private val features: ArtemisFeatureConfig,
    private var theme: ArtemisThemeConfig,
    private val callbacks: Callbacks,
    private val handle: com.artemis.uisdk.ArtemisUiHandle? = null,
) : ListAdapter<ChatMessage, ChatMessageAdapter.ChatMessageViewHolder>(CHAT_MESSAGE_DIFF_CALLBACK) {
    interface Callbacks {
        fun onAction(
            message: ChatMessage,
            actionIdentifier: String,
            actionValue: String?,
            formData: Map<String, String>?,
            renderIdentifier: String?
        )

        fun onFeedback(
            message: ChatMessage,
            feedbackType: String,
            rating: Int,
            feedbackText: String?
        )

        fun isTemplateLocked(message: ChatMessage, actionIdentifier: String, renderId: String?): Boolean = isLocked(message, actionIdentifier)
        fun templateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject): Map<String, String> = emptyMap()
        fun onTemplateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject, values: Map<String, String>) = Unit
        fun feedbackState(
            message: ChatMessage,
            data: com.google.gson.JsonObject
        ): FeedbackResponseState =
            message.feedbackResponses[FeedbackContracts.renderId(message, data).orEmpty()]
                ?: FeedbackResponseState()

        fun onFeedbackDraft(
            message: ChatMessage,
            data: com.google.gson.JsonObject,
            draft: FeedbackDraft
        ) = Unit

        fun onFeedbackResponse(
            message: ChatMessage,
            data: com.google.gson.JsonObject,
            draft: FeedbackDraft
        ) {
            FeedbackContracts.parse(data)?.let { template ->
                onFeedback(
                    message,
                    template.type,
                    draft.rating ?: 0,
                    draft.comments.takeIf(String::isNotBlank)
                )
            }
        }

        fun onAttachment(message: ChatMessage, attachment: AttachmentRef) = Unit
        fun onAuthorize(message: ChatMessage, rowId: String) = Unit
        fun onCheckAuthorization(message: ChatMessage, rowId: String) = Unit
        fun onCancelAuthorization(message: ChatMessage, rowId: String) = Unit
        fun onTransferSurvey(message: ChatMessage, score: Int, comments: String?) = Unit
        fun surveyDraft(message: ChatMessage): SurveyDraft = SurveyDraft()
        fun onSurveyDraft(message: ChatMessage, draft: SurveyDraft) = Unit
        fun onContactCenterError(message: String) = Unit
        fun isLocked(message: ChatMessage, actionIdentifier: String): Boolean
    }

    class ChatMessageViewHolder(val messageRowLayout: LinearLayout) :
        RecyclerView.ViewHolder(messageRowLayout) {
        val bubble: LinearLayout by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_bubble) }
        val templateContainer: LinearLayout by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_message_templates) }
        val textView: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_message_text) }
        val timestampView: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_timestamp) }
        val messageSourceView: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_message_source) }
        val answeredByAIView: View by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_answered_by_ai) }
        val answeredByAIIcon: ImageView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_answered_by_ai_icon) }
        val answeredByAIText: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_answered_by_ai_text) }
        var boundMessage: ChatMessage? = null
        var boundPresentation = -1
        var boundWidth = 0
        val videoViews = mutableListOf<android.widget.VideoView>()
        var onMediaReleased: (() -> Unit)? = null
        var mediaPlayer: MediaPlayer? = null
        fun releaseMediaPlayer() {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            videoViews.forEach { runCatching { it.stopPlayback() } }
            onMediaReleased?.invoke()
        }
    }

    private val contactCenter = ContactCenterRenderer(features, callbacks, handle)
    private val templates = DefaultRichTemplateRenderer(features, theme, callbacks, handle)
    private val formDrafts = linkedMapOf<String, Pair<ChatMessage, List<Pair<String, Any>>>>()
    private var presentationRevision = 0
    private var presentation = handle?.presentation?.value
    fun updatePresentation(value: com.artemis.uisdk.ArtemisPresentation) {
        val previous = presentation
        if (previous?.branding?.theme == value.branding.theme && previous.strings === value.strings &&
            previous.typography == value.typography && previous.timestampSettings == value.timestampSettings &&
            previous.rendererRevision == value.rendererRevision) return
        presentation = value
        theme = value.branding.theme
        templates.updateTheme(theme)
        presentationRevision++
        templateCache.clear()
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "presentation")
    }

    private var recyclerView: RecyclerView? = null
    private val templateCache = TemplateViewCache()
    private val stableIds = mutableMapOf<String, Long>()
    private var nextStableId = 0L

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        stableIds.getOrPut(getItem(position).messageId) { nextStableId++ }

    override fun onCurrentListChanged(
        previousList: MutableList<ChatMessage>,
        currentList: MutableList<ChatMessage>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        val ids = currentList.map { it.messageId }.toSet()
        stableIds.keys.retainAll(ids)
        templateCache.prune(ids)
        formDrafts.keys.retainAll(ids)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        templateCache.clear()
        templates.clearCaches()
        formDrafts.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder =
        ChatMessageViewHolder(
            android.view.LayoutInflater.from(parent.context).inflate(
                com.artemis.uisdk.R.layout.artemis_message_row, parent, false
            ) as LinearLayout
        )

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        val message = getItem(position)
        val attachedRecyclerView = recyclerView
        val availableRowWidth = ((attachedRecyclerView?.width?.takeIf { it > 0 }
            ?: holder.messageRowLayout.context.resources.displayMetrics.widthPixels) -
                (attachedRecyclerView?.paddingLeft ?: 0) - (attachedRecyclerView?.paddingRight
            ?: 0)).coerceAtLeast(1)
        val previous = holder.boundMessage
        if (previous == message && holder.boundPresentation == presentationRevision && holder.boundWidth == availableRowWidth) return
        val reuseTemplates =
            holder.boundPresentation == presentationRevision && previous != null && sameTemplatePresentation(
                previous,
                message
            )
        // Keep unsent form values when appearance/strings change; ordinary state updates still reuse views.
        val drafts =
            if (reuseTemplates) emptyList() else if (previous?.messageId == message.messageId && !reuseTemplates && sameTemplatePresentation(
                    previous,
                    message
                )
            ) editableValues(holder.templateContainer)
            else formDrafts[message.messageId]?.takeIf {
                sameTemplatePresentation(
                    it.first,
                    message
                )
            }?.second.orEmpty()
        if (!reuseTemplates) {
            retainTemplates(holder)
            holder.releaseMediaPlayer()
            holder.onMediaReleased = null
            holder.videoViews.clear()
        }
        val context = holder.messageRowLayout.context
        val isUserMessage = message.role == MessageRole.USER
        holder.messageRowLayout.gravity = if (isUserMessage) Gravity.END else Gravity.START
        val messageBubble = holder.bubble
        val templateContainer = holder.templateContainer
        val bubbleColor = if (isUserMessage) {
            color(
                theme.userBubble,
                color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary))
            )
        } else {
            color(
                theme.assistantBubble,
                context.getColor(com.artemis.uisdk.R.color.artemis_assistant_bubble)
            )
        }
        messageBubble.background = GradientDrawable().apply {
            setColor(bubbleColor)
            cornerRadius = theme.bubbleRadiusDp * context.resources.displayMetrics.density
        }
        val baseMessageText = if (isUserMessage) message.metadata?.get("display_label")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() }?.asString
            ?: message.text
        else message.transferSurvey?.takeIf { "prompt" !in it.suppliedLabels && it.prompt == "How would you rate your experience?" && message.text == it.prompt }
            ?.let {
                handle?.presentation?.value?.strings?.text(
                    context,
                    com.artemis.uisdk.R.string.artemis_survey_prompt
                )
            } ?: message.text
        val displayedMessageText = if (!isUserMessage && TemplateContracts.hasPreferredText(message)) "" else baseMessageText
        val markdownText = !isUserMessage && features.markdown && message.metadata?.string("contentFormat") != "plain_text"
        val renderedMessageText = if (markdownText) {
            templates.parseMarkdown(context, displayedMessageText)
        } else displayedMessageText
        val linkedMessageText = if (features.linkifyPhoneNumbers && renderedMessageText.isNotBlank()) {
            SpannableString(renderedMessageText).apply {
                Linkify.addLinks(this, Patterns.PHONE, "tel:")
            }
        } else renderedMessageText
        val messageTextView = holder.textView
        messageTextView.apply {
            visibility = if (displayedMessageText.isBlank()) View.GONE else View.VISIBLE
            setTextColor(
                if (isUserMessage) color(
                    theme.userBubbleText ?: theme.primaryForeground,
                    if (androidx.core.graphics.ColorUtils.calculateLuminance(bubbleColor) > .5) Color.BLACK else Color.WHITE
                ) else color(
                    theme.assistantBubbleText ?: theme.text,
                    context.getColor(com.artemis.uisdk.R.color.artemis_text)
                )
            )
            textSize = if (isUserMessage) 14f else 16f
            setLineSpacing(0f, 1.25f)
            text = linkedMessageText
            movementMethod = if (markdownText || features.linkifyPhoneNumbers) LinkMovementMethod.getInstance() else null
            setLinkTextColor(
                if (isUserMessage) currentTextColor else color(
                    theme.primary,
                    context.getColor(com.artemis.uisdk.R.color.artemis_primary)
                )
            )
        }
        // Constrain to the actual RecyclerView width, including embedded/split-screen hosts.
        val containsRichContent =
            message.richContent != null || message.actions != null || message.renderables != null ||
                    message.attachments.isNotEmpty() || message.agentLink != null || message.transferSurvey != null || features.authentication && message.authorization != null
        val isStandaloneRichTemplate =
            containsRichContent && displayedMessageText.isBlank() && !isUserMessage &&
                    !TemplateContracts.hasPreferredText(message)
        if (isStandaloneRichTemplate) messageBubble.background = null
        messageBubble.setPadding(
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
        )
        // Web parity (`.message.system`): a plain-text system line (transfer status, notices) is
        // centered muted text without a bubble. System cards such as authorization keep their layout.
        val isStatusLine = message.role == MessageRole.SYSTEM && !containsRichContent
        if (isStatusLine) {
            holder.messageRowLayout.gravity = Gravity.CENTER_HORIZONTAL
            messageBubble.background = null
            messageTextView.setTextColor(color(theme.mutedText, context.getColor(com.artemis.uisdk.R.color.artemis_muted)))
            messageTextView.textSize = 13f
            messageTextView.gravity = Gravity.CENTER_HORIZONTAL
        } else {
            messageTextView.gravity = Gravity.START
        }
        val maximumBubbleWidth = (availableRowWidth * if (isUserMessage) .82f else .94f).toInt()
        messageTextView.maxWidth =
            (maximumBubbleWidth - messageBubble.paddingLeft - messageBubble.paddingRight).coerceAtLeast(
                1
            )
        messageBubble.layoutParams =
            (messageBubble.layoutParams as LinearLayout.LayoutParams).apply {
                width =
                    if (containsRichContent) maximumBubbleWidth else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        if (!reuseTemplates) {
            val cached = templateCache.take(message)
            if (cached != null) cached.forEach(templateContainer::addView)
            else {
                if (features.authentication && message.authorization != null) templateContainer.addView(
                    AuthorizationCardView(context, message, theme, handle?.presentation?.value?.strings ?: com.artemis.uisdk.ArtemisStrings(), callbacks))
                contactCenter.render(context, message, templateContainer)
                message.richContent?.let { richContent ->
                    templates.renderRich(
                        context,
                        holder,
                        message,
                        templateContainer,
                        richContent
                    )
                }
                templates.renderRenderables(context, holder, message, templateContainer)
                if (features.citations && message.role == MessageRole.ASSISTANT) message.citations.forEach { citation ->
                    val url = TemplateContracts.actionUrl(citation.url) ?: return@forEach
                    templateContainer.addView(TextView(context).apply {
                        text = "[${citation.index}] ${citation.title}"
                        setTextColor(
                            color(
                                theme.primary,
                                context.getColor(com.artemis.uisdk.R.color.artemis_primary)
                            )
                        )
                        setPadding(4, 8, 4, 8)
                        setOnClickListener {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            }
                        }
                    })
                }
                message.actions?.let { messageActions ->
                    templates.renderActions(
                        context,
                        message,
                        templateContainer,
                        messageActions
                    )
                }
            }
        }
        if (drafts.isNotEmpty()) restoreEditableValues(holder.templateContainer, drafts)
        val isHumanAgentMessage = message.messageSource?.string("system") == "integration"
        holder.answeredByAIView.visibility = if (
            features.showAnsweredByAI && message.role == MessageRole.ASSISTANT && !isHumanAgentMessage &&
                message.messageStatus != MessageStatus.STREAMING
        ) View.VISIBLE else View.GONE
        if (holder.answeredByAIView.visibility == View.VISIBLE) {
            holder.answeredByAIText.text = handle?.presentation?.value?.strings?.text(
                context, com.artemis.uisdk.R.string.artemis_answered_by_ai
            ) ?: context.getString(com.artemis.uisdk.R.string.artemis_answered_by_ai)
            holder.answeredByAIText.setTextColor(color(theme.mutedText, context.getColor(com.artemis.uisdk.R.color.artemis_muted)))
            holder.answeredByAIIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary))
            )
        }
        holder.messageSourceView.apply {
            visibility = View.GONE
            text = ""
        }
        val timestampSettings = presentation?.timestampSettings ?: ArtemisMessageTimestampSettings()
        holder.timestampView.apply {
            visibility =
                if (features.timestamps && message.messageStatus != MessageStatus.STREAMING && message.role != MessageRole.SYSTEM) View.VISIBLE else View.GONE
            val locale = handle?.presentation?.value?.strings?.localeTag?.let(java.util.Locale::forLanguageTag)
                ?: java.util.Locale.getDefault()
            val date = Date(message.timestamp)
            val dateText = when (timestampSettings.dateFormat) {
                ArtemisTimestampDateFormat.LOCALE -> {
                    val icuLocale = ULocale.forLocale(locale)
                    val pattern = DateTimePatternGenerator.getInstance(icuLocale).getBestPattern("yMMMd")
                    android.icu.text.SimpleDateFormat(pattern, icuLocale).format(date)
                }
                ArtemisTimestampDateFormat.MONTH_DAY_YEAR -> SimpleDateFormat("MM/dd/yy", locale).format(date)
                ArtemisTimestampDateFormat.DAY_MONTH_YEAR -> SimpleDateFormat("dd/MM/yy", locale).format(date)
                ArtemisTimestampDateFormat.NAMED_MONTH_DAY_YEAR -> SimpleDateFormat("MMM dd, yyyy", locale).format(date)
            }
            val timeFormatter = when (timestampSettings.timeFormat) {
                ArtemisTimestampTimeFormat.LOCALE -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
                ArtemisTimestampTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
                ArtemisTimestampTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
            }
            text = "$dateText • ${timeFormatter.format(date)}"
            setTextColor(
                color(
                    theme.mutedText,
                    context.getColor(com.artemis.uisdk.R.color.artemis_muted)
                )
            )
        }
        val timestampIndex = holder.messageRowLayout.indexOfChild(holder.timestampView)
        val targetTimestampIndex = if (timestampSettings.position == ArtemisTimestampPosition.ABOVE) {
            0
        } else holder.messageRowLayout.childCount - 1
        if (timestampIndex != targetTimestampIndex) {
            holder.messageRowLayout.removeView(holder.timestampView)
            holder.messageRowLayout.addView(
                holder.timestampView,
                targetTimestampIndex.coerceAtMost(holder.messageRowLayout.childCount),
            )
        }
        presentation?.typography?.applyTo(holder.messageRowLayout)
        holder.boundPresentation = presentationRevision
        holder.boundMessage = message
        holder.boundWidth = availableRowWidth
    }

    private fun retainTemplates(holder: ChatMessageViewHolder) {
        val container = holder.templateContainer
        holder.boundMessage?.let { message ->
            val values = editableValues(container)
            if (values.isNotEmpty()) formDrafts[message.messageId] = message to values
            while (formDrafts.size > 32) formDrafts.remove(formDrafts.keys.first())
        }
        val views = (0 until container.childCount).map(container::getChildAt)
        container.removeAllViews()
        val cached = recyclerView != null && holder.boundPresentation == presentationRevision && holder.boundMessage != null
        if (cached) templateCache.retain(holder.boundMessage!!, views) else views.forEach(::releaseAdaptiveViews)
    }

    override fun onViewRecycled(holder: ChatMessageViewHolder) {
        retainTemplates(holder)
        holder.releaseMediaPlayer()
        holder.onMediaReleased = null
        holder.videoViews.clear()
        holder.boundMessage = null
        holder.boundWidth = 0
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: ChatMessageViewHolder) {
        holder.releaseMediaPlayer(); super.onViewDetachedFromWindow(holder)
    }

    private fun editableValues(root: ViewGroup): List<Pair<String, Any>> = buildList {
        fun visit(view: View) {
            if (view is FeedbackCardView || view is TemplateControlsView || view.tag === NativeTemplateDraftOwner) return
            when (view) {
                is android.widget.EditText -> add("text" to view.text.toString())
                is android.widget.Spinner -> add("select" to view.selectedItemPosition)
                is android.widget.CompoundButton -> add("check" to view.isChecked)
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        visit(root)
    }

    private fun restoreEditableValues(root: ViewGroup, values: List<Pair<String, Any>>) {
        var index = 0
        fun visit(view: View) {
            if (view is FeedbackCardView || view is TemplateControlsView || view.tag === NativeTemplateDraftOwner) return
            val kind = when (view) {
                is android.widget.EditText -> "text"; is android.widget.Spinner -> "select"; is android.widget.CompoundButton -> "check"; else -> null
            }
            if (kind != null) {
                val value = values.getOrNull(index++)?.takeIf { it.first == kind }?.second
                when (view) {
                    is android.widget.EditText -> (value as? String)?.let(view::setText)
                    is android.widget.Spinner -> (value as? Int)?.takeIf { it in 0 until view.count }
                        ?.let(view::setSelection)

                    is android.widget.CompoundButton -> (value as? Boolean)?.let {
                        view.isChecked = it
                    }
                }
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        visit(root)
    }

    private fun color(value: String?, fallback: Int) =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        private val CHAT_MESSAGE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.messageId == newItem.messageId

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem == newItem
        }
    }
}
