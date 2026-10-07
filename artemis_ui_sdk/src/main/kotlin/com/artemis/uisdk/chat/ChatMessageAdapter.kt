package com.artemis.uisdk.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.setPadding
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import java.text.DateFormat
import java.util.Date

internal class ChatMessageAdapter(
    private val features: ArtemisFeatureConfig,
    private val theme: ArtemisThemeConfig,
    private val callbacks: Callbacks,
) : ListAdapter<ChatMessage, ChatMessageAdapter.Holder>(DIFF) {
    interface Callbacks {
        fun onAction(message: ChatMessage, id: String, value: String?, data: Map<String, String>?, renderId: String?)
        fun onFeedback(message: ChatMessage, type: String, rating: Int, text: String?)
        fun isLocked(message: ChatMessage, actionId: String): Boolean
    }

    class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        var player: MediaPlayer? = null
        fun release() { runCatching { player?.release() }; player = null }
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(android.view.LayoutInflater.from(parent.context).inflate(
            com.artemis.uisdk.R.layout.artemis_message_row, parent, false
        ) as LinearLayout)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.release()
        val message = getItem(position)
        val c = holder.root.context
        val user = message.role == MessageRole.USER
        holder.root.gravity = if (user) Gravity.END else Gravity.START
        val bubble = holder.root.findViewById<LinearLayout>(com.artemis.uisdk.R.id.artemis_bubble)
        val content = holder.root.findViewById<LinearLayout>(com.artemis.uisdk.R.id.artemis_message_templates)
        content.removeAllViews()
        val fill = if (user) color(theme.userBubble, color(theme.primary, c.getColor(com.artemis.uisdk.R.color.artemis_primary)))
            else color(theme.assistantBubble, c.getColor(com.artemis.uisdk.R.color.artemis_assistant_bubble))
        bubble.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = theme.bubbleRadiusDp * c.resources.displayMetrics.density
        }
        val shownText = if (user) message.metadata?.get("display_label")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() }?.asString ?: message.text
            else message.text
        val body = holder.root.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_message_text)
        body.apply {
            visibility = if (shownText.isBlank()) View.GONE else View.VISIBLE
            setTextColor(if (user) Color.WHITE else c.getColor(com.artemis.uisdk.R.color.artemis_text))
            textSize = if (user) 14f else 16f
            setLineSpacing(0f, 1.25f)
            text = if (!user && features.markdown) templates.parseMarkdown(c, shownText) else shownText
            movementMethod = if (!user && features.markdown) LinkMovementMethod.getInstance() else null
            setLinkTextColor(if (user) Color.WHITE else color(theme.primary, c.getColor(com.artemis.uisdk.R.color.artemis_primary)))
        }
        // Constrain to the actual RecyclerView width, including embedded/split-screen hosts.
        val recycler = recyclerView
        val available = ((recycler?.width?.takeIf { it > 0 } ?: c.resources.displayMetrics.widthPixels) -
            (recycler?.paddingLeft ?: 0) - (recycler?.paddingRight ?: 0)).coerceAtLeast(1)
        val rich = message.richContent != null || message.actions != null
        val standaloneTemplate = rich && shownText.isBlank() && !user
        if (standaloneTemplate) bubble.background = null
        bubble.setPadding(
            if (standaloneTemplate) 0 else (12 * c.resources.displayMetrics.density).toInt(),
            if (standaloneTemplate) 0 else (12 * c.resources.displayMetrics.density).toInt(),
            if (standaloneTemplate) 0 else (12 * c.resources.displayMetrics.density).toInt(),
            if (standaloneTemplate) 0 else (12 * c.resources.displayMetrics.density).toInt(),
        )
        val maximum = (available * if (user) .82f else .94f).toInt()
        body.maxWidth = (maximum - bubble.paddingLeft - bubble.paddingRight).coerceAtLeast(1)
        bubble.layoutParams = (bubble.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (rich) maximum else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        message.richContent?.let { templates.renderRich(c, holder, message, content, it) }
        message.actions?.let { templates.renderActions(c, message, content, it) }
        holder.root.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_timestamp).apply {
            visibility = if (features.timestamps) View.VISIBLE else View.GONE
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
        }
    }

    override fun onViewRecycled(holder: Holder) { holder.release(); super.onViewRecycled(holder) }
    override fun onViewDetachedFromWindow(holder: Holder) { holder.release(); super.onViewDetachedFromWindow(holder) }

    private fun color(value: String?, fallback: Int) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
        }
    }
}
