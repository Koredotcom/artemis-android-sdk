package com.artemis.uisdk.chat

import com.google.gson.JsonObject
import com.google.gson.JsonArray

enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MessageStatus { PENDING, ACCEPTED, FAILED, STREAMING, COMPLETE, CANCELLED, }

data class Citation(val index: Int, val title: String, val url: String, val sourceType: String = "")

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
    val pendingRender: Boolean = false,
    val citations: List<Citation> = emptyList(),
    val messageSource: JsonObject? = null,
    val renderables: JsonArray? = null,
    val contentEnvelope: JsonObject? = null,
    val sequence: Long? = null,
    val presentationVersion: Int = 0,
    val attachments: List<AttachmentRef> = emptyList(),
    val agentLink: AgentLink? = null,
    val transferSurvey: AgentTransferSurvey? = null,
    val feedbackResponses: Map<String, FeedbackResponseState> = emptyMap(),
    val authorization: AuthorizationCard? = null,

)

enum class AttachmentDraftStatus { QUEUED, UPLOADING, READY, FAILED, CANCELLED }

data class AttachmentDraft(
    val localId: String,
    val attachment: AttachmentRef = AttachmentRef(""),
    val status: AttachmentDraftStatus = AttachmentDraftStatus.QUEUED,
    val progress: Int? = null,
    val errorMessage: String? = null,
)

data class ChatUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val isOffline: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val errorMessage: String? = null,
    val newMessageCount: Int = 0,
    val activityText: String? = null,
    val sessionId: String? = null,
    val initialAssistantMessagePending: Boolean = false,
    val agentTransferStatus: AgentTransferStatus = AgentTransferStatus.NONE,
    val agentTransferSessionId: String? = null,
    val attachmentUploading: Boolean = false,
    val attachmentUploadProgress: Int? = null,
    val attachmentFilename: String? = null,
    val attachmentUploadFailed: Boolean = false,
    val attachmentsUnavailable: Boolean = false,
    val pendingAttachment: AttachmentRef? = null,
    val attachmentDrafts: List<AttachmentDraft> = emptyList(),
    val attachmentBrandingEnabled: Boolean = false,
    val attachmentWritePermitted: Boolean = false,

)

enum class ConnectionStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
