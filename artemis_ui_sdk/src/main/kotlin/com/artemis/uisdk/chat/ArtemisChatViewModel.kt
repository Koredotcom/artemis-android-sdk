package com.artemis.uisdk.chat

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.artemis.uisdk.BuildConfig
import artemis.socket.ArtemisFeedbackCallback
import artemis.socket.ArtemisSocketClient
import artemis.socket.ArtemisSocketConfiguration
import artemis.socket.ArtemisSocketListener
import com.artemis.uisdk.ArtemisUiEvent
import com.artemis.uisdk.ArtemisUiHandle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ArtemisChatViewModel(private val handle: ArtemisUiHandle) : ViewModel() {
    private companion object {
        const val RESPONSE_LOG_TAG = "ArtemisUI"
        const val RESPONSE_LOG_CHUNK_SIZE = 3000
    }

    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val lockedActions = mutableSetOf<String>()
    private var client: ArtemisSocketClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var closed = false

    init {
        handle.shutdownSession = ::closeSession
    }

    fun start() {
        if (started || closed) return
        started = true
        observeConnectivity()
        val connection = handle.config.connection
        try {
            val socketConfig = ArtemisSocketConfiguration.builder()
                .endpoint(connection.endpoint)
                .projectId(connection.projectId)
                .apiKey(connection.apiKey)
                .channelId(connection.channelId)
                .channelName(connection.channelName)
                .reconnection(
                    handle.config.reconnection.enabled,
                    handle.config.reconnection.maxAttempts,
                    handle.config.reconnection.baseDelayMs,
                    handle.config.reconnection.maxDelayMs,
                )
                .build()
            client = ArtemisSocketClient(socketConfig, socketListener)
            setState(_state.value.copy(status = ConnectionStatus.CONNECTING, error = null))
            handle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTING"))
            client?.connect()
        } catch (error: RuntimeException) {
            fail("CONFIGURATION", error.message ?: "Invalid connection configuration", false)
        }
    }

    fun sendText(text: String) {
        val content = text.trim()
        if (content.isEmpty() || content.codePointCount(0, content.length) > 10_000) return
        val active = client ?: return
        if (_state.value.status != ConnectionStatus.CONNECTED || _state.value.offline) return
        val id = UUID.randomUUID().toString()
        val local = ChatMessage(id = id, role = MessageRole.USER, text = content, status = MessageStatus.PENDING)
        append(local)
        try {
            active.sendMessage(content)
            updateMessage(id) { it.copy(status = MessageStatus.ACCEPTED) }
            setState(_state.value.copy(typing = true, error = null))
        } catch (error: RuntimeException) {
            updateMessage(id) { it.copy(status = MessageStatus.FAILED) }
            setState(_state.value.copy(error = error.message ?: "Message could not be sent"))
            handle.emit(ArtemisUiEvent.Error("SEND_REJECTED", "Message could not be sent", true))
        }
    }

    fun submitAction(messageId: String, actionId: String, value: String?, formData: Map<String, String>?, renderId: String?) {
        if (_state.value.status != ConnectionStatus.CONNECTED || _state.value.offline) return
        val key = "$messageId:$actionId"
        if (!lockedActions.add(key)) return
        try {
            client?.submitAction(actionId, value, formData, renderId)
            setState(_state.value.copy(typing = true, error = null))
        } catch (error: RuntimeException) {
            lockedActions.remove(key)
            setState(_state.value.copy(error = error.message ?: "Action could not be sent"))
            handle.emit(ArtemisUiEvent.Error("SEND_REJECTED", "Action could not be sent", true))
        }
    }

    fun submitFeedback(messageId: String, ratingType: String, ratingValue: Int, feedbackText: String?) {
        if (_state.value.status != ConnectionStatus.CONNECTED || _state.value.offline) return
        val key = "$messageId:feedback"
        if (!lockedActions.add(key)) return
        try {
            client?.submitFeedback(messageId, ratingType, ratingValue, feedbackText, null,
                object : ArtemisFeedbackCallback {
                    override fun onSuccess(feedbackId: String) {
                        lockedActions.add(key)
                    }
                    override fun onFailure(code: String, message: String) {
                        lockedActions.remove(key)
                        setState(_state.value.copy(error = message))
                        handle.emit(ArtemisUiEvent.Error(code, message, true))
                    }
                })
        } catch (error: RuntimeException) {
            lockedActions.remove(key)
            setState(_state.value.copy(error = error.message ?: "Feedback could not be sent"))
        }
    }

    fun isActionLocked(messageId: String, actionId: String) = "$messageId:$actionId" in lockedActions

    fun retry() {
        if (closed) return
        if (handle.isClosed()) return
        client?.shutdown()
        client = null
        started = false
        setState(_state.value.copy(status = ConnectionStatus.CONNECTING, error = null))
        start()
    }

    fun closeSession(reason: String) {
        if (closed) return
        closed = true
        unregisterConnectivity()
        client?.shutdown()
        client = null
        handle.finishChat(reason)
    }

    private val socketListener = object : ArtemisSocketListener {
        override fun onLog(message: String) = Unit
        override fun onConnecting() = update { it.copy(status = ConnectionStatus.CONNECTING) }
        override fun onConnected(sessionId: String) {
            update { it.copy(status = ConnectionStatus.CONNECTED, error = null) }
            handle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTED"))
        }
        override fun onDisconnected(reason: String) {
            update { it.copy(status = ConnectionStatus.DISCONNECTED, typing = false) }
            handle.emit(ArtemisUiEvent.ConnectionChanged("DISCONNECTED", reason))
        }
        override fun onMessage(payload: String) = parseFrame(payload)
        override fun onError(error: Throwable) {
            val detail = safeError(error)
            update { it.copy(error = detail) }
            handle.emit(ArtemisUiEvent.Error("CONNECTION", detail, true))
        }
    }

    private fun parseFrame(payload: String) {
        val frame = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return
        when (frame.get("type")?.asString) {
            "response_start" -> {
                val id = frame.get("messageId")?.asString?.takeIf(String::isNotBlank) ?: return
                if (_state.value.messages.none { it.id == id }) {
                    append(ChatMessage(id, serverId = id, role = MessageRole.ASSISTANT, text = "", status = MessageStatus.STREAMING))
                }
                setState(_state.value.copy(typing = true))
            }
            "response_chunk" -> {
                val id = frame.get("messageId")?.asString?.takeIf(String::isNotBlank) ?: return
                val chunk = listOf("chunk", "content", "text").firstNotNullOfOrNull { frame.get(it)?.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString } ?: return
                val old = _state.value.messages.firstOrNull { it.id == id }
                if (old == null) append(ChatMessage(id, serverId = id, role = MessageRole.ASSISTANT, text = chunk, status = MessageStatus.STREAMING))
                else updateMessage(id) { it.copy(text = it.text + chunk, status = MessageStatus.STREAMING) }
                setState(_state.value.copy(typing = true))
            }
            "response_end" -> finishAssistant(frame)
            "error" -> setState(_state.value.copy(typing = false, error = "The assistant could not complete the response"))
        }
    }

    private fun finishAssistant(frame: JsonObject) {
        val id = frame.get("messageId")?.asString?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val envelope = frame.getAsJsonObject("contentEnvelope")
        val text = listOf("fullText", "text", "content").firstNotNullOfOrNull { frame.get(it)?.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
            ?: envelope?.get("text")?.asString
            ?: envelope?.get("content")?.asString
            ?: _state.value.messages.firstOrNull { it.id == id }?.text.orEmpty()
        val rich = firstObject(frame, "richContent", "rich_content")
            ?: envelope?.let { firstObject(it, "richContent", "rich_content") }
        val metadata = frame.getAsJsonObject("metadata")?.deepCopy() ?: JsonObject()
        if (rich != null) metadata.add("richContent", rich)
        val actions = firstObject(frame, "actions") ?: envelope?.getAsJsonObject("actions")
        if (text.isBlank() && rich == null && actions == null) {
            removeMessage(id)
            setState(_state.value.copy(typing = false))
            return
        }
        val message = ChatMessage(id, serverId = frame.get("messageId")?.asString, role = MessageRole.ASSISTANT,
            text = text, metadata = metadata, richContent = rich, actions = actions, status = MessageStatus.COMPLETE)
        logBotResponse(message)
        if (_state.value.messages.any { it.id == id }) updateMessage(id) { message } else append(message)
        setState(_state.value.copy(typing = false, error = null))
        handle.emit(ArtemisUiEvent.MessageChanged(id, "assistant"))
    }

    /** Full response details can contain user data; keep verbose response logging out of release builds. */
    private fun logBotResponse(message: ChatMessage) {
        if (!BuildConfig.DEBUG) return
        val response = gson.toJson(JsonObject().apply {
            addProperty("messageId", message.id)
            addProperty("text", message.text)
            message.richContent?.let { add("richContent", it.deepCopy()) }
            message.actions?.let { add("actions", it.deepCopy()) }
        })
        var start = 0
        var part = 1
        while (start < response.length) {
            var end = (start + RESPONSE_LOG_CHUNK_SIZE).coerceAtMost(response.length)
            if (end < response.length && Character.isHighSurrogate(response[end - 1])) end--
            Log.d(RESPONSE_LOG_TAG, "Bot response [$part]: ${response.substring(start, end)}")
            start = end
            part++
        }
    }

    private fun firstObject(obj: JsonObject, vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }
    private fun append(message: ChatMessage) {
        setState(_state.value.copy(messages = (_state.value.messages + message).takeLast(500)))
        handle.emit(ArtemisUiEvent.MessageChanged(message.id, message.role.name.lowercase()))
    }
    private fun updateMessage(id: String, change: (ChatMessage) -> ChatMessage) {
        setState(_state.value.copy(messages = _state.value.messages.map { if (it.id == id) change(it) else it }))
    }
    private fun removeMessage(id: String) = setState(_state.value.copy(messages = _state.value.messages.filterNot { it.id == id }))
    private fun setState(value: ChatUiState) { if (Looper.myLooper() == Looper.getMainLooper()) _state.value = value else main.post { if (!closed) _state.value = value } }
    private fun update(transform: (ChatUiState) -> ChatUiState) = setState(transform(_state.value))
    private fun fail(code: String, message: String, recoverable: Boolean) {
        setState(_state.value.copy(status = ConnectionStatus.FAILED, error = message))
        handle.emit(ArtemisUiEvent.Error(code, message, recoverable))
    }
    private fun safeError(error: Throwable) = when (error) {
        is java.io.IOException -> "Connection problem. Check the network and retry."
        else -> "Connection failed. Check the chat configuration and retry."
    }

    private fun observeConnectivity() {
        val cm = handle.appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateOffline(cm)
            override fun onLost(network: Network) = updateOffline(cm)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateOffline(cm)
            private fun updateOffline(manager: ConnectivityManager) {
                val online = manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
                update { it.copy(offline = !online) }
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback) }
        val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        setState(_state.value.copy(offline = !online))
    }

    private fun unregisterConnectivity() {
        val callback = networkCallback ?: return
        runCatching { handle.appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    override fun onCleared() { closeSession("VIEWMODEL_CLEARED") }
}
