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
        fun isLocked(message: ChatMessage, actionIdentifier: String): Boolean
    }

    class ChatMessageViewHolder(val messageRowLayout: LinearLayout) : RecyclerView.ViewHolder(messageRowLayout) {
        var mediaPlayer: MediaPlayer? = null
        fun releaseMediaPlayer() {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    private val templates = DefaultRichTemplateRenderer(features, theme, callbacks)
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder =
        ChatMessageViewHolder(android.view.LayoutInflater.from(parent.context).inflate(
            com.artemis.uisdk.R.layout.artemis_message_row, parent, false
        ) as LinearLayout)

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        holder.releaseMediaPlayer()
        val message = getItem(position)
        val context = holder.messageRowLayout.context
        val isUserMessage = message.role == MessageRole.USER
        holder.messageRowLayout.gravity = if (isUserMessage) Gravity.END else Gravity.START
        val messageBubble = holder.messageRowLayout.findViewById<LinearLayout>(com.artemis.uisdk.R.id.artemis_bubble)
        val templateContainer = holder.messageRowLayout.findViewById<LinearLayout>(com.artemis.uisdk.R.id.artemis_message_templates)
        templateContainer.removeAllViews()
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
        val messageTextView = holder.messageRowLayout.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_message_text)
        messageTextView.apply {
            visibility = if (displayedMessageText.isBlank()) View.GONE else View.VISIBLE
            setTextColor(if (isUserMessage) Color.WHITE else context.getColor(com.artemis.uisdk.R.color.artemis_text))
            textSize = if (isUserMessage) 14f else 16f
            setLineSpacing(0f, 1.25f)
            text = if (!isUserMessage && features.markdown) templates.parseMarkdown(context, displayedMessageText) else displayedMessageText
            movementMethod = if (!isUserMessage && features.markdown) LinkMovementMethod.getInstance() else null
            setLinkTextColor(if (isUserMessage) Color.WHITE else color(theme.primary, context.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        }
        // Constrain to the actual RecyclerView width, including embedded/split-screen hosts.
        val attachedRecyclerView = recyclerView
        val availableRowWidth = ((attachedRecyclerView?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) -
            (attachedRecyclerView?.paddingLeft ?: 0) - (attachedRecyclerView?.paddingRight ?: 0)).coerceAtLeast(1)
        val containsRichContent = message.richContent != null || message.actions != null
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
        message.richContent?.let { richContent -> templates.renderRich(context, holder, message, templateContainer, richContent) }
        message.actions?.let { messageActions -> templates.renderActions(context, message, templateContainer, messageActions) }
        holder.messageRowLayout.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_timestamp).apply {
            visibility = if (features.timestamps) View.VISIBLE else View.GONE
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
        }
    }

    override fun onViewRecycled(holder: ChatMessageViewHolder) { holder.releaseMediaPlayer(); super.onViewRecycled(holder) }
    override fun onViewDetachedFromWindow(holder: ChatMessageViewHolder) { holder.releaseMediaPlayer(); super.onViewDetachedFromWindow(holder) }

    private fun color(value: String?, fallback: Int) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        private val CHAT_MESSAGE_DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.messageId == newItem.messageId
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
        }
    }
}
