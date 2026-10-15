package com.artemis.uisdk.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import java.text.DateFormat
import java.util.Date

internal class ChatMessageAdapter(
    private val features: ArtemisFeatureConfig,
    private val theme: ArtemisThemeConfig,
    private val callbacks: Callbacks,
) : ListAdapter<ChatMessage, ChatMessageAdapter.ChatMessageViewHolder>(CHAT_MESSAGE_DIFF_CALLBACK) {
    interface Callbacks {
        fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String, String>?, renderIdentifier: String?)
        fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?)
        fun onAttachment(message: ChatMessage, attachment: AttachmentRef) = Unit
        fun onTransferSurvey(message: ChatMessage, score: Int, comments: String?) = Unit
        fun surveyDraft(message: ChatMessage): SurveyDraft = SurveyDraft()
        fun onSurveyDraft(message: ChatMessage, draft: SurveyDraft) = Unit
        fun onContactCenterError(message: String) = Unit
        fun isLocked(message: ChatMessage, actionIdentifier: String): Boolean
    }

    class ChatMessageViewHolder(val messageRowLayout: LinearLayout) : RecyclerView.ViewHolder(messageRowLayout) {
        val bubble: LinearLayout by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_bubble) }
        val templateContainer: LinearLayout by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_message_templates) }
        val textView: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_message_text) }
        val timestampView: TextView by lazy { messageRowLayout.findViewById(com.artemis.uisdk.R.id.artemis_timestamp) }
        var boundMessage: ChatMessage? = null
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

    private val contactCenter = ContactCenterRenderer(features, callbacks)
    private val templates = DefaultRichTemplateRenderer(features, theme, callbacks)
    private var recyclerView: RecyclerView? = null
    private val templateCache = TemplateViewCache()
    private val stableIds = mutableMapOf<String, Long>()
    private var nextStableId = 0L
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).messageId) { nextStableId++ }
    override fun onCurrentListChanged(previousList: MutableList<ChatMessage>, currentList: MutableList<ChatMessage>) {
        super.onCurrentListChanged(previousList, currentList)
        val ids = currentList.map { it.messageId }.toSet()
        stableIds.keys.retainAll(ids)
        templateCache.prune(ids)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        templateCache.clear()
        templates.clearCaches()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder =
        ChatMessageViewHolder(android.view.LayoutInflater.from(parent.context).inflate(
            com.artemis.uisdk.R.layout.artemis_message_row, parent, false
        ) as LinearLayout)

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        val message = getItem(position)
        val attachedRecyclerView = recyclerView
        val availableRowWidth = ((attachedRecyclerView?.width?.takeIf { it > 0 } ?: holder.messageRowLayout.context.resources.displayMetrics.widthPixels) -
            (attachedRecyclerView?.paddingLeft ?: 0) - (attachedRecyclerView?.paddingRight ?: 0)).coerceAtLeast(1)
        val previous = holder.boundMessage
        if (previous == message && holder.boundWidth == availableRowWidth) return
        val reuseTemplates = previous != null && sameTemplatePresentation(previous, message)
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
            color(theme.userBubble, color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        } else {
            color(theme.assistantBubble, context.getColor(com.artemis.uisdk.R.color.artemis_assistant_bubble))
        }
        messageBubble.background = GradientDrawable().apply {
            setColor(bubbleColor)
            cornerRadius = theme.bubbleRadiusDp * context.resources.displayMetrics.density
        }
        val displayedMessageText = if (isUserMessage) message.metadata?.get("display_label")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() }?.asString ?: message.text
            else message.text
        val messageTextView = holder.textView
        messageTextView.apply {
            visibility = if (displayedMessageText.isBlank()) View.GONE else View.VISIBLE
            setTextColor(if (isUserMessage) Color.WHITE else context.getColor(com.artemis.uisdk.R.color.artemis_text))
            textSize = if (isUserMessage) 14f else 16f
            setLineSpacing(0f, 1.25f)
            text = if (!isUserMessage && features.markdown && message.metadata?.string("contentFormat") != "plain_text") templates.parseMarkdown(context, displayedMessageText) else displayedMessageText
            movementMethod = if (!isUserMessage && features.markdown && message.metadata?.string("contentFormat") != "plain_text") LinkMovementMethod.getInstance() else null
            setLinkTextColor(if (isUserMessage) Color.WHITE else color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        }
        // Constrain to the actual RecyclerView width, including embedded/split-screen hosts.
        val containsRichContent = message.richContent != null || message.actions != null || message.renderables != null ||
            message.attachments.isNotEmpty() || message.agentLink != null || message.transferSurvey != null
        val isStandaloneRichTemplate = containsRichContent && displayedMessageText.isBlank() && !isUserMessage
        if (isStandaloneRichTemplate) messageBubble.background = null
        messageBubble.setPadding(
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
            if (isStandaloneRichTemplate) 0 else (12 * context.resources.displayMetrics.density).toInt(),
        )
        val maximumBubbleWidth = (availableRowWidth * if (isUserMessage) .82f else .94f).toInt()
        messageTextView.maxWidth = (maximumBubbleWidth - messageBubble.paddingLeft - messageBubble.paddingRight).coerceAtLeast(1)
        messageBubble.layoutParams = (messageBubble.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (containsRichContent) maximumBubbleWidth else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (!reuseTemplates) {
            val cached = templateCache.take(message)
            if (cached != null) cached.forEach(templateContainer::addView)
            else {
                contactCenter.render(context, message, templateContainer)
                message.richContent?.let { richContent -> templates.renderRich(context, holder, message, templateContainer, richContent) }
                templates.renderRenderables(context, holder, message, templateContainer)
                if (features.citations && message.role == MessageRole.ASSISTANT) message.citations.forEach { citation ->
                    val url = TemplateContracts.actionUrl(citation.url) ?: return@forEach
                    templateContainer.addView(TextView(context).apply {
                        text = "[${citation.index}] ${citation.title}"
                        setTextColor(color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary)))
                        setPadding(4, 8, 4, 8)
                        setOnClickListener { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
                    })
                }
                if (message.messageSource?.string("system") == "integration") templateContainer.addView(TextView(context).apply {
                    text = message.messageSource.string("provider") ?: "Support agent"
                    textSize = 12f
                    setTextColor(context.getColor(com.artemis.uisdk.R.color.artemis_muted))
                })
                message.actions?.let { messageActions -> templates.renderActions(context, message, templateContainer, messageActions) }
            }
        }
        holder.timestampView.apply {
            visibility = if (features.timestamps && message.messageStatus != MessageStatus.STREAMING && message.role != MessageRole.SYSTEM) View.VISIBLE else View.GONE
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
        }
        holder.boundMessage = message
        holder.boundWidth = availableRowWidth
    }

    private fun retainTemplates(holder: ChatMessageViewHolder) {
        val container = holder.templateContainer
        val views = (0 until container.childCount).map(container::getChildAt)
        container.removeAllViews()
        if (recyclerView != null) holder.boundMessage?.let { templateCache.retain(it, views) }
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
    override fun onViewDetachedFromWindow(holder: ChatMessageViewHolder) { holder.releaseMediaPlayer(); super.onViewDetachedFromWindow(holder) }

    private fun color(value: String?, fallback: Int) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        private val CHAT_MESSAGE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.messageId == newItem.messageId
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
        }
    }
}
