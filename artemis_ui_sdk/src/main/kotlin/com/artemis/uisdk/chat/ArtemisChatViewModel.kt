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
    }

    private fun label(id: Int, vararg args: Any) = artemisUiHandle.text(id, *args)
    private var lastAttachmentPolicyError: String? = null
    private val attachmentsDisabledMessage get() = label(com.artemis.uisdk.R.string.artemis_attachments_disabled).also { lastAttachmentPolicyError = it }
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private val gsonSerializer = Gson()
    private val mutableChatUiState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableChatUiState.asStateFlow()
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
            fail("CONFIGURATION", error.message ?: label(com.artemis.uisdk.R.string.artemis_invalid_configuration), false)
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
        try {
            client.sendMessage(value, id, metadata, null, attachments.map { it.id })
            transcript.acceptedUser(id, value, metadata)
            setState(transcript.state)
            // Keep redacted text/metadata server-owned; refs carry no typed user text.
            updateMessage(id) { it.copy(attachments = attachments) }
            removePendingAttachment(); composerDraft = ""
            artemisUiHandle.emit(ArtemisUiEvent.MessageChanged(id, "user"))
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
                override fun onFailure(code: String, message: String) { if (!isClosed && state.value.sessionId == owningSession) contactCenterError(message) }
            })
        } catch (error: RuntimeException) { contactCenterError(label(com.artemis.uisdk.R.string.artemis_attachment_reconnect)) }
    }

    fun contactCenterError(message: String) {
        setState(state.value.copy(errorMessage = message))
        artemisUiHandle.emit(ArtemisUiEvent.Error("CONTACT_CENTER", message, true))
    }

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
        val isQuickReply = sourceMessage?.let { templatePayloads(it, "quick_replies").any { replies -> replies.takeIf { it.isJsonArray }?.asJsonArray?.any { it.isJsonObject && it.asJsonObject.string("id") == actionIdentifier } == true } } == true
        val actionLockKey = renderIdentifier?.let { "render:$it" } ?: "$messageId:${if (isQuickReply) "quick_replies" else actionIdentifier}"
        val displayLabel = if (formData == null) actionDisplayLabel(sourceMessage, actionIdentifier) else null
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
        artemisUiHandle.applyRuntimeBranding(scope)
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
                    errorMessage = if (chatUiState.attachmentDrafts.isNotEmpty() && !attachmentsEnabled) attachmentsDisabledMessage else null)
            }
            mainThreadHandler.post { startNextAttachment() }
            artemisUiHandle.emit(ArtemisUiEvent.ConnectionChanged("CONNECTED"))
            artemisUiHandle.emit(ArtemisUiEvent.SessionStarted(sessionId, artemisSocketClient?.sessionScope ?: JsonObject()))
            if (artemisUiHandle.config.features.history || contactCenterEnabled) { historyTransferRevision = transcript.transferRevision; artemisSocketClient?.loadHistory() }
        }
        override fun onDisconnected(reason: String) {
            if (isClosed) return
            update { chatUiState ->
                chatUiState.copy(connectionStatus = ConnectionStatus.DISCONNECTED, isTyping = false, messages = chatUiState.messages.map { m ->
                    m.copy(authorization = m.authorization?.let { card -> card.copy(rows = card.rows.map { row ->
                        if (row.status in setOf(AuthorizationStatus.VERIFYING, AuthorizationStatus.CANCELLING)) row.copy(status = AuthorizationStatus.FAILED) else row
                    }) })
                })
            }
            pruneAuthorizationOperations()
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
        }
        setState(transcript.state)
        if (frame is ChatFrame.Authorization || frame is ChatFrame.Error) pruneAuthorizationOperations()
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
