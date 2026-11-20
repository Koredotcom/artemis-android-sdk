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
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ArtemisChatViewModel internal constructor(
    private val artemisUiHandle: ArtemisUiHandle,
    private val createSocketClient: (ArtemisSocketConfiguration, ArtemisSocketListener) -> ArtemisSocketClient,
) : ViewModel() {
    constructor(handle: ArtemisUiHandle) : this(handle, ::ArtemisSocketClient)

    private fun label(id: Int, vararg args: Any) = artemisUiHandle.text(id, *args)
    private var lastAttachmentPolicyError: String? = null
    private val attachmentsDisabledMessage get() = label(com.artemis.uisdk.R.string.artemis_attachments_disabled).also { lastAttachmentPolicyError = it }
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val mutableChatUiState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableChatUiState.asStateFlow()
    private val mutableInitializationComplete = MutableStateFlow(false)
    val initializationComplete: StateFlow<Boolean> = mutableInitializationComplete.asStateFlow()
    private val transcript = ChatTranscript(artemisUiHandle.config.features.contactCenter, artemisUiHandle.config.features.attachments, artemisUiHandle.config.features.authentication)
    private val authorizationRequests = mutableMapOf<String, com.artemis.uisdk.ArtemisAuthorizationRequest>()
    private val authorizationOperations = mutableMapOf<Pair<String, String>, String>()
    private val authorizationTimeouts = mutableMapOf<Pair<String, String>, Runnable>()
    private var browserAuthorization: com.artemis.uisdk.ArtemisAuthorizationRequest? = null
    private var authorizationBrowserWasAway = false
    private val attachmentMetadataExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ownedAttachmentGrants = mutableSetOf<android.net.Uri>()
    private class AttachmentSelection(val uri: android.net.Uri, val sessionId: String, var draft: AttachmentDraft, var revision: Int = 0)
    private val attachmentSelections = linkedMapOf<String, AttachmentSelection>()
    @Volatile private var retainedAttachmentUris: Set<android.net.Uri> = emptySet()
    private var activeAttachmentId: String? = null
    private var activeAttachmentUpload: artemis.socket.ArtemisAttachmentUpload? = null
    private var historyTransferRevision = 0L
    private var contactCenterSbcRequestId: String? = null
    private var contactCenterSbcDetails: AgentMediaSbcDetails? = null
    private val endedContactCenterCalls = linkedSetOf<String>()
    private val seenAgentTransferDeliveryIds = linkedSetOf<String>()
    private var seenAgentTransferDeliverySessionId: String? = null
    private val templateDrafts = linkedMapOf<String, Pair<JsonObject, Map<String, String>>>()
    private val feedbackDrafts = linkedMapOf<String, Pair<JsonObject, FeedbackDraft>>()
    private val feedbackOperations = mutableMapOf<String, String>()
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
                .userContext(artemisUiHandle.effectiveUserContext)
                .sessionMetadata(connectionConfig.sessionMetadata)
                .clientSessionIdentifier(if (connectionConfig.clientSessionIdentifier) artemisUiHandle.clientSessionIdentifier else null)
                .newSessionOnConnect(artemisUiHandle.requestsNewSession)
                .unlimitedTransientReconnect(artemisUiHandle.config.reconnection.unlimitedTransientRetries)
                .idleDisconnect(artemisUiHandle.config.session.idleDisconnectTimeoutMs, artemisUiHandle.config.session.endSessionOnIdle)
                .staleSessionRefresh(artemisUiHandle.config.session.staleSessionRefreshMs)
                .historyPagination(artemisUiHandle.config.history.pageSize, artemisUiHandle.config.history.maxPages)
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
            fail("CONFIGURATION", error.message ?: label(com.artemis.uisdk.R.string.artemis_invalid_configuration), false)
        }
    }

    fun updateCustomData(data: JsonObject) { artemisSocketClient?.updateCustomData(data.deepCopy()) }
    fun getCustomData(): JsonObject = artemisSocketClient?.customData ?: JsonObject()
    fun clearCustomData() { artemisSocketClient?.clearCustomData() }
    /** Clear local presentation only; server history remains available on later reload. */
    fun clearHistory() {
        artemisSocketClient?.invalidateHistoryLoads()
        transcript.clearMessages()
        setState(transcript.state)
        artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(emptyList()))
    }

    @JvmOverloads
    fun sendText(text: String, metadata: JsonObject? = null, interactionContext: JsonObject? = null): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.codePointCount(0, value.length) > 10_000) return false
        val client = artemisSocketClient ?: return false
        if (mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return false
        val id = UUID.randomUUID().toString()
        val previousActivityState = mutableChatUiState.value
        try {
            client.sendMessage(value, id, metadata, interactionContext)
            transcript.acceptedUser(id, value, metadata)
            setState(transcript.state)
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(id, "user"))
            transcript.state.messages.find { it.messageId == id }?.let { artemisUiHandle.emit(ArtemisUiEvent.Message(it)) }
            artemisUiHandle.emit(ArtemisUiEvent.MessageSent(id))
            emitLocalActivityTransitions(previousActivityState, mutableChatUiState.value)
            return true
        } catch (error: RuntimeException) {
            setState(mutableChatUiState.value.copy(errorMessage = label(com.artemis.uisdk.R.string.artemis_send_failed)))
            artemisUiHandle.emit(ArtemisUiEvent.Error("SEND_REJECTED", label(com.artemis.uisdk.R.string.artemis_message_rejected), true))
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
        if (!client.isConnected || state.value.isOffline) { contactCenterError(label(com.artemis.uisdk.R.string.artemis_feedback_reconnect)); return }
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
                        artemisUiHandle.emit(ArtemisUiEvent.Error(code, label(com.artemis.uisdk.R.string.artemis_survey_rejected), true))
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
            val message = label(com.artemis.uisdk.R.string.artemis_attachment_limit, maxAttachmentsPerMessage)
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
                require(policy.allows(mime)) { label(com.artemis.uisdk.R.string.artemis_file_type_rejected) }
                require(size != 0L && size <= policy.maxFileSizeBytes && size >= -1) { label(com.artemis.uisdk.R.string.artemis_file_size_rejected) }
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
                                    if (sessionId != item.sessionId) { failAttachment(id, item, operation, "SESSION_CHANGED", label(com.artemis.uisdk.R.string.artemis_attachment_session_changed)); return }
                                    activeAttachmentUpload = null; activeAttachmentId = null
                                    item.draft = item.draft.copy(attachment = info.copy(id = attachmentId), status = AttachmentDraftStatus.READY, progress = 100)
                                    publishAttachmentState()
                                    artemisUiHandle.emit(ArtemisUiEvent.AttachmentUploaded(attachmentId, info.filename))
                                    startNextAttachment()
                                }
                                override fun onFailure(code: String, message: String) = failAttachment(id, item, operation, code, message)
                            })
                    } catch (error: RuntimeException) { failAttachment(id, item, operation, "UPLOAD_FAILED", label(com.artemis.uisdk.R.string.artemis_upload_rejected)) }
                }
            } catch (error: Exception) {
                val message = if (error is IllegalArgumentException) error.message ?: label(com.artemis.uisdk.R.string.artemis_invalid_attachment) else label(com.artemis.uisdk.R.string.artemis_file_unreadable)
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
        artemisUiHandle.emit(ArtemisUiEvent.AttachmentError(item.draft.attachment.filename.takeIf(String::isNotBlank) ?: "Attachment", message))
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
        if (selected.any { it.sessionId != state.value.sessionId }) { removePendingAttachment(); contactCenterError(label(com.artemis.uisdk.R.string.artemis_attachments_session_changed)); return false }
        val attachments = selected.map { it.draft.attachment }
        val id = UUID.randomUUID().toString()
        val metadata = AttachmentMime.metadata(attachments)
        val previousActivityState = mutableChatUiState.value
        try {
            client.sendMessage(value, id, metadata, null, attachments.map { it.id })
            transcript.acceptedUser(id, value, metadata)
            setState(transcript.state)
            // Keep redacted text/metadata server-owned; refs carry no typed user text.
            updateMessage(id) { it.copy(attachments = attachments) }
            removePendingAttachment(); composerDraft = ""
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(id, "user"))
            transcript.state.messages.find { it.messageId == id }?.let { artemisUiHandle.emit(ArtemisUiEvent.Message(it)) }
            artemisUiHandle.emit(ArtemisUiEvent.MessageSent(id))
            emitLocalActivityTransitions(previousActivityState, mutableChatUiState.value)
            return true
        } catch (error: RuntimeException) { contactCenterError(label(com.artemis.uisdk.R.string.artemis_attachment_send_failed)); return false }
    }

    fun openAttachment(attachment: AttachmentRef, onResolved: (String) -> Unit) {
        // Hiding the upload composer must not hide or disable already received files.
        if (!artemisUiHandle.config.features.attachments || attachment.deliveryFailed) return
        val owningSession = state.value.sessionId
        try {
            val client = artemisSocketClient ?: return
            client.resolveAttachmentDownloadUrl(attachment.id, object : artemis.socket.ArtemisAttachmentUrlCallback {
                override fun onSuccess(url: String) { if (!isClosed && state.value.sessionId == owningSession) onResolved(url) }
                override fun onFailure(code: String, message: String) {
                    if (!isClosed && state.value.sessionId == owningSession) {
                        artemisUiHandle.emit(ArtemisUiEvent.AttachmentError(attachment.filename, message))
                        contactCenterError(message)
                    }
                }
            })
        } catch (error: RuntimeException) {
            val message = label(com.artemis.uisdk.R.string.artemis_attachment_reconnect)
            artemisUiHandle.emit(ArtemisUiEvent.AttachmentError(attachment.filename, message))
            contactCenterError(message)
        }
    }

    fun contactCenterError(message: String) {
        setState(state.value.copy(errorMessage = message))
        artemisUiHandle.emit(ArtemisUiEvent.Error("CONTACT_CENTER", message, true))
    }

    /** Sends the iOS/Web SDK Contact Center call control event after an invite decision. */
    fun respondToAgentMediaInvite(invite: AgentMediaInvite, accepted: Boolean): Boolean {
        val client = artemisSocketClient
        if (client == null || !client.isConnected || state.value.isOffline) {
            contactCenterCallError(label(com.artemis.uisdk.R.string.artemis_call_response_failed))
            return false
        }
        val decision = if (accepted) "accepted" else "rejected"
        val callId = invite.callId ?: invite.mediaSessionId ?: invite.conversationId ?: return false
        val mediaSessionId = invite.mediaSessionId ?: callId
        return try {
            client.respondToContactCenterCall(
                decision,
                callId,
                mediaSessionId,
                invite.callType,
                invite.conversationId,
                if (accepted) null else "user_rejected",
            )
            if (!accepted) {
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterCallRejected(callId, "user_rejected"))
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("ended", "user_rejected", callId))
            }
            true
        } catch (_: RuntimeException) {
            contactCenterCallError(label(com.artemis.uisdk.R.string.artemis_call_response_failed))
            false
        }
    }

    private fun contactCenterCallError(message: String) {
        setState(state.value.copy(errorMessage = message))
        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("error", message))
        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterError(message))
        artemisUiHandle.emit(ArtemisUiEvent.Error("CONTACT_CENTER", message, true))
    }

    fun terminateAgentMediaCall(endpoint: AgentMediaEndpoint, reason: String): Boolean {
        val client = artemisSocketClient ?: return false
        if (!client.isConnected || state.value.isOffline) return false
        val sent = runCatching {
            client.respondToContactCenterCall(
                "terminated",
                endpoint.callId,
                endpoint.mediaSessionId,
                endpoint.callType,
                endpoint.conversationId,
                reason,
            )
        }.isSuccess
        if (sent && endedContactCenterCalls.add(endpoint.callId)) {
            while (endedContactCenterCalls.size > 100) endedContactCenterCalls.remove(endedContactCenterCalls.first())
            artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("ended", reason, endpoint.callId))
            artemisUiHandle.emit(ArtemisUiEvent.ContactCenterCallEnded(endpoint.callId, reason))
        }
        return sent
    }

    fun currentContactCenterSbcDetails(): AgentMediaSbcDetails? = contactCenterSbcDetails

    fun markActivity() { artemisSocketClient?.markActivity() }

    private fun authorizationRow(messageId: String, rowId: String): Pair<AuthorizationCard, AuthorizationRow>? {
        if (isClosed || artemisUiHandle.isClosed() || !artemisUiHandle.config.features.authentication) return null
        val card = state.value.messages.find { it.messageId == messageId }?.authorization
            ?.takeIf { it.active && it.sessionId == state.value.sessionId } ?: return null
        return card.rows.find { it.id == rowId }?.let { card to it }
    }

    private fun authorizationStatus(messageId: String, rowId: String, status: AuthorizationStatus, attempted: Boolean = true) {
        updateMessage(messageId) { m -> m.copy(authorization = m.authorization?.let { card ->
            card.copy(rows = card.rows.map { if (it.id == rowId && it.status !in setOf(AuthorizationStatus.CONNECTED, AuthorizationStatus.CANCELLED)) it.copy(status = status, attempted = attempted) else it })
        }) }
    }

    fun beginAuthorization(messageId: String, rowId: String): com.artemis.uisdk.ArtemisAuthorizationRequest? {
        val (card, row) = authorizationRow(messageId, rowId) ?: return null
        if (state.value.connectionStatus != ConnectionStatus.CONNECTED || state.value.isOffline || authorizationRequests.size >= 50 || authorizationOperations.size >= 100 ||
            row.status !in setOf(AuthorizationStatus.PENDING, AuthorizationStatus.FAILED)) return null
        val url = AuthorizationContracts.url(row.authUrl) ?: return null
        val request = com.artemis.uisdk.ArtemisAuthorizationRequest(UUID.randomUUID().toString(), card.sessionId, messageId, rowId, row.label, url)
        authorizationRequests[request.requestId] = request
        authorizationOperations[messageId to rowId] = request.requestId
        authorizationStatus(messageId, rowId, AuthorizationStatus.AUTHORIZING)
        return request
    }

    fun completeAuthorization(request: com.artemis.uisdk.ArtemisAuthorizationRequest, result: com.artemis.uisdk.ArtemisAuthorizationResult) {
        if (authorizationRequests[request.requestId] !== request) return
        authorizationRequests.remove(request.requestId)
        if (browserAuthorization === request) { browserAuthorization = null; authorizationBrowserWasAway = false }
        val key = request.messageId to request.rowId
        if (authorizationOperations[key] != request.requestId || request.sessionId != state.value.sessionId ||
            authorizationRow(request.messageId, request.rowId)?.second?.status != AuthorizationStatus.AUTHORIZING) return
        authorizationOperations.remove(key)
        when (result) {
            com.artemis.uisdk.ArtemisAuthorizationResult.COMPLETED -> verifyAuthorization(request.messageId, request.rowId)
            com.artemis.uisdk.ArtemisAuthorizationResult.DISMISSED -> authorizationStatus(request.messageId, request.rowId, AuthorizationStatus.PENDING)
            com.artemis.uisdk.ArtemisAuthorizationResult.FAILED -> authorizationStatus(request.messageId, request.rowId, AuthorizationStatus.FAILED)
        }
    }

    internal fun trackAuthorizationBrowser(request: com.artemis.uisdk.ArtemisAuthorizationRequest) {
        browserAuthorization = request; authorizationBrowserWasAway = false
    }
    internal fun authorizationBrowserDeparted() { if (browserAuthorization != null) authorizationBrowserWasAway = true }
    internal fun authorizationBrowserReturned() {
        if (authorizationBrowserWasAway) browserAuthorization?.let { completeAuthorization(it, com.artemis.uisdk.ArtemisAuthorizationResult.DISMISSED) }
    }

    fun verifyAuthorization(messageId: String, rowId: String) = sendAuthorizationResponse(messageId, rowId, false)
    fun cancelAuthorization(messageId: String, rowId: String) = sendAuthorizationResponse(messageId, rowId, true)

    private fun sendAuthorizationResponse(messageId: String, rowId: String, cancelled: Boolean) {
        val (card, row) = authorizationRow(messageId, rowId) ?: return
        if (row.status in setOf(AuthorizationStatus.CONNECTED, AuthorizationStatus.CANCELLED, AuthorizationStatus.VERIFYING, AuthorizationStatus.CANCELLING) ||
            cancelled && !row.canCancel) return
        val key = messageId to rowId
        if (authorizationOperations.size >= 100 && key !in authorizationOperations) return
        authorizationTimeouts.remove(key)?.let(mainThreadHandler::removeCallbacks)
        authorizationRequests.entries.removeAll { it.value.messageId == messageId && it.value.rowId == rowId }
        if (browserAuthorization?.let { it.messageId == messageId && it.rowId == rowId } == true) browserAuthorization = null
        val operation = UUID.randomUUID().toString()
        authorizationOperations[key] = operation
        try {
            val client = artemisSocketClient ?: throw IllegalStateException("Disconnected")
            check(state.value.connectionStatus == ConnectionStatus.CONNECTED && !state.value.isOffline)
            if (card.kind == AuthorizationKind.CHALLENGE || row.canCancel) {
                client.respondToAuthorization(card.sessionId, row.id, if (cancelled) "cancelled" else "completed")
            }
            if (!cancelled && card.kind == AuthorizationKind.PREFLIGHT) {
                client.recheckAuthorization(card.sessionId, row.authProfileRef ?: throw IllegalStateException("Missing requirement"), row.id)
            }
            authorizationStatus(messageId, rowId, if (cancelled) AuthorizationStatus.CANCELLING else AuthorizationStatus.VERIFYING)
            // A local waiting timeout enables explicit retry. It never cancels/resumes a workflow.
            val timeout = Runnable {
                authorizationTimeouts.remove(key)
                if (!isClosed && authorizationOperations[key] == operation && state.value.sessionId == card.sessionId) {
                    authorizationOperations.remove(key)
                    authorizationStatus(messageId, rowId, AuthorizationStatus.FAILED)
                }
            }
            authorizationTimeouts[key] = timeout
            mainThreadHandler.postDelayed(timeout, 30_000)
        } catch (_: RuntimeException) {
            authorizationOperations.remove(key)
            authorizationStatus(messageId, rowId, AuthorizationStatus.FAILED)
        }
    }

    private fun pruneAuthorizationOperations() {
        authorizationOperations.keys.toList().forEach { key ->
            val row = authorizationRow(key.first, key.second)?.second
            if (row == null || row.status !in setOf(AuthorizationStatus.AUTHORIZING, AuthorizationStatus.VERIFYING, AuthorizationStatus.CANCELLING)) {
                authorizationOperations.remove(key)
                authorizationTimeouts.remove(key)?.let(mainThreadHandler::removeCallbacks)
            }
        }
        authorizationRequests.entries.removeAll { (id, request) -> authorizationOperations[request.messageId to request.rowId] != id }
        if (browserAuthorization?.requestId !in authorizationRequests.keys) { browserAuthorization = null; authorizationBrowserWasAway = false }
    }

    private fun clearAuthorizationOperations() {
        authorizationTimeouts.values.forEach(mainThreadHandler::removeCallbacks); authorizationTimeouts.clear()
        authorizationRequests.clear(); authorizationOperations.clear(); browserAuthorization = null; authorizationBrowserWasAway = false
    }

    fun sendPresence(typing: Boolean) { artemisSocketClient?.sendPresence(typing) }

    fun submitAction(
        messageId: String,
        actionIdentifier: String,
        actionValue: String?,
        formData: Map<String, String>?,
        renderIdentifier: String?,
    ) {
        if (isClosed || mutableChatUiState.value.connectionStatus != ConnectionStatus.CONNECTED || mutableChatUiState.value.isOffline) return
        val sourceMessage = mutableChatUiState.value.messages.find { it.messageId == messageId } ?: return
        val isQuickReply = templatePayloads(sourceMessage, "quick_replies").any { replies ->
            replies.takeIf { it.isJsonArray }?.asJsonArray?.any {
                it.isJsonObject && it.asJsonObject.string("id") == actionIdentifier
            } == true
        }
        val actionLockKey = renderIdentifier?.let { "render:$it" } ?: "$messageId:${if (isQuickReply) "quick_replies" else actionIdentifier}"
        val displayLabel = if (formData == null) actionDisplayLabel(sourceMessage, actionIdentifier) else null
        val displayId = if (displayLabel != null) UUID.randomUUID().toString() else null
        val previousActivityState = mutableChatUiState.value
        if (!lockedActionKeys.add(actionLockKey)) return
        try {
            artemisSocketClient?.submitAction(actionIdentifier, actionValue, formData, renderIdentifier, displayLabel, displayId)
            if (displayId != null && displayLabel != null) {
                transcript.acceptedUser(displayId, displayLabel)
                setState(transcript.state)
                transcript.state.messages.find { it.messageId == displayId }?.let {
                    artemisUiHandle.emit(ArtemisUiEvent.Message(it))
                    artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(displayId, "user"))
                }
            }
            updateMessage(messageId) { message ->
                val actions = message.actions?.deepCopy()
                if (renderIdentifier != null) actions?.add("submitted", JsonObject().apply {
                    addProperty("actionId", actionIdentifier); addProperty("status", "submitted");
                    if (actionValue != null) addProperty("value", actionValue)
                })
                message.copy(actions = actions, presentationVersion = message.presentationVersion + 1)
            }
            // Web emits messagesReplaced when a submitted render is reflected
            // back into an existing message. Keep the state-flow update and the
            // public event in lockstep for native hosts.
            if (renderIdentifier != null) {
                artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(mutableChatUiState.value.messages.toList()))
            }
            if (actionIdentifier == "feedback") {
                setState(mutableChatUiState.value.copy(errorMessage = null))
            } else {
                setState(mutableChatUiState.value.copy(isTyping = true, errorMessage = null))
                emitLocalActivityTransitions(previousActivityState, mutableChatUiState.value)
            }
        } catch (error: RuntimeException) {
            lockedActionKeys.remove(actionLockKey)
            updateMessage(messageId) { it.copy(presentationVersion = it.presentationVersion + 1) }
            setState(mutableChatUiState.value.copy(errorMessage = error.message ?: label(com.artemis.uisdk.R.string.artemis_action_rejected)))
            artemisUiHandle.emit(ArtemisUiEvent.Error("SEND_REJECTED", label(com.artemis.uisdk.R.string.artemis_action_rejected), true))
        }
    }

    internal fun templateDraft(message: ChatMessage, renderId: String?, scope: String, signature: JsonObject): Map<String, String> =
        templateDrafts[TemplateContracts.draftKey(message, renderId, scope)]?.takeIf { it.first == signature }?.second.orEmpty()
    internal fun updateTemplateDraft(message: ChatMessage, renderId: String?, scope: String, signature: JsonObject, values: Map<String, String>) {
        if (isClosed || state.value.messages.none { it.messageId == message.messageId || it.serverMessageId == message.serverMessageId && message.serverMessageId != null }) return
        templateDrafts[TemplateContracts.draftKey(message, renderId, scope)] = signature.deepCopy() to values.mapValues { it.value.take(10_000) }
        while (templateDrafts.size > 64) templateDrafts.remove(templateDrafts.keys.first())
    }

    private fun feedbackMessage(id: String) = state.value.messages.find { it.messageId == id || it.serverMessageId == id }

    internal fun feedbackState(message: ChatMessage, data: JsonObject): FeedbackResponseState {
        val current = feedbackMessage(message.messageId) ?: message
        val renderId = FeedbackContracts.renderId(current, data)
        val response = current.feedbackResponses[renderId.orEmpty()] ?: FeedbackResponseState()
        val draft = feedbackDrafts[FeedbackContracts.key(current, renderId)]?.takeIf { it.first == data }?.second ?: response.draft
        return response.copy(draft = FeedbackContracts.parse(data)?.normalized(draft) ?: draft)
    }

    internal fun updateFeedbackDraft(message: ChatMessage, data: JsonObject, value: FeedbackDraft) {
        val current = feedbackMessage(message.messageId) ?: return
        val template = FeedbackContracts.parse(data) ?: return
        val renderId = FeedbackContracts.renderId(current, data)
        if (current.feedbackResponses[renderId.orEmpty()]?.status in setOf(FeedbackSubmissionStatus.PENDING, FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)) return
        feedbackDrafts[FeedbackContracts.key(current, renderId)] = data.deepCopy() to template.normalized(value)
        while (feedbackDrafts.size > 500) feedbackDrafts.remove(feedbackDrafts.keys.first())
    }

    /** Existing entry point accepts either local or server IDs; the wire always uses the server ID. */
    fun submitFeedback(messageId: String, ratingType: String, ratingValue: Int, feedbackText: String?) {
        val message = feedbackMessage(messageId) ?: return
        val renderId = message.actions?.string("renderId")
        submitFeedbackInternal(message, ratingType, renderId, FeedbackDraft(ratingValue, feedbackText.orEmpty()))
    }

    @JvmOverloads
    fun submitFeedbackResponse(messageId: String, draft: FeedbackDraft, renderId: String? = null) {
        val message = feedbackMessage(messageId) ?: return
        val payload = FeedbackContracts.payload(message, renderId) ?: return
        val template = FeedbackContracts.parse(payload) ?: return
        val answer = template.normalized(draft)
        if (!template.valid(answer.rating)) return
        submitFeedbackInternal(message, template.type, renderId, answer)
    }

    private fun submitFeedbackInternal(message: ChatMessage, type: String, renderId: String?, draft: FeedbackDraft) {
        if (isClosed || message.role != MessageRole.ASSISTANT) return
        val serverId = message.serverMessageId ?: return
        val renderKey = renderId.orEmpty()
        val response = message.feedbackResponses[renderKey] ?: FeedbackResponseState()
        if (response.status in setOf(FeedbackSubmissionStatus.PENDING, FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)) return
        val client = artemisSocketClient ?: return
        val payload = FeedbackContracts.payload(message, renderId)?.deepCopy()
        if (type == "text" && draft.comments.isBlank()) return
        val key = FeedbackContracts.key(message, renderId)
        feedbackDrafts[key] = (payload ?: JsonObject()) to draft
        while (feedbackDrafts.size > 500) feedbackDrafts.remove(feedbackDrafts.keys.first())
        fun publish(status: FeedbackSubmissionStatus, code: String? = null) {
            val current = feedbackMessage(serverId) ?: return
            updateMessage(current.messageId) { it.copy(feedbackResponses = it.feedbackResponses +
                (renderKey to FeedbackResponseState(status, draft, code))) }
        }
        if (state.value.connectionStatus != ConnectionStatus.CONNECTED || state.value.isOffline) {
            publish(FeedbackSubmissionStatus.FAILED, "NOT_CONNECTED")
            return
        }
        val session = state.value.sessionId
        val operation = UUID.randomUUID().toString()
        feedbackOperations[key] = operation
        publish(FeedbackSubmissionStatus.PENDING)
        fun finish(status: FeedbackSubmissionStatus, code: String? = null) {
            if (isClosed || state.value.sessionId != session || feedbackOperations[key] != operation) return
            feedbackOperations.remove(key)
            val current = feedbackMessage(serverId) ?: return
            if (FeedbackContracts.payload(current, renderId) != payload) return
            publish(status, code)
            if (status == FeedbackSubmissionStatus.SUBMITTED) feedbackDrafts.remove(key)
            if (status == FeedbackSubmissionStatus.FAILED) artemisUiHandle.emit(ArtemisUiEvent.Error(
                code ?: "FEEDBACK_REJECTED", label(com.artemis.uisdk.R.string.artemis_feedback_rejected), true))
        }
        try {
            client.submitFeedback(serverId, type, draft.rating ?: 0, draft.comments.trim().takeIf(String::isNotBlank), renderId,
                draft.followUpRating, draft.followUpText.trim().takeIf(String::isNotBlank), false, 10_000,
                object : ArtemisFeedbackCallback {
                    override fun onSuccess(feedbackId: String) = finish(FeedbackSubmissionStatus.SUBMITTED)
                    override fun onFailure(code: String, message: String) {
                        val status = when (code) {
                            "DUPLICATE_FEEDBACK" -> FeedbackSubmissionStatus.SUBMITTED
                            "EXPIRED" -> FeedbackSubmissionStatus.EXPIRED
                            else -> FeedbackSubmissionStatus.FAILED
                        }
                        finish(status, code)
                    }
                })
        } catch (_: RuntimeException) {
            finish(FeedbackSubmissionStatus.FAILED, "SEND_REJECTED")
        }
    }

    fun isTemplateActionLocked(messageId: String, actionIdentifier: String, renderId: String?): Boolean =
        (renderId != null && "render:$renderId" in lockedActionKeys) || isActionLocked(messageId, actionIdentifier)

    fun isActionLocked(messageId: String, actionIdentifier: String) =
        (actionIdentifier == "feedback" && feedbackMessage(messageId)?.feedbackResponses?.values?.any {
            it.status in setOf(FeedbackSubmissionStatus.PENDING, FeedbackSubmissionStatus.SUBMITTED, FeedbackSubmissionStatus.EXPIRED)
        } == true) || "$messageId:$actionIdentifier" in lockedActionKeys || mutableChatUiState.value.messages
            .find { it.messageId == messageId }?.actions?.let { actions ->
                actions.obj("submitted")?.string("status") in setOf("pending", "submitted") || actions.string("renderId")?.let { "render:$it" in lockedActionKeys } == true
            } == true

    private fun templatePayloads(message: ChatMessage, name: String): List<com.google.gson.JsonElement> =
        listOfNotNull(message.richContent?.get(name)) + message.renderables?.toList().orEmpty().mapNotNull {
            it.takeIf { entry -> entry.isJsonObject && entry.asJsonObject.string("name") == name }?.asJsonObject?.get("payload")
        }

    private fun actionDisplayLabel(message: ChatMessage?, id: String): String? {
        message ?: return null
        val quickReply = templatePayloads(message, "quick_replies").flatMap { it.takeIf { value -> value.isJsonArray }?.asJsonArray?.toList().orEmpty() }
            .firstOrNull { it.isJsonObject && it.asJsonObject.string("id") == id }?.asJsonObject
        val buttons = message.actions?.array("elements")?.filter { it.isJsonObject && (it.asJsonObject.string("type") ?: "button") == "button" }.orEmpty() +
            templatePayloads(message, "carousel").flatMap { it.takeIf { value -> value.isJsonObject }?.asJsonObject?.array("cards")?.toList().orEmpty() }
                .flatMap { it.takeIf { value -> value.isJsonObject }?.asJsonObject?.array("buttons")?.toList().orEmpty() }
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
        templateDrafts.clear(); feedbackDrafts.clear(); feedbackOperations.clear()
        clearAuthorizationOperations()
        unregisterConnectivity()
        val client = artemisSocketClient
        val shouldEndSession = reason == "END_SESSION" || artemisUiHandle.config.session.endSessionOnClose
        if (BuildConfig.DEBUG) Log.d("ArtemisSessionEnd", "reason=$reason endSession=$shouldEndSession socketConnected=${client?.isConnected == true}")
        if (shouldEndSession) {
            artemisUiHandle.requestNewSession()
            client?.endSession()
            mainThreadHandler.postDelayed({ client?.shutdown() }, 3_100)
        } else client?.shutdown()
        artemisSocketClient = null
        artemisUiHandle.finishChat(reason)
    }

    private fun applyAttachmentPolicy(scope: JsonObject) {
        if (isClosed) return
        artemisUiHandle.applyRuntimeBranding(scope)
        mutableInitializationComplete.value = true
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
            setState(state.value.copy(errorMessage = attachmentsDisabledMessage))
        }
        if (attachmentsEnabled) {
            if (state.value.errorMessage == lastAttachmentPolicyError) setState(state.value.copy(errorMessage = null))
            lastAttachmentPolicyError = null
            startNextAttachment()
        }
    }

    private val socketListener = object : ArtemisSocketListener {
        override fun onSessionScopeChanged(scope: JsonObject) { applyAttachmentPolicy(scope) }
        override fun onLog(message: String) {
            if (BuildConfig.DEBUG && message.startsWith("ATTACHMENT ")) Log.d("ArtemisAttachment", message)
            if (BuildConfig.DEBUG && message.startsWith("END_SESSION ")) Log.d("ArtemisSocket", message)
        }
        override fun onConnecting() {
            if (BuildConfig.DEBUG) Log.d("ArtemisSocket", "Connection state=CONNECTING")
            update { chatUiState -> chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTING) }
        }
        override fun onConnected(sessionId: String) {
            if (isClosed) return
            if (BuildConfig.DEBUG) Log.d("ArtemisSocket", "Connection state=CONNECTED sessionAvailable=${sessionId.isNotBlank()}")
            artemisUiHandle.confirmSession()
            applyAttachmentPolicy(artemisSocketClient?.sessionScope ?: JsonObject())
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.CONNECTED,
                    errorMessage = if (chatUiState.attachmentDrafts.isNotEmpty() && !attachmentsEnabled) attachmentsDisabledMessage else null)
            }
            mainThreadHandler.post { startNextAttachment() }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTED"))
            artemisUiHandle.emit(ArtemisUiEvent.SessionStarted(sessionId, artemisSocketClient?.sessionScope ?: JsonObject()))
            if (artemisUiHandle.config.features.history || contactCenterEnabled) { historyTransferRevision = transcript.transferRevision; artemisSocketClient?.loadHistory() }
        }
        override fun onDisconnected(reason: String) {
            if (isClosed) return
            if (BuildConfig.DEBUG) Log.d("ArtemisSocket", "Connection state=DISCONNECTED")
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.DISCONNECTED, isTyping = false, messages = chatUiState.messages.map { m ->
                    m.copy(authorization = m.authorization?.let { card -> card.copy(rows = card.rows.map { row ->
                        if (row.status in setOf(AuthorizationStatus.VERIFYING, AuthorizationStatus.CANCELLING)) row.copy(status = AuthorizationStatus.FAILED) else row
                    }) })
                })
            }
            pruneAuthorizationOperations()
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("DISCONNECTED", reason))
            if (contactCenterEnabled) artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("disabled", reason))
        }
        override fun onMessage(payload: String) { if (!isClosed) parseFrame(payload) }
        override fun onHistory(sessionId: String, messagesJson: String) {
            if (isClosed || sessionId != mutableChatUiState.value.sessionId) return
            transcript.mergeHistory(ChatFrameParser.history(messagesJson), historyTransferRevision)
            setState(transcript.state)
            artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(transcript.state.messages.toList()))
        }
        override fun onIdleTimeout(timeoutMs: Long, behavior: String) {
            if (!isClosed) artemisUiHandle.emit(ArtemisUiEvent.IdleTimeout(timeoutMs, behavior))
        }
        override fun onError(error: Throwable) {
            if (isClosed) return
            // Reveal the normal error/retry UI if init fails before returning a scope.
            mutableInitializationComplete.value = true
            if (BuildConfig.DEBUG) Log.w("ArtemisSocket", "Socket error=${error.javaClass.simpleName}")
            val errorDescription = safeError(error)
            update { chatUiState -> chatUiState.copy(errorMessage = errorDescription) }
            artemisUiHandle.emit(ArtemisUiEvent.Error("CONNECTION", errorDescription, true))
        }
    }

    private fun parseFrame(payload: String) {
        val frame = ChatFrameParser.parse(payload)
        if (BuildConfig.DEBUG) {
            Log.d("ArtemisSocket", "Inbound frame parsed=${frame != null}")
        }
        frame ?: return
        if (frame is ChatFrame.Transfer) {
            if (seenAgentTransferDeliverySessionId != frame.sessionId) {
                seenAgentTransferDeliverySessionId = frame.sessionId
                seenAgentTransferDeliveryIds.clear()
            }
            val deliveryId = frame.payload?.string("deliveryId")?.takeIf(String::isNotBlank)
            if (deliveryId != null && !seenAgentTransferDeliveryIds.add(deliveryId)) return
            while (seenAgentTransferDeliveryIds.size > 200) {
                seenAgentTransferDeliveryIds.remove(seenAgentTransferDeliveryIds.first())
            }
        }
        transcript.configureScope(artemisSocketClient?.sessionScope ?: JsonObject())
        val previousSession = transcript.state.sessionId
        val previousState = transcript.state
        transcript.apply(frame)
        if (frame is ChatFrame.Error && frame.code?.startsWith("AUTH_") == true && artemisUiHandle.config.features.authentication) {
            transcript.update(transcript.state.copy(messages = transcript.state.messages.map { m ->
                m.copy(authorization = m.authorization?.let { card -> card.copy(rows = card.rows.map { row ->
                    if (row.status in setOf(AuthorizationStatus.VERIFYING, AuthorizationStatus.CANCELLING)) row.copy(status = AuthorizationStatus.FAILED) else row
                }) })
            }))
        }
        if (frame is ChatFrame.Session && previousSession != transcript.state.sessionId) {
            clearAuthorizationOperations()
            lockedActionKeys.clear(); surveyDrafts.clear(); templateDrafts.clear(); feedbackDrafts.clear(); feedbackOperations.clear(); clearAttachmentQueue()
            contactCenterSbcRequestId = null
            contactCenterSbcDetails = null
            endedContactCenterCalls.clear()
            seenAgentTransferDeliveryIds.clear()
            seenAgentTransferDeliverySessionId = null
        }
        if (frame is ChatFrame.Session && contactCenterEnabled) {
            val capabilities = frame.payload.obj("capabilities")?.obj("contactCenter")
            if (capabilities?.flag("enabled") == true && capabilities.flag("sbcDetails") &&
                contactCenterSbcRequestId == null && artemisSocketClient?.isConnected == true) {
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("sbc_pending"))
                contactCenterSbcRequestId = runCatching { artemisSocketClient?.requestContactCenterSbcDetails() }.getOrNull()
            } else if (capabilities == null || capabilities.flag("enabled") != true) {
                val reason = capabilities?.string("disabledReason") ?: "Contact Center is not enabled for this session"
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("unsupported"))
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterUnsupported(
                    reason, capabilities?.deepCopy()
                ))
            } else if (capabilities.flag("sbcDetails") != true) {
                val reason = "Contact Center SBC details are not enabled for this session"
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("unsupported", reason))
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterUnsupported(reason, capabilities.deepCopy()))
            }
        }
        setState(transcript.state)
        if (frame is ChatFrame.Authorization || frame is ChatFrame.Error) pruneAuthorizationOperations()
        emitFrameEvents(frame, previousState, previousSession)
        if (frame is ChatFrame.Transfer && contactCenterEnabled && transcript.state.sessionId != null) {
            artemisUiHandle.emit(ArtemisUiEvent.AgentTransferChanged(transcript.state.agentTransferStatus.name))
            frame.mediaFrame?.let(::emitContactCenterMediaEvent)
        }
        if (frame is ChatFrame.ContactCenterMedia && contactCenterEnabled) {
            emitContactCenterMediaEvent(frame.event)
        }
        if (frame is ChatFrame.Cancel) artemisUiHandle.emit(ArtemisUiEvent.ResponseCancelled(frame.id, frame.reason))
    }

    private fun emitFrameEvents(frame: ChatFrame, previousState: ChatUiState, previousSession: String?) {
        val current = transcript.state
        fun emitTypingTransition() {
            // Activity fillers use the same internal flag to keep the native
            // indicator visible, but Web's `typing` event represents only a
            // typing activity (not a status filler).
            val wasTyping = previousState.isTyping && previousState.activityText == null
            val isTyping = current.isTyping && current.activityText == null
            if (wasTyping != isTyping) {
                artemisUiHandle.emit(ArtemisUiEvent.Typing(isTyping))
            }
        }
        fun emitMessageIfChanged(messageId: String) {
            val message = current.messages.find { it.messageId == messageId } ?: return
            val old = previousState.messages.find { it.messageId == messageId }
            if (old != message) {
                artemisUiHandle.emit(ArtemisUiEvent.Message(message))
                artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(message.messageId, message.role.name.lowercase()))
            }
        }
        fun emitChangedMessages() {
            val changed = current.messages.filter { message ->
                previousState.messages.find { it.messageId == message.messageId } != message
            }
            if (changed.isEmpty()) return
            var replaced = false
            changed.forEach { message ->
                if (previousState.messages.none { it.messageId == message.messageId }) {
                    artemisUiHandle.emit(ArtemisUiEvent.Message(message))
                    artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(message.messageId, message.role.name.lowercase()))
                } else if (!replaced) {
                    artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(current.messages.toList()))
                    replaced = true
                }
            }
        }
        when (frame) {
            is ChatFrame.Session -> {
                if (previousSession != null && previousSession != current.sessionId) {
                    artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(current.messages.toList()))
                }
                artemisUiHandle.emit(ArtemisUiEvent.InitialAssistantMessageStatus(
                    current.initialAssistantMessageSource,
                    current.initialAssistantMessageExpected,
                    current.initialAssistantMessagePending,
                    current.initialAssistantMessageRendered,
                    current.initialAssistantMessageFailed,
                ))
            }
            is ChatFrame.Start -> emitTypingTransition()
            is ChatFrame.Chunk -> artemisUiHandle.emit(ArtemisUiEvent.MessageChunk(frame.id, frame.text))
            is ChatFrame.End -> {
                emitMessageIfChanged(frame.message.messageId)
                emitTypingTransition()
            }
            is ChatFrame.UserRender -> {
                val message = current.messages.find { it.messageId == frame.id }
                if (message != null && previousState.messages.find { it.messageId == frame.id } != message) {
                    artemisUiHandle.emit(ArtemisUiEvent.MessagesReplaced(current.messages.toList()))
                }
            }
            is ChatFrame.Typing -> emitTypingTransition()
            is ChatFrame.Activity -> {
                when (frame.type) {
                    "status_update" -> artemisUiHandle.emit(ArtemisUiEvent.StatusUpdate(
                        text = frame.payload.string("text", "content").orEmpty(),
                        operation = frame.payload.string("operation") ?: "general",
                        fillerId = frame.payload.string("fillerId"),
                        turnId = frame.payload.string("turnId"),
                    ))
                    "status_clear" -> artemisUiHandle.emit(ArtemisUiEvent.StatusClear)
                    else -> Unit
                }
                emitChangedMessages()
            }
            is ChatFrame.Authorization -> {
                emitAuthorizationEvent(frame)
                emitChangedMessages()
            }
            is ChatFrame.FeedbackAck -> emitFeedbackAck(frame.payload)
            is ChatFrame.SurveyAck -> emitSurveyAck(frame.payload)
            is ChatFrame.SessionEnd -> artemisUiHandle.emit(ArtemisUiEvent.SessionEnded(frame.payload.string("reason")))
            is ChatFrame.InitialMessage -> artemisUiHandle.emit(ArtemisUiEvent.InitialAssistantMessageStatus(
                current.initialAssistantMessageSource,
                current.initialAssistantMessageExpected,
                current.initialAssistantMessagePending,
                current.initialAssistantMessageRendered,
                current.initialAssistantMessageFailed,
            ))
            is ChatFrame.Transfer -> {
                frame.messages.forEach { emitMessageIfChanged(it.messageId) }
                if (frame.eventType == "agent:typing" || frame.eventType == "agent:typing_stop") emitTypingTransition()
                val data = frame.payload?.deepCopy()
                when (frame.eventType) {
                    "agent:joined" -> artemisUiHandle.emit(ArtemisUiEvent.AgentJoined(frame.sessionId, data, frame.timestamp))
                    "agent:exited" -> artemisUiHandle.emit(ArtemisUiEvent.AgentExited(frame.sessionId, data, frame.timestamp))
                    "agent:notice" -> artemisUiHandle.emit(ArtemisUiEvent.AgentNotice(frame.sessionId, data, frame.timestamp))
                }
            }
            is ChatFrame.ContactCenterMedia, is ChatFrame.Cancel -> Unit
            is ChatFrame.Error -> artemisUiHandle.emit(ArtemisUiEvent.Error(
                frame.code ?: "RUNTIME", frame.text, true,
            )).also {
                if (frame.code?.contains("CONTACT", ignoreCase = true) == true || frame.code?.contains("CALL", ignoreCase = true) == true) {
                    artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("error", frame.text))
                    artemisUiHandle.emit(ArtemisUiEvent.ContactCenterError(frame.text))
                }
            }
        }
        if (frame !is ChatFrame.Activity && previousState.activityText != null && current.activityText == null) {
            artemisUiHandle.emit(ArtemisUiEvent.StatusClear)
        }
    }

    private fun emitLocalActivityTransitions(previous: ChatUiState, current: ChatUiState) {
        if (previous.activityText != null && current.activityText == null) {
            artemisUiHandle.emit(ArtemisUiEvent.StatusClear)
        }
        val wasTyping = previous.isTyping && previous.activityText == null
        val isTyping = current.isTyping && current.activityText == null
        if (wasTyping != isTyping) {
            artemisUiHandle.emit(ArtemisUiEvent.Typing(isTyping))
        }
    }

    private fun emitAuthorizationEvent(frame: ChatFrame.Authorization) {
        val payload = frame.payload.deepCopy()
        when (val event = frame.event) {
            is AuthorizationEvent.Gate -> artemisUiHandle.emit(ArtemisUiEvent.AuthRequired(
                event.sessionId,
                payload.array("pending")?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() }.orEmpty(),
                payload.array("satisfied")?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() }.orEmpty(),
                payload,
            ))
            is AuthorizationEvent.Challenge -> artemisUiHandle.emit(ArtemisUiEvent.AuthChallenge(payload))
            is AuthorizationEvent.Satisfied -> artemisUiHandle.emit(ArtemisUiEvent.AuthGateSatisfied(event.sessionId, payload))
            is AuthorizationEvent.Resolved -> artemisUiHandle.emit(ArtemisUiEvent.AuthChallengeResolved(
                event.sessionId, event.id, payload.string("outcome"), payload,
            ))
            is AuthorizationEvent.Queued -> artemisUiHandle.emit(ArtemisUiEvent.MessageQueued(
                event.sessionId, payload.string("reason") ?: "queued", payload,
            ))
        }
    }

    private fun emitFeedbackAck(payload: JsonObject) {
        val error = payload.obj("error")
        artemisUiHandle.emit(ArtemisUiEvent.FeedbackAck(
            messageId = payload.string("messageId").orEmpty(),
            success = payload.flag("success"),
            feedbackId = payload.string("feedbackId"),
            actionRenderId = payload.string("actionRenderId"),
            errorCode = error?.string("code"),
            errorMessage = error?.string("message"),
            payload = payload.deepCopy(),
        ))
    }

    private fun emitSurveyAck(payload: JsonObject) {
        val error = payload.obj("error")
        artemisUiHandle.emit(ArtemisUiEvent.AgentTransferSurveyAck(
            artemisSessionId = payload.string("artemisSessionId").orEmpty(),
            success = payload.flag("success"),
            feedbackId = payload.string("feedbackId"),
            errorCode = error?.string("code"),
            errorMessage = error?.string("message"),
            payload = payload.deepCopy(),
        ))
    }

    private fun emitContactCenterMediaEvent(event: ContactCenterMediaFrame) {
        when (event) {
            is ContactCenterMediaFrame.Incoming -> event.call.let { invite ->
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("incoming", callId = invite.callId))
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterIncomingCall(invite))
                artemisUiHandle.emit(ArtemisUiEvent.AgentMediaInvite(
                    callType = invite.callType,
                    conversationId = invite.conversationId,
                    providerConversationId = invite.providerConversationId,
                    providerAccountId = invite.providerAccountId,
                    mediaSessionId = invite.mediaSessionId,
                    idempotencyKey = invite.idempotencyKey,
                    callId = invite.callId,
                    displayName = invite.displayName,
                ))
            }
            is ContactCenterMediaFrame.EndpointReleased -> {
                artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("connecting", callId = event.endpoint.callId))
                artemisUiHandle.emit(ArtemisUiEvent.AgentMediaEndpointReleased(event.endpoint))
            }
            is ContactCenterMediaFrame.SbcDetails -> {
                val details = event.details
                if (details.requestId == null || details.requestId == contactCenterSbcRequestId) {
                    contactCenterSbcDetails = details
                    artemisUiHandle.emit(ArtemisUiEvent.AgentMediaSbcDetailsChanged(details))
                    if (details.ready) {
                        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("ready"))
                        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterReady(details))
                    } else {
                        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged(
                            "unsupported", "SBC details are unavailable"
                        ))
                        artemisUiHandle.emit(ArtemisUiEvent.ContactCenterUnsupported("SBC details are unavailable"))
                    }
                }
            }
            is ContactCenterMediaFrame.Terminated -> {
                val reason = event.call.reason
                val callId = event.call.callId
                if (callId == null || endedContactCenterCalls.add(callId)) {
                    artemisUiHandle.emit(ArtemisUiEvent.ContactCenterStateChanged("ended", reason, callId))
                    artemisUiHandle.emit(ArtemisUiEvent.ContactCenterCallEnded(callId, reason))
                }
                artemisUiHandle.emit(ArtemisUiEvent.AgentMediaCallTerminated(event.call))
            }
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
            artemisUiHandle.publishState(newChatUiState)
        } else {
            mainThreadHandler.post { if (!isClosed) { transcript.update(newChatUiState); mutableChatUiState.value = newChatUiState; artemisUiHandle.publishState(newChatUiState) } }
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
        is java.io.IOException -> label(com.artemis.uisdk.R.string.artemis_network_error)
        else -> label(com.artemis.uisdk.R.string.artemis_configuration_error)
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
