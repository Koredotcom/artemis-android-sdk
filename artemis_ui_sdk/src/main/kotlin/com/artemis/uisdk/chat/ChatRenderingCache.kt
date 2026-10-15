package com.artemis.uisdk.chat

import android.view.View
import android.view.ViewGroup
import android.util.LruCache

internal fun ChatMessage.hasTemplatePresentation() = richContent != null || actions != null || renderables != null ||
    attachments.isNotEmpty() || agentLink != null || transferSurvey != null || citations.isNotEmpty() || messageSource?.string("system") == "integration"

/** Streaming text and timestamp updates do not invalidate unrelated template controls. */
internal fun sameTemplatePresentation(old: ChatMessage, new: ChatMessage): Boolean {
    if (old.messageId != new.messageId || old.serverMessageId != new.serverMessageId || old.role != new.role ||
        old.presentationVersion != new.presentationVersion || old.richContent != new.richContent ||
        old.actions != new.actions || old.renderables != new.renderables || old.citations != new.citations ||
        old.messageSource != new.messageSource || old.attachments != new.attachments || old.agentLink != new.agentLink || old.transferSurvey != new.transferSurvey) return false
    val markdown = old.richContent?.string("markdown")
    if (markdown != null && (markdown.trim() == old.text.trim()) != (markdown.trim() == new.text.trim())) return false
    // Unknown renderables may omit fallback text when it duplicates the bubble.
    return old.renderables == null || old.text == new.text
}

internal class TemplateViewCache {
    data class Entry(val message: ChatMessage, val views: List<View>, val nodeCount: Int)
    private val cache = object : LruCache<String, Entry>(1000) {
        override fun sizeOf(key: String, value: Entry) = value.nodeCount
    }
    fun retain(message: ChatMessage, views: List<View>) {
        if (!message.hasTemplatePresentation() || views.isEmpty() || hasMedia(message)) return
        val nodes = views.sumOf(::countNodes)
        if (nodes > 1000) return
        cache.put(message.messageId, Entry(message, views, nodes))
        // Bound both view count and retained message models/closures.
        while (cache.snapshot().size > 12) cache.remove(cache.snapshot().keys.first())
    }
    fun take(message: ChatMessage): List<View>? {
        val entry = cache.remove(message.messageId) ?: return null
        return entry.views.takeIf { sameTemplatePresentation(entry.message, message) }
    }
    fun prune(ids: Set<String>) { cache.snapshot().keys.filterNot(ids::contains).forEach(cache::remove) }
    fun clear() = cache.evictAll()
    internal fun retainedNodeCount() = cache.size()
    private fun countNodes(view: View): Int = 1 + if (view is ViewGroup) (0 until view.childCount).sumOf { countNodes(view.getChildAt(it)) } else 0
    private fun hasMedia(message: ChatMessage): Boolean = message.richContent?.let { it.has("audio") || it.has("video") } == true ||
        message.renderables?.any { it.isJsonObject && it.asJsonObject.string("name") in setOf("audio", "video") } == true
}

/** Follow new content only when the user is at the bottom and no scroll gesture is active. */
internal class ChatScrollFollow {
    private var following = true
    private var scrolling = false
    val shouldFollow: Boolean get() = following && !scrolling
    fun scrollStateChanged(isIdle: Boolean, atBottom: Boolean) {
        scrolling = !isIdle
        if (isIdle) following = atBottom
    }
    fun userSentMessage() { following = true }
}
