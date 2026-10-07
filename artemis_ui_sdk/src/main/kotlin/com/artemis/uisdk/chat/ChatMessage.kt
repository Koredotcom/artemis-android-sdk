package com.artemis.uisdk.chat

import com.google.gson.JsonObject

enum class MessageRole { USER, ASSISTANT }
enum class MessageStatus { PENDING, ACCEPTED, FAILED, STREAMING, COMPLETE, }

data class ChatMessage(
    val messageId: String,
    val serverMessageId: String? = null,
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: JsonObject? = null,
    val richContent: JsonObject? = null,
    val actions: JsonObject? = null,
    val messageStatus: MessageStatus = MessageStatus.COMPLETE,
)

data class ChatUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val isOffline: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val errorMessage: String? = null,
    val newMessageCount: Int = 0,
)

enum class ConnectionStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
