package com.artemis.uisdk

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.artemis.uisdk.chat.ArtemisChatActivity
import com.artemis.uisdk.chat.ArtemisChatFragment
import java.util.UUID
import com.artemis.uisdk.chat.ArtemisChatViewModel
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Entry point for initializing the embedded Artemis chat experience. */
object ArtemisUI {
    private val activeUiHandles = ConcurrentHashMap<String, ArtemisUiHandle>()

    @JvmStatic
    fun initialize(context: Context, config: ArtemisUIConfig): ArtemisUiHandle {
        val initializedUiHandle = ArtemisUiHandle(context.applicationContext, config)
        activeUiHandles[initializedUiHandle.handleIdentifier] = initializedUiHandle
        return initializedUiHandle
    }

    /** Load configuration from a host-owned asset before initializing. */
    @JvmStatic
    fun initializeFromAssets(
        context: Context,
        assetPath: String = "sdk_configurations.yaml",
        environment: String? = null,
    ): ArtemisUiHandle = initialize(context, ArtemisConfigLoader.load(context, assetPath, environment))

    internal fun resolveHandle(handleIdentifier: String): ArtemisUiHandle? = activeUiHandles[handleIdentifier]
    internal fun unregisterHandle(uiHandle: ArtemisUiHandle) {
        activeUiHandles.remove(uiHandle.handleIdentifier, uiHandle)
    }
}

/** Application-scoped configuration handle. A single chat may be active per handle. */
class ArtemisUiHandle internal constructor(
    internal val appContext: Context,
    val config: ArtemisUIConfig,
) : AutoCloseable {
    internal val handleIdentifier: String = UUID.randomUUID().toString()
    private val eventListeners = CopyOnWriteArrayList<ArtemisUiListener>()
    @Volatile internal var isChatOpen = false
    internal var isMinimized = false
    internal var chatViewModel: ArtemisChatViewModel? = null
    // Opt-in cached identity is scoped by endpoint, project, channel, key hash and user; credentials are not stored.
    private val sessionCache = if (config.connection.clientSessionIdentifier) appContext.getSharedPreferences("artemis.sdk.sessions", Context.MODE_PRIVATE) else null
    private val sessionCacheKey = run {
        val c = config.connection
        val scope = listOf(c.endpoint.trimEnd('/'), c.projectId, c.channelId ?: c.channelName ?: c.deploymentSlug, c.apiKey, c.userContext?.get("userId")?.toString()).joinToString("\u0000")
        java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    internal val clientSessionIdentifier: String = sessionCache?.getString(sessionCacheKey, null)?.takeIf { it.matches(Regex("csid_[a-f0-9]{32}")) }
        ?: ("csid_" + UUID.randomUUID().toString().replace("-", "")).also { sessionCache?.edit()?.putString(sessionCacheKey, it)?.apply() }
    internal val requestsNewSession: Boolean get() = sessionCache?.getBoolean("$sessionCacheKey:new", false) == true
    internal fun requestNewSession() { sessionCache?.edit()?.putBoolean("$sessionCacheKey:new", true)?.apply() }
    internal fun confirmSession() { sessionCache?.edit()?.remove("$sessionCacheKey:new")?.apply() }
    @Volatile private var isClosed = false
    @Volatile internal var chatSessionShutdownCallback: ((String) -> Unit)? = null

    fun showChat(activity: FragmentActivity, options: ChatOptions = ChatOptions()) {
        checkAvailable()
        check(!isChatOpen) { "A chat session is already open for this handle" }
        val previousMinimized = isMinimized
        isChatOpen = true
        isMinimized = false
        try {
            activity.startActivity(Intent(activity, ArtemisChatActivity::class.java).apply {
                putExtra(ArtemisChatActivity.EXTRA_HANDLE_IDENTIFIER, handleIdentifier)
                putExtra(ArtemisChatActivity.EXTRA_TITLE, options.title)
            })
        } catch (error: RuntimeException) {
            isChatOpen = false
            isMinimized = previousMinimized
            throw error
        }
    }

    fun createChatFragment(options: ChatOptions = ChatOptions()): Fragment {
        checkAvailable()
        check(!isChatOpen) { "A chat session is already open for this handle" }
        isChatOpen = true
        isMinimized = false
        return ArtemisChatFragment.create(handleIdentifier, options.title)
    }

    fun addListener(listener: ArtemisUiListener): AutoCloseable {
        checkAvailable()
        eventListeners.add(listener)
        return AutoCloseable { eventListeners.remove(listener) }
    }

    internal fun emit(event: ArtemisUiEvent) {
        eventListeners.forEach { listener -> runCatching { listener.onEvent(event) } }
    }

    internal fun minimizeChat() {
        isChatOpen = false
        isMinimized = true
        emit(ArtemisUiEvent.Minimized)
    }

    fun endSession() {
        checkAvailable()
        requestNewSession()
        chatSessionShutdownCallback?.invoke("END_SESSION")
    }

    internal fun finishChat(reason: String) {
        isMinimized = false
        chatViewModel = null
        isChatOpen = false
        chatSessionShutdownCallback = null
        emit(ArtemisUiEvent.Closed(reason))
    }

    private fun checkAvailable() { check(!isClosed) { "Artemis UI handle is closed" } }
    internal fun isClosed() = isClosed

    override fun close() {
        if (isClosed) return
        val shutdownChatSession = chatSessionShutdownCallback
        if (shutdownChatSession != null) shutdownChatSession("HANDLE_CLOSED")
        else {
            isChatOpen = false
            emit(ArtemisUiEvent.Closed("HANDLE_CLOSED"))
        }
        isClosed = true
        chatSessionShutdownCallback = null
        eventListeners.clear()
        ArtemisUI.unregisterHandle(this)
    }
}

data class ChatOptions(val title: String = "Chat")

fun interface ArtemisUiListener { fun onEvent(event: ArtemisUiEvent) }

sealed interface ArtemisUiEvent {
    data class ConnectionChanged(val status: String, val detail: String? = null) : ArtemisUiEvent
    data class MessageChanged(val messageId: String, val role: String) : ArtemisUiEvent
    data class Error(val code: String, val message: String, val recoverable: Boolean) : ArtemisUiEvent
    data class SessionStarted(val sessionId: String, val scope: JsonObject) : ArtemisUiEvent
    data class AttachmentUploaded(val attachmentId: String, val filename: String) : ArtemisUiEvent
    data class AgentTransferChanged(val status: String) : ArtemisUiEvent
    data class ResponseCancelled(val messageId: String?, val reason: String) : ArtemisUiEvent
    object Minimized : ArtemisUiEvent
    data class Closed(val reason: String) : ArtemisUiEvent
}
