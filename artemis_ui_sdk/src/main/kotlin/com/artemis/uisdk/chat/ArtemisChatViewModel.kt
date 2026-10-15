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

class ArtemisChatViewModel internal constructor(
    private val artemisUiHandle: ArtemisUiHandle,
    private val createSocketClient: (ArtemisSocketConfiguration, ArtemisSocketListener) -> ArtemisSocketClient,
) : ViewModel() {
    constructor(handle: ArtemisUiHandle) : this(handle, ::ArtemisSocketClient)

    private companion object {
        const val RESPONSE_LOG_TAG = "ArtemisUI"
        const val RESPONSE_LOG_CHUNK_SIZE = 3000
        const val ATTACHMENTS_DISABLED_MESSAGE = "Attachments are disabled for this chat. Remove the selected files to send text."
    }

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val gsonSerializer = Gson()
    private val mutableChatUiState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableChatUiState.asStateFlow()
    private val transcript = ChatTranscript(artemisUiHandle.config.features.contactCenter, artemisUiHandle.config.features.attachments)
    private val attachmentMetadataExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ownedAttachmentGrants = mutableSetOf<android.net.Uri>()
    private class AttachmentSelection(val uri: android.net.Uri, val sessionId: String, var draft: AttachmentDraft, var revision: Int = 0)
    private val attachmentSelections = linkedMapOf<String, AttachmentSelection>()
    @Volatile private var retainedAttachmentUris: Set<android.net.Uri> = emptySet()
    private var activeAttachmentId: String? = null
    private var activeAttachmentUpload: artemis.socket.ArtemisAttachmentUpload? = null
    private var historyTransferRevision = 0L
    private val surveyDrafts = linkedMapOf<String, SurveyDraft>()
    private val lockedActionKeys = mutableSetOf<String>()
    private var artemisSocketClient: ArtemisSocketClient? = null
    private var registeredConnectivityNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasStarted = false
    @Volatile private var isClosed = false

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
                .bootstrapToken(connectionConfig.bootstrapToken)
                .bootstrapTokenProvider(connectionConfig.bootstrapTokenProvider)
                .deploymentSlug(connectionConfig.deploymentSlug)
                .userContext(connectionConfig.userContext)
                .sessionMetadata(connectionConfig.sessionMetadata)
                .clientSessionIdentifier(if (connectionConfig.clientSessionIdentifier) artemisUiHandle.clientSessionIdentifier else null)
                .newSessionOnConnect(artemisUiHandle.requestsNewSession)
                .unlimitedTransientReconnect(artemisUiHandle.config.reconnection.unlimitedTransientRetries)
                .idleDisconnect(artemisUiHandle.config.session.idleDisconnectTimeoutMs, artemisUiHandle.config.session.endSessionOnIdle)
                .staleSessionRefresh(artemisUiHandle.config.session.staleSessionRefreshMs)
                .reconnection(
                    artemisUiHandle.config.reconnection.enabled,
                    artemisUiHandle.config.reconnection.maxAttempts,
                    artemisUiHandle.config.reconnection.baseDelayMs,
                    artemisUiHandle.config.reconnection.maxDelayMs,
                )
                .build()
            artemisSocketClient = createSocketClient(socketConfiguration, socketListener)
            setState(mutableChatUiState.value.copy(connectionStatus = ConnectionStatus.CONNECTING, errorMessage = null))
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTING"))
            artemisSocketClient?.connect()
        } catch (error: RuntimeException) {
            fail("CONFIGURATION", error.message ?: "Invalid connection configuration", false)
        }
    }

    @JvmOverloads
    fun sendText(text: String, metadata: JsonObject? = null, interactionContext: JsonObject? = null): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.codePointCount(0, value.length) > 10_000) return false
        val client = artemisSocketClient ?: return false
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return false
        val id = UUID.randomUUID().toString()
        try {
            client.sendMessage(value, id, metadata, interactionContext)
            transcript.acceptedUser(id, value, metadata)
            setState(transcript.state)
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(id, "user"))
            return true
        } catch (error: RuntimeException) {
            setState(mutableChatUiState.value.copy(errorMessage = "Message could not be sent. Retry when connected."))
            artemisUiHandle.emit(ArtemisUiEvent.Error("SEND_REJECTED", "Message could not be sent", true))
            return false
        }
    }

    fun surveyDraft(messageId: String): SurveyDraft = surveyDrafts[messageId] ?: SurveyDraft()
    fun updateSurveyDraft(messageId: String, draft: SurveyDraft) {
        surveyDrafts[messageId] = draft.copy(comments = draft.comments.take(4000))
        while (surveyDrafts.size > 500) surveyDrafts.remove(surveyDrafts.keys.first())
    }

    fun submitTransferSurvey(messageId: String, score: Int, comments: String?) {
        if (!contactCenterEnabled) return
        val message = state.value.messages.find { it.messageId == messageId } ?: return
        val survey = message.transferSurvey ?: return
        if (!survey.validScore(score) || score !in survey.choices || survey.status in setOf(SurveySubmissionStatus.PENDING, SurveySubmissionStatus.SUBMITTED)) return
        val client = artemisSocketClient ?: return
        if (!client.isConnected || state.value.isOffline) { contactCenterError("Reconnect to submit your feedback."); return }
        val session = state.value.sessionId
        updateMessage(messageId) { it.copy(transferSurvey = survey.copy(status = SurveySubmissionStatus.PENDING)) }
        try {
            client.submitAgentTransferSurvey(survey.artemisSessionId, survey.smartAssistSessionId, survey.surveyType, score,
                comments?.take(4000)?.takeIf { survey.showComment(score) }, object : ArtemisFeedbackCallback {
                    override fun onSuccess(feedbackId: String?) {
                        if (isClosed || state.value.sessionId != session) return
                        updateMessage(messageId) { it.copy(transferSurvey = survey.copy(status = SurveySubmissionStatus.SUBMITTED)) }
                        surveyDrafts.remove(messageId)
                    }
                    override fun onFailure(code: String, message: String) {
                        if (isClosed || state.value.sessionId != session) return
                        updateMessage(messageId) { it.copy(transferSurvey = survey.copy(status = SurveySubmissionStatus.FAILED)) }
                        artemisUiHandle.emit(ArtemisUiEvent.Error(code, "Survey could not be submitted. Please retry.", true))
                    }
                })
        } catch (error: RuntimeException) {
            updateMessage(messageId) { it.copy(transferSurvey = survey.copy(status = SurveySubmissionStatus.FAILED)) }
        }
    }

    val attachmentsEnabled: Boolean get() = artemisUiHandle.config.features.attachments &&
        state.value.attachmentBrandingEnabled && state.value.attachmentWritePermitted && !state.value.attachmentsUnavailable
    val contactCenterEnabled: Boolean get() = artemisUiHandle.config.features.contactCenter
    var composerDraft: String = ""; private set
    fun updateComposerDraft(text: String) { composerDraft = text }

    val maxAttachmentsPerMessage: Int get() = ArtemisSocketClient.MAX_ATTACHMENTS_PER_MESSAGE

    /** Retained for hosts selecting one document at a time. */
    fun uploadAttachment(uri: android.net.Uri) = uploadAttachments(listOf(uri))

    /** Reserve every slot before reading/uploading, including queued, failed and cancelled files. */
    fun uploadAttachments(uris: List<android.net.Uri>) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainThreadHandler.post { uploadAttachments(uris) }; return }
        val client = artemisSocketClient ?: return
        val session = state.value.sessionId ?: return
        if (isClosed || !attachmentsEnabled || !client.isConnected || state.value.isOffline) return
        val additions = uris.distinct().filter { uri -> attachmentSelections.values.none { it.uri == uri } }
        if (attachmentSelections.size + additions.size > maxAttachmentsPerMessage) {
            val message = artemisUiHandle.appContext.getString(com.artemis.uisdk.R.string.artemis_attachment_limit, maxAttachmentsPerMessage)
            setState(state.value.copy(errorMessage = message))
            artemisUiHandle.emit(ArtemisUiEvent.Error("ATTACHMENT_LIMIT", message, false))
            return
        }
        additions.forEach { uri ->
            val id = UUID.randomUUID().toString()
            attachmentSelections[id] = AttachmentSelection(uri, session, AttachmentDraft(id))
        }
        if (additions.isEmpty()) return
        setState(state.value.copy(errorMessage = null))
        publishAttachmentState()
        startNextAttachment()
    }

    private fun publishAttachmentState() {
        retainedAttachmentUris = attachmentSelections.values.map { it.uri }.toSet()
        val drafts = attachmentSelections.values.map { it.draft }
        val working = drafts.firstOrNull { it.status == AttachmentDraftStatus.UPLOADING }
        val ready = drafts.firstOrNull { it.status == AttachmentDraftStatus.READY }
        setState(state.value.copy(attachmentDrafts = drafts,
            attachmentUploading = drafts.any { it.status in setOf(AttachmentDraftStatus.QUEUED, AttachmentDraftStatus.UPLOADING) },
            attachmentUploadFailed = drafts.any { it.status in setOf(AttachmentDraftStatus.FAILED, AttachmentDraftStatus.CANCELLED) },
            attachmentUploadProgress = working?.progress ?: if (drafts.isNotEmpty() && drafts.all { it.status == AttachmentDraftStatus.READY }) 100 else null,
            attachmentFilename = (working ?: drafts.firstOrNull())?.attachment?.filename,
            pendingAttachment = ready?.attachment))
    }

    /** Upload sequentially; a batch never competes with the socket/credential worker. */
    private fun startNextAttachment() {
        if (isClosed || activeAttachmentId != null || !attachmentsEnabled || state.value.isOffline) return
        val client = artemisSocketClient?.takeIf { it.isConnected } ?: return
        val entry = attachmentSelections.entries.firstOrNull { it.value.draft.status == AttachmentDraftStatus.QUEUED } ?: return
        val id = entry.key
        val item = entry.value
        if (item.sessionId != state.value.sessionId) { clearAttachmentQueue(); publishAttachmentState(); return }
        val operation = ++item.revision
        activeAttachmentId = id
        item.draft = item.draft.copy(status = AttachmentDraftStatus.UPLOADING, progress = null, errorMessage = null)
        publishAttachmentState()
        attachmentMetadataExecutor.execute {
            val resolver = artemisUiHandle.appContext.contentResolver
            try {
                synchronized(ownedAttachmentGrants) {
                    if (!isClosed && item.uri in retainedAttachmentUris && resolver.persistedUriPermissions.none { it.uri == item.uri && it.isReadPermission }) {
                        runCatching { resolver.takePersistableUriPermission(item.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            .onSuccess { ownedAttachmentGrants.add(item.uri) }
                    }
                }
                var filename = "Attachment"; var size = -1L
                resolver.query(item.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) filename = cursor.getString(nameIndex).take(200).ifBlank { "Attachment" }
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                val mime = AttachmentMime.resolve(filename, resolver.getType(item.uri))
                val info = AttachmentRef("", filename, mime, size.coerceAtLeast(0))
                mainThreadHandler.post {
                    if (currentAttachment(id, item, operation)) { item.draft = item.draft.copy(attachment = info); publishAttachmentState() }
                }
                val policy = artemisUiHandle.config.attachment
                require(policy.allows(mime)) { "This file type is not allowed. Select a supported file." }
                require(size != 0L && size <= policy.maxFileSizeBytes && size >= -1) { "File is empty or exceeds the configured size limit." }
                mainThreadHandler.post {
                    if (!currentAttachment(id, item, operation)) { releaseAttachmentGrants(); return@post }
                    try {
                        activeAttachmentUpload = client.uploadAttachment(info.filename, info.mimeType, size, policy.maxFileSizeBytes,
                            { resolver.openInputStream(item.uri) }, object : artemis.socket.ArtemisAttachmentCallback {
                                override fun onProgress(uploadedBytes: Long, totalBytes: Long) {
                                    if (!currentAttachment(id, item, operation)) return
                                    val percent = if (totalBytes > 0) ((uploadedBytes * 100) / totalBytes).coerceIn(0, 100).toInt() else null
                                    if (percent != item.draft.progress) { item.draft = item.draft.copy(progress = percent); publishAttachmentState() }
                                }
                                override fun onSuccess(attachmentId: String, sessionId: String) {
                                    if (!currentAttachment(id, item, operation)) return
                                    if (sessionId != item.sessionId) { failAttachment(id, item, operation, "SESSION_CHANGED", "Conversation changed. Please attach the file again."); return }
                                    activeAttachmentUpload = null; activeAttachmentId = null
                                    item.draft = item.draft.copy(attachment = info.copy(id = attachmentId), status = AttachmentDraftStatus.READY, progress = 100)
                                    publishAttachmentState()
                                    artemisUiHandle.emit(ArtemisUiEvent.AttachmentUploaded(attachmentId, info.filename))
                                    startNextAttachment()
                                }
                                override fun onFailure(code: String, message: String) = failAttachment(id, item, operation, code, message)
                            })
                    } catch (error: RuntimeException) { failAttachment(id, item, operation, "UPLOAD_FAILED", "File could not be uploaded. Please retry.") }
                }
            } catch (error: Exception) {
                val message = if (error is IllegalArgumentException) error.message ?: "Invalid attachment" else "File could not be read. Please select it again."
                mainThreadHandler.post { failAttachment(id, item, operation, "UPLOAD_FAILED", message) }
            }
        }
    }

    private fun currentAttachment(id: String, item: AttachmentSelection, operation: Int) =
        !isClosed && attachmentSelections[id] === item && item.revision == operation && item.sessionId == state.value.sessionId

    private fun failAttachment(id: String, item: AttachmentSelection, operation: Int, code: String, message: String) {
        if (!currentAttachment(id, item, operation)) return
        activeAttachmentId = null; activeAttachmentUpload = null
        item.draft = item.draft.copy(status = AttachmentDraftStatus.FAILED, progress = null, errorMessage = message)
        if (code == "ATTACHMENTS_UNAVAILABLE") {
            attachmentSelections.values.filter { it.draft.status == AttachmentDraftStatus.QUEUED }.forEach {
                it.draft = it.draft.copy(status = AttachmentDraftStatus.FAILED, errorMessage = message)
            }
        }
        setState(state.value.copy(attachmentsUnavailable = state.value.attachmentsUnavailable || code == "ATTACHMENTS_UNAVAILABLE", errorMessage = message))
        publishAttachmentState()
        artemisUiHandle.emit(ArtemisUiEvent.Error(code, message, true))
        startNextAttachment()
    }

    fun retryAttachment() {
        attachmentSelections.values.filter { it.draft.status in setOf(AttachmentDraftStatus.FAILED, AttachmentDraftStatus.CANCELLED) }
            .map { it.draft.localId }.forEach(::retryAttachment)
    }
    fun retryAttachment(localId: String) {
        if (isClosed || !attachmentsEnabled || state.value.isOffline || artemisSocketClient?.isConnected != true) return
        val item = attachmentSelections[localId] ?: return
        if (item.draft.status !in setOf(AttachmentDraftStatus.FAILED, AttachmentDraftStatus.CANCELLED)) return
        item.draft = item.draft.copy(status = AttachmentDraftStatus.QUEUED, progress = null, errorMessage = null)
        setState(state.value.copy(errorMessage = null)); publishAttachmentState(); startNextAttachment()
    }
    fun cancelAttachmentUpload() {
        attachmentSelections.values.filter { it.draft.status in setOf(AttachmentDraftStatus.QUEUED, AttachmentDraftStatus.UPLOADING) }.forEach { item ->
            item.revision++
            item.draft = item.draft.copy(status = AttachmentDraftStatus.CANCELLED, progress = null, errorMessage = null)
        }
        activeAttachmentId = null; activeAttachmentUpload?.cancel(); activeAttachmentUpload = null
        publishAttachmentState()
    }
    fun cancelAttachmentUpload(localId: String) {
        val item = attachmentSelections[localId] ?: return
        if (item.draft.status !in setOf(AttachmentDraftStatus.QUEUED, AttachmentDraftStatus.UPLOADING)) return
        item.revision++
        if (activeAttachmentId == localId) { activeAttachmentId = null; activeAttachmentUpload?.cancel(); activeAttachmentUpload = null }
        item.draft = item.draft.copy(status = AttachmentDraftStatus.CANCELLED, progress = null, errorMessage = null)
        publishAttachmentState(); startNextAttachment()
    }
    fun removePendingAttachment() { clearAttachmentQueue(); setState(state.value.copy(errorMessage = null)); publishAttachmentState() }
    fun removePendingAttachment(localId: String) {
        val item = attachmentSelections.remove(localId) ?: return
        item.revision++
        if (activeAttachmentId == localId) { activeAttachmentId = null; activeAttachmentUpload?.cancel(); activeAttachmentUpload = null }
        publishAttachmentState(); releaseAttachmentGrants()
        setState(state.value.copy(errorMessage = null)); startNextAttachment()
    }
    private fun clearAttachmentQueue() {
        attachmentSelections.values.forEach { it.revision++ }
        attachmentSelections.clear(); retainedAttachmentUris = emptySet(); activeAttachmentId = null
        activeAttachmentUpload?.cancel(); activeAttachmentUpload = null
        releaseAttachmentGrants()
    }
    private fun releaseAttachmentGrants() {
        synchronized(ownedAttachmentGrants) {
            ownedAttachmentGrants.filter { it !in retainedAttachmentUris || isClosed }.forEach { uri ->
                runCatching { artemisUiHandle.appContext.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ownedAttachmentGrants.remove(uri)
            }
        }
    }

    fun sendComposedMessage(text: String): Boolean {
        val selected = attachmentSelections.values.toList()
        if (selected.isEmpty()) return sendText(text).also { if (it) composerDraft = "" }
        if (!attachmentsEnabled || selected.size > maxAttachmentsPerMessage || selected.any { it.draft.status != AttachmentDraftStatus.READY }) return false
        val value = text.trim()
        if (value.codePointCount(0, value.length) > 10_000) return false
        val client = artemisSocketClient ?: return false
        if (!client.isConnected || state.value.isOffline) return false
        if (selected.any { it.sessionId != state.value.sessionId }) { removePendingAttachment(); contactCenterError("Conversation changed. Please attach the files again."); return false }
        val attachments = selected.map { it.draft.attachment }
        val id = UUID.randomUUID().toString()
        val metadata = AttachmentMime.metadata(attachments)
        try {
            client.sendMessage(value, id, metadata, null, attachments.map { it.id })
            transcript.acceptedUser(id, value, metadata)
            setState(transcript.state)
            // Keep redacted text/metadata server-owned; refs carry no typed user text.
            updateMessage(id) { it.copy(attachments = attachments) }
            removePendingAttachment(); composerDraft = ""
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(id, "user"))
            return true
        } catch (error: RuntimeException) { contactCenterError("Message could not be sent. Your attachments are ready to retry."); return false }
    }

    fun openAttachment(attachment: AttachmentRef, onResolved: (String) -> Unit) {
        // Hiding the upload composer must not hide or disable already received files.
        if (!artemisUiHandle.config.features.attachments || attachment.deliveryFailed) return
        val owningSession = state.value.sessionId
        try {
            val client = artemisSocketClient ?: return
            client.resolveAttachmentDownloadUrl(attachment.id, object : artemis.socket.ArtemisAttachmentUrlCallback {
                override fun onSuccess(url: String) { if (!isClosed && state.value.sessionId == owningSession) onResolved(url) }
                override fun onFailure(code: String, message: String) { if (!isClosed && state.value.sessionId == owningSession) contactCenterError(message) }
            })
        } catch (error: RuntimeException) { contactCenterError("Reconnect to open this attachment.") }
    }

    fun contactCenterError(message: String) {
        setState(state.value.copy(errorMessage = message))
        artemisUiHandle.emit(ArtemisUiEvent.Error("CONTACT_CENTER", message, true))
    }

    fun markActivity() { artemisSocketClient?.markActivity() }

    fun sendPresence(typing: Boolean) { artemisSocketClient?.sendPresence(typing) }

    fun submitAction(
        messageId: String,
        actionIdentifier: String,
        actionValue: String?,
        formData: Map<String, String>?,
        renderIdentifier: String?,
    ) {
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return
        val sourceMessage = mutableChatUiState.value.messages.find { it.messageId == messageId }
        val isQuickReply = sourceMessage?.richContent?.array("quick_replies")?.any { it.isJsonObject && it.asJsonObject.string("id") == actionIdentifier } == true
        val actionLockKey = renderIdentifier?.let { "render:$it" } ?: "$messageId:${if (isQuickReply) "quick_replies" else actionIdentifier}"
        val displayLabel = actionDisplayLabel(sourceMessage, actionIdentifier)
        val displayId = if (displayLabel != null) UUID.randomUUID().toString() else null
        if (!lockedActionKeys.add(actionLockKey)) return
        try {
            artemisSocketClient?.submitAction(actionIdentifier, actionValue, formData, renderIdentifier, displayLabel, displayId)
            if (displayId != null && displayLabel != null) { transcript.acceptedUser(displayId, displayLabel); setState(transcript.state) }
            updateMessage(messageId) { message ->
                val actions = message.actions?.deepCopy()
                if (renderIdentifier != null) actions?.add("submitted", JsonObject().apply {
                    addProperty("actionId", actionIdentifier); addProperty("status", "submitted");
                    if (actionValue != null) addProperty("value", actionValue)
                })
                message.copy(actions = actions, presentationVersion = message.presentationVersion + 1)
            }
            setState(mutableChatUiState.value.copy(isTyping = true, errorMessage = null))
        } catch (error: RuntimeException) {
            lockedActionKeys.remove(actionLockKey)
            updateMessage(messageId) { it.copy(presentationVersion = it.presentationVersion + 1) }
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
                        updateMessage(messageId) { it.copy(presentationVersion = it.presentationVersion + 1) }
                    }
                    override fun onFailure(code: String, message: String) {
                        lockedActionKeys.remove(feedbackLockKey)
                        updateMessage(messageId) { it.copy(presentationVersion = it.presentationVersion + 1) }
                        setState(mutableChatUiState.value.copy(errorMessage = message))
                        artemisUiHandle.emit(ArtemisUiEvent.Error(code, message, true))
                    }
                })
        } catch (error: RuntimeException) {
            lockedActionKeys.remove(feedbackLockKey)
            updateMessage(messageId) { it.copy(presentationVersion = it.presentationVersion + 1) }
            setState(mutableChatUiState.value.copy(errorMessage = error.message ?: "Feedback could not be sent"))
        }
    }

    fun isActionLocked(messageId: String, actionIdentifier: String) =
        "$messageId:$actionIdentifier" in lockedActionKeys || mutableChatUiState.value.messages
            .find { it.messageId == messageId }?.actions?.let { actions ->
                actions.obj("submitted")?.string("status") in setOf("pending", "submitted") || actions.string("renderId")?.let { "render:$it" in lockedActionKeys } == true
            } == true

    private fun actionDisplayLabel(message: ChatMessage?, id: String): String? {
        message ?: return null
        val quickReply = message.richContent?.array("quick_replies")?.firstOrNull { it.isJsonObject && it.asJsonObject.string("id") == id }?.asJsonObject
        val buttons = message.actions?.array("elements")?.filter { it.isJsonObject && (it.asJsonObject.string("type") ?: "button") == "button" }.orEmpty() +
            message.richContent?.obj("carousel")?.array("cards")?.flatMap { it.takeIf { it.isJsonObject }?.asJsonObject?.array("buttons")?.toList().orEmpty() }.orEmpty()
        val button = buttons.firstOrNull { it.isJsonObject && it.asJsonObject.string("id") == id }?.asJsonObject
        return (quickReply ?: button)?.string("label")?.replace(Regex("[\\u202A-\\u202E\\u2066-\\u2069]"), "")?.trim()?.take(500)?.takeIf(String::isNotBlank)
    }

    fun retry() {
        if (isClosed) return
        if (artemisUiHandle.isClosed()) return
        val client = artemisSocketClient
        if (client != null) { client.disconnect(); client.connect(); return }
        hasStarted = false
        setState(mutableChatUiState.value.copy(connectionStatus = ConnectionStatus.CONNECTING, errorMessage = null))
        start()
    }

    fun closeSession(reason: String) {
        if (isClosed) return
        isClosed = true
        clearAttachmentQueue()
        attachmentMetadataExecutor.shutdownNow()
        surveyDrafts.clear()
        unregisterConnectivity()
        val client = artemisSocketClient
        if (reason == "END_SESSION" || artemisUiHandle.config.session.endSessionOnClose) {
            artemisUiHandle.requestNewSession()
            client?.endSession()
            mainThreadHandler.postDelayed({ client?.shutdown() }, 3_100)
        } else client?.shutdown()
        artemisSocketClient = null
        artemisUiHandle.finishChat(reason)
    }

    private fun applyAttachmentPolicy(scope: JsonObject) {
        if (isClosed) return
        val wasEnabled = attachmentsEnabled
        val setting = scope.obj("widgetConfig")?.obj("theme")?.get("attachmentButtonEnabled")
        val brandingEnabled = setting?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let {
            when { it.isBoolean -> it.asBoolean; it.isString -> it.asString.trim().equals("true", ignoreCase = true); else -> false }
        } ?: false
        val mayWrite = scope.array("permissions")?.any { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "attachment:write" } == true
        setState(state.value.copy(attachmentBrandingEnabled = brandingEnabled, attachmentWritePermitted = mayWrite))
        if (BuildConfig.DEBUG) Log.d("ArtemisAttachment", "BRANDING attachmentButtonEnabled=$brandingEnabled attachmentWritePermitted=$mayWrite")
        if (wasEnabled && !attachmentsEnabled && attachmentSelections.isNotEmpty()) {
            cancelAttachmentUpload()
            setState(state.value.copy(errorMessage = ATTACHMENTS_DISABLED_MESSAGE))
        }
        if (attachmentsEnabled) {
            if (state.value.errorMessage == ATTACHMENTS_DISABLED_MESSAGE) setState(state.value.copy(errorMessage = null))
            startNextAttachment()
        }
    }

    private val socketListener = object : ArtemisSocketListener {
        override fun onSessionScopeChanged(scope: JsonObject) { applyAttachmentPolicy(scope) }
        override fun onLog(message: String) {
            if (BuildConfig.DEBUG && message.startsWith("ATTACHMENT ")) Log.d("ArtemisAttachment", message)
        }
        override fun onConnecting() = update { chatUiState ->
            chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTING)
        }
        override fun onConnected(sessionId: String) {
            if (isClosed) return
            artemisUiHandle.confirmSession()
            applyAttachmentPolicy(artemisSocketClient?.sessionScope ?: JsonObject())
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTED,
                    errorMessage = if (chatUiState.attachmentDrafts.isNotEmpty() && !attachmentsEnabled) ATTACHMENTS_DISABLED_MESSAGE else null)
            }
            mainThreadHandler.post { startNextAttachment() }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTED"))
            artemisUiHandle.emit(ArtemisUiEvent.SessionStarted(sessionId, artemisSocketClient?.sessionScope ?: JsonObject()))
            if (artemisUiHandle.config.features.history || contactCenterEnabled) { historyTransferRevision = transcript.transferRevision; artemisSocketClient?.loadHistory() }
        }
        override fun onDisconnected(reason: String) {
            if (isClosed) return
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.DISCONNECTED, isTyping = false)
            }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("DISCONNECTED", reason))
        }
        override fun onMessage(payload: String) { if (!isClosed) parseFrame(payload) }
        override fun onHistory(sessionId: String, messagesJson: String) {
            if (isClosed || sessionId != mutableChatUiState.value.sessionId) return
            transcript.mergeHistory(ChatFrameParser.history(messagesJson), historyTransferRevision)
            setState(transcript.state)
        }
        override fun onError(error: Throwable) {
            if (isClosed) return
            val errorDescription = safeError(error)
            update { chatUiState -> chatUiState.copy(errorMessage = errorDescription) }
            artemisUiHandle.emit(ArtemisUiEvent.Error("CONNECTION", errorDescription, true))
        }
    }

    private fun parseFrame(payload: String) {
        val frame = ChatFrameParser.parse(payload) ?: return
        transcript.configureScope(artemisSocketClient?.sessionScope ?: JsonObject())
        if (frame is ChatFrame.Activity && !artemisUiHandle.config.features.activityUpdates) return
        val previousSession = transcript.state.sessionId
        transcript.apply(frame)
        if (frame is ChatFrame.Session && previousSession != transcript.state.sessionId) {
            lockedActionKeys.clear(); surveyDrafts.clear(); clearAttachmentQueue()
        }
        setState(transcript.state)
        if (frame is ChatFrame.End) {
            mutableChatUiState.value.messages.find { it.messageId == frame.message.messageId }?.let(::logBotResponse)
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(frame.message.messageId, "assistant"))
        }
        if (frame is ChatFrame.Transfer && contactCenterEnabled && transcript.state.sessionId != null) {
            frame.messages.forEach { artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(it.messageId, it.role.name.lowercase())) }
            artemisUiHandle.emit(ArtemisUiEvent.AgentTransferChanged(transcript.state.agentTransferStatus.name))
        }
        if (frame is ChatFrame.Cancel) artemisUiHandle.emit(ArtemisUiEvent.ResponseCancelled(frame.id, frame.reason))
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

    private fun updateMessage(messageId: String, transformMessage: (ChatMessage) -> ChatMessage) {
        setState(mutableChatUiState.value.copy(messages = mutableChatUiState.value.messages.map { chatMessage ->
            if (chatMessage.messageId == messageId) transformMessage(chatMessage) else chatMessage
        }))
    }
    private fun setState(newChatUiState: ChatUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            transcript.update(newChatUiState)
            mutableChatUiState.value = newChatUiState
        } else {
            mainThreadHandler.post { if (!isClosed) { transcript.update(newChatUiState); mutableChatUiState.value = newChatUiState } }
        }
    }
    private fun update(transformState: (ChatUiState) -> ChatUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!isClosed) setState(transformState(mutableChatUiState.value)) }
        else mainThreadHandler.post { if (!isClosed) setState(transformState(mutableChatUiState.value)) }
    }
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
                if (hasInternetConnection) mainThreadHandler.post { startNextAttachment() }
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
