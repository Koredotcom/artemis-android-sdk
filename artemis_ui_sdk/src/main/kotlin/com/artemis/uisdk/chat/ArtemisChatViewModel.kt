package com.artemis.uisdk.chat

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import artemis.socket.ArtemisFeedbackCallback
import artemis.socket.ArtemisSocketClient
import artemis.socket.ArtemisSocketConfiguration
import artemis.socket.ArtemisSocketListener
import com.artemis.uisdk.ArtemisUiEvent
import com.artemis.uisdk.ArtemisUiHandle
import com.artemis.uisdk.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ArtemisChatViewModel(private val artemisUiHandle: ArtemisUiHandle) : ViewModel() {
    private companion object {
        const val RESPONSE_LOG_TAG = "ArtemisUI"
        const val RESPONSE_LOG_CHUNK_SIZE = 3000
    }

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val gsonSerializer = Gson()
    private val mutableChatUiState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableChatUiState.asStateFlow()
    private val lockedActionKeys = mutableSetOf<String>()
    private var artemisSocketClient: ArtemisSocketClient? = null
    private var registeredConnectivityNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasStarted = false
    private var isClosed = false

    init {
        artemisUiHandle.chatSessionShutdownCallback = ::closeSession
    }

    fun start() {
        if (hasStarted || isClosed) return
        hasStarted = true
        observeConnectivity()
        val connectionConfig = artemisUiHandle.config.connection
        try {
            val socketConfiguration = ArtemisSocketConfiguration.builder()
                .endpoint(connectionConfig.endpoint)
                .projectId(connectionConfig.projectId)
                .apiKey(connectionConfig.apiKey)
                .channelId(connectionConfig.channelId)
                .channelName(connectionConfig.channelName)
                .reconnection(
                    artemisUiHandle.config.reconnection.enabled,
                    artemisUiHandle.config.reconnection.maxAttempts,
                    artemisUiHandle.config.reconnection.baseDelayMs,
                    artemisUiHandle.config.reconnection.maxDelayMs,
                )
                .build()
            artemisSocketClient = ArtemisSocketClient(socketConfiguration, socketListener)
            setState(mutableChatUiState.value.copy(connectionStatus = ConnectionStatus.CONNECTING, errorMessage = null))
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTING"))
            artemisSocketClient?.connect()
        } catch (error: RuntimeException) {
            fail("CONFIGURATION", error.message ?: "Invalid connection configuration", false)
        }
    }

    fun sendText(text: String) {
        val trimmedMessageText = text.trim()
        if (trimmedMessageText.isEmpty() || trimmedMessageText.codePointCount(0, trimmedMessageText.length) > 10_000) return
        val activeSocketClient = artemisSocketClient ?: return
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return
        val localMessageId = UUID.randomUUID().toString()
        val pendingUserMessage = ChatMessage(
            messageId = localMessageId,
            role = MessageRole.USER,
            text = trimmedMessageText,
            messageStatus = MessageStatus.PENDING,
        )
        append(pendingUserMessage)
        try {
            activeSocketClient.sendMessage(trimmedMessageText)
            updateMessage(localMessageId) { chatMessage -> chatMessage.copy(messageStatus = MessageStatus.ACCEPTED) }
            setState(mutableChatUiState.value.copy(isTyping = true, errorMessage = null))
        } catch (error: RuntimeException) {
            updateMessage(localMessageId) { chatMessage -> chatMessage.copy(messageStatus = MessageStatus.FAILED) }
            setState(mutableChatUiState.value.copy(errorMessage = error.message ?: "Message could not be sent"))
            artemisUiHandle.emit(ArtemisUiEvent.Error("SEND_REJECTED", "Message could not be sent", true))
        }
    }

    fun submitAction(
        messageId: String,
        actionIdentifier: String,
        actionValue: String?,
        formData: Map<String, String>?,
        renderIdentifier: String?,
    ) {
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return
        val actionLockKey = "$messageId:$actionIdentifier"
        if (!lockedActionKeys.add(actionLockKey)) return
        try {
            artemisSocketClient?.submitAction(actionIdentifier, actionValue, formData, renderIdentifier)
            setState(mutableChatUiState.value.copy(isTyping = true, errorMessage = null))
        } catch (error: RuntimeException) {
            lockedActionKeys.remove(actionLockKey)
            setState(mutableChatUiState.value.copy(errorMessage = error.message ?: "Action could not be sent"))
            artemisUiHandle.emit(ArtemisUiEvent.Error("SEND_REJECTED", "Action could not be sent", true))
        }
    }

    fun submitFeedback(messageId: String, ratingType: String, ratingValue: Int, feedbackText: String?) {
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return
        val feedbackLockKey = "$messageId:feedback"
        if (!lockedActionKeys.add(feedbackLockKey)) return
        try {
            artemisSocketClient?.submitFeedback(messageId, ratingType, ratingValue, feedbackText, null,
                object : ArtemisFeedbackCallback {
                    override fun onSuccess(feedbackId: String) {
                        lockedActionKeys.add(feedbackLockKey)
                    }
                    override fun onFailure(code: String, message: String) {
                        lockedActionKeys.remove(feedbackLockKey)
                        setState(mutableChatUiState.value.copy(errorMessage = message))
                        artemisUiHandle.emit(ArtemisUiEvent.Error(code, message, true))
                    }
                })
        } catch (error: RuntimeException) {
            lockedActionKeys.remove(feedbackLockKey)
            setState(mutableChatUiState.value.copy(errorMessage = error.message ?: "Feedback could not be sent"))
        }
    }

    fun isActionLocked(messageId: String, actionIdentifier: String) =
        "$messageId:$actionIdentifier" in lockedActionKeys

    fun retry() {
        if (isClosed) return
        if (artemisUiHandle.isClosed()) return
        artemisSocketClient?.shutdown()
        artemisSocketClient = null
        hasStarted = false
        setState(mutableChatUiState.value.copy(connectionStatus = ConnectionStatus.CONNECTING, errorMessage = null))
        start()
    }

    fun closeSession(reason: String) {
        if (isClosed) return
        isClosed = true
        unregisterConnectivity()
        artemisSocketClient?.shutdown()
        artemisSocketClient = null
        artemisUiHandle.finishChat(reason)
    }

    private val socketListener = object : ArtemisSocketListener {
        override fun onLog(message: String) = Unit
        override fun onConnecting() = update { chatUiState ->
            chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTING)
        }
        override fun onConnected(sessionId: String) {
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTED, errorMessage = null)
            }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTED"))
        }
        override fun onDisconnected(reason: String) {
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.DISCONNECTED, isTyping = false)
            }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("DISCONNECTED", reason))
        }
        override fun onMessage(payload: String) = parseFrame(payload)
        override fun onError(error: Throwable) {
            val errorDescription = safeError(error)
            update { chatUiState -> chatUiState.copy(errorMessage = errorDescription) }
            artemisUiHandle.emit(ArtemisUiEvent.Error("CONNECTION", errorDescription, true))
        }
    }

    private fun parseFrame(payload: String) {
        val incomingMessageFrame = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return
        when (incomingMessageFrame.get("type")?.asString) {
            "response_start" -> {
                val serverMessageId = incomingMessageFrame.get("messageId")?.asString?.takeIf(String::isNotBlank) ?: return
                if (mutableChatUiState.value.messages.none { chatMessage -> chatMessage.messageId == serverMessageId }) {
                    append(ChatMessage(
                        messageId = serverMessageId,
                        serverMessageId = serverMessageId,
                        role = MessageRole.ASSISTANT,
                        text = "",
                        messageStatus = MessageStatus.STREAMING,
                    ))
                }
                setState(mutableChatUiState.value.copy(isTyping = true))
            }
            "response_chunk" -> {
                val serverMessageId = incomingMessageFrame.get("messageId")?.asString?.takeIf(String::isNotBlank) ?: return
                val responseChunk = listOf("chunk", "content", "text").firstNotNullOfOrNull { contentFieldName ->
                    incomingMessageFrame.get(contentFieldName)?.takeIf { jsonValue ->
                        jsonValue.isJsonPrimitive && jsonValue.asJsonPrimitive.isString
                    }?.asString
                } ?: return
                val existingAssistantMessage = mutableChatUiState.value.messages.firstOrNull { chatMessage ->
                    chatMessage.messageId == serverMessageId
                }
                if (existingAssistantMessage == null) {
                    append(ChatMessage(
                        messageId = serverMessageId,
                        serverMessageId = serverMessageId,
                        role = MessageRole.ASSISTANT,
                        text = responseChunk,
                        messageStatus = MessageStatus.STREAMING,
                    ))
                } else {
                    updateMessage(serverMessageId) { chatMessage ->
                        chatMessage.copy(text = chatMessage.text + responseChunk, messageStatus = MessageStatus.STREAMING)
                    }
                }
                setState(mutableChatUiState.value.copy(isTyping = true))
            }
            "response_end" -> finishAssistant(incomingMessageFrame)
            "error" -> setState(mutableChatUiState.value.copy(
                isTyping = false,
                errorMessage = "The assistant could not complete the response",
            ))
        }
    }

    private fun finishAssistant(incomingMessageFrame: JsonObject) {
        val serverMessageId = incomingMessageFrame.get("messageId")?.asString?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        val contentEnvelope = incomingMessageFrame.getAsJsonObject("contentEnvelope")
        val assistantMessageText = listOf("fullText", "text", "content").firstNotNullOfOrNull { contentFieldName ->
            incomingMessageFrame.get(contentFieldName)?.takeIf { jsonValue ->
                jsonValue.isJsonPrimitive && jsonValue.asJsonPrimitive.isString
            }?.asString
        }
            ?: contentEnvelope?.get("text")?.asString
            ?: contentEnvelope?.get("content")?.asString
            ?: mutableChatUiState.value.messages.firstOrNull { chatMessage -> chatMessage.messageId == serverMessageId }?.text.orEmpty()
        val richContent = firstObject(incomingMessageFrame, "richContent", "rich_content")
            ?: contentEnvelope?.let { envelope -> firstObject(envelope, "richContent", "rich_content") }
        val messageMetadata = incomingMessageFrame.getAsJsonObject("metadata")?.deepCopy() ?: JsonObject()
        if (richContent != null) messageMetadata.add("richContent", richContent)
        val messageActions = firstObject(incomingMessageFrame, "actions") ?: contentEnvelope?.getAsJsonObject("actions")
        if (assistantMessageText.isBlank() && richContent == null && messageActions == null) {
            removeMessage(serverMessageId)
            setState(mutableChatUiState.value.copy(isTyping = false))
            return
        }
        val assistantMessage = ChatMessage(
            messageId = serverMessageId,
            serverMessageId = incomingMessageFrame.get("messageId")?.asString,
            role = MessageRole.ASSISTANT,
            text = assistantMessageText,
            metadata = messageMetadata,
            richContent = richContent,
            actions = messageActions,
            messageStatus = MessageStatus.COMPLETE,
        )
        logBotResponse(assistantMessage)
        if (mutableChatUiState.value.messages.any { chatMessage -> chatMessage.messageId == serverMessageId }) {
            updateMessage(serverMessageId) { assistantMessage }
        } else {
            append(assistantMessage)
        }
        setState(mutableChatUiState.value.copy(isTyping = false, errorMessage = null))
        artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(serverMessageId, "assistant"))
    }

    /** Full response details can contain user data; keep verbose response logging out of release builds. */
    private fun logBotResponse(assistantMessage: ChatMessage) {
        if (!BuildConfig.DEBUG) return
        val serializedResponse = gsonSerializer.toJson(JsonObject().apply {
            addProperty("messageId", assistantMessage.messageId)
            addProperty("text", assistantMessage.text)
            assistantMessage.richContent?.let { richContent -> add("richContent", richContent.deepCopy()) }
            assistantMessage.actions?.let { messageActions -> add("actions", messageActions.deepCopy()) }
        })
        var logStartIndex = 0
        var logPartNumber = 1
        while (logStartIndex < serializedResponse.length) {
            var logEndIndex = (logStartIndex + RESPONSE_LOG_CHUNK_SIZE).coerceAtMost(serializedResponse.length)
            if (logEndIndex < serializedResponse.length && Character.isHighSurrogate(serializedResponse[logEndIndex - 1])) logEndIndex--
            Log.d(RESPONSE_LOG_TAG, "Bot response [$logPartNumber]: ${serializedResponse.substring(logStartIndex, logEndIndex)}")
            logStartIndex = logEndIndex
            logPartNumber++
        }
    }

    private fun firstObject(jsonObject: JsonObject, vararg propertyNames: String): JsonObject? =
        propertyNames.firstNotNullOfOrNull { propertyName ->
        jsonObject.get(propertyName)?.takeIf { jsonValue -> jsonValue.isJsonObject }?.asJsonObject
    }
    private fun append(message: ChatMessage) {
        setState(mutableChatUiState.value.copy(messages = (mutableChatUiState.value.messages + message).takeLast(500)))
        artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(message.messageId, message.role.name.lowercase()))
    }
    private fun updateMessage(messageId: String, transformMessage: (ChatMessage) -> ChatMessage) {
        setState(mutableChatUiState.value.copy(messages = mutableChatUiState.value.messages.map { chatMessage ->
            if (chatMessage.messageId == messageId) transformMessage(chatMessage) else chatMessage
        }))
    }
    private fun removeMessage(messageId: String) = setState(mutableChatUiState.value.copy(
        messages = mutableChatUiState.value.messages.filterNot { chatMessage -> chatMessage.messageId == messageId },
    ))
    private fun setState(newChatUiState: ChatUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mutableChatUiState.value = newChatUiState
        } else {
            mainThreadHandler.post { if (!isClosed) mutableChatUiState.value = newChatUiState }
        }
    }
    private fun update(transformState: (ChatUiState) -> ChatUiState) = setState(transformState(mutableChatUiState.value))
    private fun fail(code: String, message: String, recoverable: Boolean) {
        setState(mutableChatUiState.value.copy(connectionStatus = ConnectionStatus.FAILED, errorMessage = message))
        artemisUiHandle.emit(ArtemisUiEvent.Error(code, message, recoverable))
    }
    private fun safeError(error: Throwable) = when (error) {
        is java.io.IOException -> "Connection problem. Check the network and retry."
        else -> "Connection failed. Check the chat configuration and retry."
    }

    private fun observeConnectivity() {
        val connectivityManager = artemisUiHandle.appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val connectivityNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateOfflineState(connectivityManager)
            override fun onLost(network: Network) = updateOfflineState(connectivityManager)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateOfflineState(connectivityManager)
            private fun updateOfflineState(connectivityManager: ConnectivityManager) {
                val hasInternetConnection = connectivityManager.activeNetwork?.let { activeNetwork ->
                    connectivityManager.getNetworkCapabilities(activeNetwork)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
                update { chatUiState -> chatUiState.copy(isOffline = !hasInternetConnection) }
            }
        }
        registeredConnectivityNetworkCallback = connectivityNetworkCallback
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                connectivityNetworkCallback,
            )
        }
        val hasInternetConnection = connectivityManager.activeNetwork?.let { activeNetwork ->
            connectivityManager.getNetworkCapabilities(activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        setState(mutableChatUiState.value.copy(isOffline = !hasInternetConnection))
    }

    private fun unregisterConnectivity() {
        val registeredNetworkCallback = registeredConnectivityNetworkCallback ?: return
        runCatching {
            artemisUiHandle.appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(registeredNetworkCallback)
        }
        registeredConnectivityNetworkCallback = null
    }

    override fun onCleared() {
        closeSession("VIEWMODEL_CLEARED")
    }
}
