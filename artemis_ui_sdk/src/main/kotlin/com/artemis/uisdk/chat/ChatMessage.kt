package com.artemis.uisdk.chat

import com.google.gson.JsonObject

enum class MessageRole { USER, ASSISTANT }
enum class MessageStatus { PENDING, ACCEPTED, FAILED, STREAMING, COMPLETE, INTERRUPTED }

data class ChatMessage(
    val id: String,
    val serverId: String? = null,
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: JsonObject? = null,
    val richContent: JsonObject? = null,
    val actions: JsonObject? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
)

data class ChatUiState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val offline: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val typing: Boolean = false,
    val error: String? = null,
    val newMessageCount: Int = 0,
)

enum class ConnectionStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
