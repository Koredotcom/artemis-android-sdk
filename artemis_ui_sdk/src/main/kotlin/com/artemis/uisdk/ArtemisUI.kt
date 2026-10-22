package com.artemis.uisdk

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.artemis.uisdk.chat.ArtemisChatActivity
import com.artemis.uisdk.chat.ArtemisChatFragment
import android.net.Uri
import android.os.Looper
import androidx.annotation.MainThread
import com.artemis.uisdk.chat.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var hostTheme = config.theme
    private var hostTypography = config.typography
    private var runtimeWidget: JsonObject? = null
    private val mutablePresentation = MutableStateFlow(ArtemisPresentation(
        resolveBranding(hostTheme, null, config.welcomeMessage),
        config.strings,
        typography = hostTypography,
        logoResolver = config.logoResolver,
    ))
    internal val presentation = mutablePresentation.asStateFlow()
    private val mutableBranding = MutableStateFlow(presentation.value.branding)
    val brandingUpdates = mutableBranding.asStateFlow()
    private val renderers = linkedMapOf<String, ArtemisTemplateRenderer>()
    private val messageSnapshots = mutableMapOf<String, Pair<com.artemis.uisdk.chat.ChatMessage, com.artemis.uisdk.chat.ChatMessage>>()
    private var publishedMessages: List<com.artemis.uisdk.chat.ChatMessage>? = null
    private val mutableState = MutableStateFlow(ChatUiState())
    /** Detached snapshots. Observe from the host lifecycle; opening chat is still required to connect. */
    val state = mutableState.asStateFlow()
    val branding: ArtemisBranding get() = presentation.value.branding
    internal var authorizationLauncher: ArtemisAuthorizationLauncher? = null
        private set

    /** Optional app-owned auth flow; null uses Android's system browser from the chat UI. */
    @MainThread fun setAuthorizationLauncher(launcher: ArtemisAuthorizationLauncher?) {
        checkMainThread(); authorizationLauncher = launcher
    }
    @MainThread fun beginAuthorization(messageId: String, rowId: String): ArtemisAuthorizationRequest? {
        checkMainThread(); return chatViewModel?.beginAuthorization(messageId, rowId)
    }
    @MainThread fun completeAuthorization(request: ArtemisAuthorizationRequest, result: ArtemisAuthorizationResult) {
        checkMainThread(); chatViewModel?.completeAuthorization(request, result)
    }
    @MainThread fun checkAuthorization(messageId: String, rowId: String) {
        checkMainThread(); chatViewModel?.verifyAuthorization(messageId, rowId)
    }
    @MainThread fun cancelAuthorization(messageId: String, rowId: String) {
        checkMainThread(); chatViewModel?.cancelAuthorization(messageId, rowId)
    }

    @MainThread fun updateTheme(theme: ArtemisThemeConfig) {
        checkMainThread()
        require(theme.mode == hostTheme.mode) { "Set light/dark mode at initialization" }
        require(theme.bubbleRadiusDp.isFinite() && theme.bubbleRadiusDp in 0f..32f) { "Invalid bubble radius" }
        hostTheme = theme
        updateBranding()
    }

    @MainThread fun updateStrings(strings: ArtemisStrings) {
        checkMainThread()
        mutablePresentation.value = presentation.value.copy(strings = strings)
    }

    @MainThread fun updateTypography(typography: ArtemisTypographyConfig) {
        checkMainThread()
        hostTypography = typography
        mutablePresentation.value = presentation.value.copy(typography = typography)
    }

    @MainThread fun setLogoResolver(resolver: ArtemisLogoResolver?) {
        checkMainThread()
        mutablePresentation.value = presentation.value.copy(logoResolver = resolver)
    }

    /** Applies to matching richContent keys and canonical renderables, including built-in names. */
    @MainThread fun registerTemplateRenderer(name: String, renderer: ArtemisTemplateRenderer): AutoCloseable {
        checkMainThread()
        require(name.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid template name" }
        renderers[name] = renderer
        bumpRendererRevision()
        return AutoCloseable {
            if (!isClosed) {
                checkMainThread()
                if (renderers[name] === renderer) { renderers.remove(name); bumpRendererRevision() }
            }
        }
    }

    internal fun renderer(name: String) = renderers[name]
    internal fun rendererNames() = renderers.keys.toList()
    internal fun text(id: Int, vararg args: Any) = presentation.value.strings.text(appContext, id, *args)
    internal fun applyRuntimeBranding(scope: JsonObject) {
        if (isClosed) return
        runtimeWidget = scope.get("widgetConfig")?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy()
        updateBranding()
    }
    private fun updateBranding() {
        val next = resolveBranding(hostTheme, runtimeWidget, config.welcomeMessage)
        if (branding == next && presentation.value.typography == hostTypography) return
        mutablePresentation.value = presentation.value.copy(branding = next, typography = hostTypography)
        mutableBranding.value = next
    }
    private fun bumpRendererRevision() {
        mutablePresentation.value = presentation.value.copy(rendererRevision = presentation.value.rendererRevision + 1)
    }
    internal fun publishState(value: ChatUiState) {
        val messages = if (publishedMessages === value.messages) mutableState.value.messages else {
            val ids = value.messages.map { it.messageId }.toSet()
            messageSnapshots.keys.retainAll(ids)
            value.messages.map { message ->
                messageSnapshots[message.messageId]?.takeIf { it.first === message }?.second
                    ?: message.hostSnapshot().also { messageSnapshots[message.messageId] = message to it }
            }
        }
        publishedMessages = value.messages
        mutableState.value = value.copy(messages = messages, attachmentDrafts = value.attachmentDrafts.toList())
    }

    // Host commands use the same validation, permissions and transport paths as the native controls.
    @MainThread @JvmOverloads fun sendText(text: String, metadata: JsonObject? = null): Boolean {
        checkMainThread(); return chatViewModel?.sendText(text, metadata?.deepCopy()) ?: false
    }
    @MainThread fun sendComposedMessage(text: String): Boolean { checkMainThread(); return chatViewModel?.sendComposedMessage(text) ?: false }
    @MainThread fun uploadAttachments(uris: List<Uri>) { checkMainThread(); chatViewModel?.uploadAttachments(uris.toList()) }
    @MainThread fun retryAttachment(localId: String) { checkMainThread(); chatViewModel?.retryAttachment(localId) }
    @MainThread fun cancelAttachmentUpload(localId: String) { checkMainThread(); chatViewModel?.cancelAttachmentUpload(localId) }
    @MainThread fun removeAttachment(localId: String) { checkMainThread(); chatViewModel?.removePendingAttachment(localId) }
    @MainThread fun retryConnection() { checkMainThread(); chatViewModel?.retry() }
    @MainThread @JvmOverloads fun submitAction(messageId: String, actionId: String, value: String? = null, formData: Map<String, String>? = null, renderId: String? = null) {
        checkMainThread()
        val model = chatViewModel ?: return
        if (model.state.value.messages.none { it.messageId == messageId }) return
        model.submitAction(messageId, actionId, value, formData?.toMap(), renderId)
    }
    @MainThread fun resolveAttachmentUrl(messageId: String, attachmentId: String, onResolved: (String) -> Unit) {
        checkMainThread()
        val model = chatViewModel ?: return
        val attachment = model.state.value.messages.find { it.messageId == messageId }?.attachments?.find { it.id == attachmentId } ?: return
        model.openAttachment(attachment, onResolved)
    }
    @MainThread fun submitFeedback(messageId: String, type: String, rating: Int, comments: String?) {
        checkMainThread()
        chatViewModel?.state?.value?.messages?.find { it.messageId == messageId }?.serverMessageId?.let {
            chatViewModel?.submitFeedback(it, type, rating, comments)
        }
    }
    @MainThread @JvmOverloads fun submitFeedbackResponse(messageId: String, draft: com.artemis.uisdk.chat.FeedbackDraft, renderId: String? = null) {
        checkMainThread(); chatViewModel?.submitFeedbackResponse(messageId, draft, renderId)
    }
    @MainThread fun submitTransferSurvey(messageId: String, score: Int, comments: String?) {
        checkMainThread(); chatViewModel?.submitTransferSurvey(messageId, score, comments)
    }
    private fun checkMainThread() {
        checkAvailable()
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call UI commands on the main thread" }
    }

    private val eventListeners = CopyOnWriteArrayList<ArtemisUiListener>()
    @Volatile internal var isChatOpen = false
    internal var isMinimized = false
    internal var activeChatOptions: ChatOptions = ChatOptions()
    internal var chatViewModel: ArtemisChatViewModel? = null
    // Opt-in cached identity is scoped by endpoint, project, channel, key hash and user; credentials are not stored.
    private val sessionCache = if (config.connection.clientSessionIdentifier) appContext.getSharedPreferences("artemis.sdk.sessions", Context.MODE_PRIVATE) else null
    private fun sessionCacheKey(userContext: JsonObject?): String {
        val c = config.connection
        // Preserve the historical key for callers that configure user context once at initialization.
        val userScope = if (activeChatOptions.runtimeUserContext == null) {
            c.userContext?.get("userId")?.toString()
        } else userContext?.toString()
        val scope = listOf(c.endpoint.trimEnd('/'), c.projectId, c.channelId ?: c.channelName ?: c.deploymentSlug, c.apiKey, userScope).joinToString("\u0000")
        return java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    internal val effectiveUserContext: JsonObject? get() =
        (activeChatOptions.runtimeUserContext ?: config.connection.userContext)?.deepCopy()
    private fun effectiveSessionCacheKey() = sessionCacheKey(effectiveUserContext)
    internal val clientSessionIdentifier: String get() {
        val key = effectiveSessionCacheKey()
        return sessionCache?.getString(key, null)?.takeIf { it.matches(Regex("csid_[a-f0-9]{32}")) }
            ?: ("csid_" + UUID.randomUUID().toString().replace("-", "")).also { sessionCache?.edit()?.putString(key, it)?.apply() }
    }
    internal val requestsNewSession: Boolean get() = sessionCache?.getBoolean("${effectiveSessionCacheKey()}:new", false) == true
    internal fun requestNewSession() { sessionCache?.edit()?.putBoolean("${effectiveSessionCacheKey()}:new", true)?.apply() }
    internal fun confirmSession() { sessionCache?.edit()?.remove("${effectiveSessionCacheKey()}:new")?.apply() }
    @Volatile private var isClosed = false
    @Volatile internal var chatSessionShutdownCallback: ((String) -> Unit)? = null

    fun showChat(activity: FragmentActivity, options: ChatOptions = ChatOptions()) {
        checkAvailable()
        check(!isChatOpen) { "A chat session is already open for this handle" }
        val previousMinimized = isMinimized
        isChatOpen = true
        isMinimized = false
        activeChatOptions = options.copy(runtimeUserContext = options.runtimeUserContext?.deepCopy())
        try {
            activity.startActivity(Intent(activity, ArtemisChatActivity::class.java).apply {
                putExtra(ArtemisChatActivity.EXTRA_HANDLE_IDENTIFIER, handleIdentifier)
                putExtra(ArtemisChatActivity.EXTRA_TITLE, options.title)
            })
        } catch (error: RuntimeException) {
            isChatOpen = false
            isMinimized = previousMinimized
            activeChatOptions = ChatOptions()
            throw error
        }
    }

    fun createChatFragment(options: ChatOptions = ChatOptions()): Fragment {
        checkAvailable()
        check(!isChatOpen) { "A chat session is already open for this handle" }
        isChatOpen = true
        isMinimized = false
        activeChatOptions = options.copy(runtimeUserContext = options.runtimeUserContext?.deepCopy())
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
        activeChatOptions = ChatOptions()
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
        mutableState.value = ChatUiState()
        messageSnapshots.clear()
        publishedMessages = null
        runtimeWidget = null
        updateBranding()
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
        renderers.clear()
        authorizationLauncher = null
        ArtemisUI.unregisterHandle(this)
    }
}

data class ChatOptions @JvmOverloads constructor(
    val title: String = "Chat",
    val headerBuilder: ArtemisChatHeaderBuilder? = null,
    val footerBuilder: ArtemisChatFooterBuilder? = null,
    val runtimeUserContext: JsonObject? = null,
)

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
