package com.artemis.uisdk

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.artemis.uisdk.chat.ArtemisChatActivity
import com.artemis.uisdk.chat.ArtemisChatFragment
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Entry point for initializing the embedded Artemis chat experience. */
object ArtemisUI {
    private val handles = ConcurrentHashMap<String, ArtemisUiHandle>()

    @JvmStatic
    fun initialize(context: Context, config: ArtemisUIConfig): ArtemisUiHandle {
        val handle = ArtemisUiHandle(context.applicationContext, config)
        handles[handle.id] = handle
        return handle
    }

    /** Load configuration from a host-owned asset before initializing. */
    @JvmStatic
    fun initializeFromAssets(
        context: Context,
        assetPath: String = "sdk_configurations.yaml",
        environment: String? = null,
    ): ArtemisUiHandle = initialize(context, ArtemisConfigLoader.load(context, assetPath, environment))

    internal fun resolve(id: String): ArtemisUiHandle? = handles[id]
    internal fun remove(handle: ArtemisUiHandle) { handles.remove(handle.id, handle) }
}

/** Application-scoped configuration handle. A single chat may be active per handle. */
class ArtemisUiHandle internal constructor(
    internal val appContext: Context,
    val config: ArtemisUIConfig,
) : AutoCloseable {
    internal val id: String = UUID.randomUUID().toString()
    private val listeners = CopyOnWriteArrayList<ArtemisUiListener>()
    @Volatile internal var chatOpen = false
    @Volatile private var closed = false
    @Volatile internal var shutdownSession: ((String) -> Unit)? = null

    fun showChat(activity: FragmentActivity, options: ChatOptions = ChatOptions()) {
        checkAvailable()
        check(!chatOpen) { "A chat session is already open for this handle" }
        chatOpen = true
        try {
            activity.startActivity(Intent(activity, ArtemisChatActivity::class.java).apply {
                putExtra(ArtemisChatActivity.EXTRA_HANDLE_ID, id)
                putExtra(ArtemisChatActivity.EXTRA_TITLE, options.title)
            })
        } catch (error: RuntimeException) {
            chatOpen = false
            throw error
        }
    }

    fun createChatFragment(options: ChatOptions = ChatOptions()): Fragment {
        checkAvailable()
        check(!chatOpen) { "A chat session is already open for this handle" }
        chatOpen = true
        return ArtemisChatFragment.create(id, options.title)
    }

    fun addListener(listener: ArtemisUiListener): AutoCloseable {
        checkAvailable()
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun emit(event: ArtemisUiEvent) {
        listeners.forEach { listener -> runCatching { listener.onEvent(event) } }
    }

    internal fun finishChat(reason: String) {
        chatOpen = false
        shutdownSession = null
        emit(ArtemisUiEvent.Closed(reason))
    }

    private fun checkAvailable() { check(!closed) { "Artemis UI handle is closed" } }
    internal fun isClosed() = closed

    override fun close() {
        if (closed) return
        val shutdown = shutdownSession
        if (shutdown != null) shutdown("HANDLE_CLOSED")
        else {
            chatOpen = false
            emit(ArtemisUiEvent.Closed("HANDLE_CLOSED"))
        }
        closed = true
        shutdownSession = null
        listeners.clear()
        ArtemisUI.remove(this)
    }
}

data class ChatOptions(val title: String = "Chat")

fun interface ArtemisUiListener { fun onEvent(event: ArtemisUiEvent) }

sealed interface ArtemisUiEvent {
    data class ConnectionChanged(val status: String, val detail: String? = null) : ArtemisUiEvent
    data class MessageChanged(val messageId: String, val role: String) : ArtemisUiEvent
    data class Error(val code: String, val message: String, val recoverable: Boolean) : ArtemisUiEvent
    data class Closed(val reason: String) : ArtemisUiEvent
}
