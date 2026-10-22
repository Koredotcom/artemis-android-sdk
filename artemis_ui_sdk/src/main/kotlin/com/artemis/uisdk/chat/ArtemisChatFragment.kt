package com.artemis.uisdk.chat

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.artemis.uisdk.ArtemisUI
import com.artemis.uisdk.ArtemisUiEvent
import com.artemis.uisdk.ArtemisUiHandle
import com.artemis.uisdk.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.artemis.uisdk.ArtemisPresentation
import com.artemis.uisdk.ArtemisChatHeaderContext
import com.artemis.uisdk.ArtemisChatFooterContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ArtemisChatFragment : Fragment() {
    companion object {
        private const val ARG_HANDLE_IDENTIFIER = "handle"
        private const val ARG_CHAT_TITLE = "title"
        fun create(handleIdentifier: String, chatTitle: String) = ArtemisChatFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_HANDLE_IDENTIFIER, handleIdentifier)
                putString(ARG_CHAT_TITLE, chatTitle)
            }
        }
    }

    private lateinit var artemisUiHandle: ArtemisUiHandle
    private lateinit var artemisChatViewModel: ArtemisChatViewModel
    private lateinit var chatMessagesRecyclerView: RecyclerView
    private lateinit var connectionStatusTextView: TextView
    private lateinit var typingIndicatorTextView: TextView
    private lateinit var messageInputEditText: EditText
    private lateinit var attachButton: android.widget.ImageButton
    private lateinit var attachmentStrip: AttachmentComposerStrip
    private lateinit var errorView: TextView
    private val filePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && ::artemisChatViewModel.isInitialized) artemisChatViewModel.uploadAttachments(uris)
    }
    private lateinit var sendMessageButton: android.widget.ImageButton
    private lateinit var emptyStateView: View
    private lateinit var retryConnectionButton: Button
    private lateinit var chatMessageAdapter: ChatMessageAdapter
    private var isConfigurationUnavailable = false
    private var artemisUiHandleListenerRegistration: AutoCloseable? = null
    private val scrollFollow = ChatScrollFollow()
    private var submittedMessages: List<ChatMessage>? = null
    private var wasMinimized = false
    private var appliedPresentation: ArtemisPresentation? = null
    private var defaultHeaderView: View? = null
    private var defaultFooterView: View? = null
    private var customHeaderView: View? = null
    private var customHeaderTitle: String? = null
    private val customFooterEnabled = MutableStateFlow(false)
    private val chatTitle: String get() = arguments?.getString(ARG_CHAT_TITLE) ?: "Chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handleIdentifier = requireArguments().getString(ARG_HANDLE_IDENTIFIER).orEmpty()
        val resolvedUiHandle = ArtemisUI.resolveHandle(handleIdentifier)
        if (resolvedUiHandle == null) {
            isConfigurationUnavailable = true
            return
        }
        artemisUiHandle = resolvedUiHandle
        artemisChatViewModel = artemisUiHandle.chatViewModel ?: ArtemisChatViewModel(artemisUiHandle).also {
            artemisUiHandle.chatViewModel = it
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        if (isConfigurationUnavailable) return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                convertDpToPixels(24), convertDpToPixels(24),
                convertDpToPixels(24), convertDpToPixels(24),
            )
            addView(TextView(context).apply {
                text = context.getString(R.string.configuration_unavailable_reopen_chat)
                textSize = 16f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = context.getString(R.string.close)
                setOnClickListener {
                    if (activity is ArtemisChatActivity) (activity as ArtemisChatActivity).closeChat()
                    else parentFragmentManager.popBackStack()
                }
            })
        }
        val primaryColor = color(artemisUiHandle.config.theme.primary, Color.rgb(37, 99, 235))
        val chatRootView = inflater.inflate(R.layout.artemis_fragment_chat, container, false)
        artemisUiHandle.config.theme.background?.let { chatRootView.setBackgroundColor(color(it, Color.WHITE)) }
        defaultHeaderView = chatRootView.findViewById(R.id.artemis_header)
        defaultFooterView = chatRootView.findViewById(R.id.artemis_composer)
        chatRootView.findViewById<View>(R.id.artemis_header).setBackgroundColor(primaryColor)
        chatRootView.findViewById<TextView>(R.id.artemis_title).text = chatTitle
        chatRootView.findViewById<TextView>(R.id.artemis_avatar).text =
            chatTitle.trim().take(1).uppercase().ifBlank { "A" }
        chatRootView.findViewById<View>(R.id.artemis_minimize).setOnClickListener {
            wasMinimized = true
            artemisUiHandle.minimizeChat()
            dismissChat()
        }
        chatRootView.findViewById<View>(R.id.artemis_close).setOnClickListener { closeChat("CLOSE") }
        connectionStatusTextView = chatRootView.findViewById(R.id.artemis_status)
        emptyStateView = chatRootView.findViewById(R.id.artemis_empty)
        typingIndicatorTextView = chatRootView.findViewById(R.id.artemis_typing)
        retryConnectionButton = chatRootView.findViewById<Button>(R.id.artemis_retry).apply {
            setOnClickListener { artemisChatViewModel.retry() }
        }
        errorView = chatRootView.findViewById(R.id.artemis_error)
        // Capture references to the SDK header's status view before replacing that header.
        installCustomHeader(chatTitle)
        attachmentStrip = AttachmentComposerStrip(chatRootView, primaryColor,
            artemisChatViewModel::cancelAttachmentUpload, artemisChatViewModel::retryAttachment, artemisChatViewModel::removePendingAttachment)
        attachButton = chatRootView.findViewById<android.widget.ImageButton>(R.id.artemis_attach).apply {
            visibility = if (artemisChatViewModel.attachmentsEnabled) View.VISIBLE else View.GONE
            setOnClickListener { filePicker.launch(artemisUiHandle.config.attachment.allowedMimeTypes.toTypedArray()) }
        }
        messageInputEditText = chatRootView.findViewById<EditText>(R.id.artemis_input).apply {
            setText(artemisChatViewModel.composerDraft); setSelection(text.length)
        }
        sendMessageButton = chatRootView.findViewById<android.widget.ImageButton>(R.id.artemis_send).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { sendCurrentMessage() }
        }
        messageInputEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                artemisChatViewModel.updateComposerDraft(text?.toString().orEmpty())
                updateSendState()
                artemisChatViewModel.sendPresence(!text.isNullOrBlank())
                artemisChatViewModel.markActivity()
            }
            override fun afterTextChanged(editableText: android.text.Editable?) = Unit
        })
        messageInputEditText.setOnEditorActionListener { _, editorActionIdentifier, _ ->
            if (editorActionIdentifier == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
        }
        installCustomFooter()
        chatMessagesRecyclerView = chatRootView.findViewById<RecyclerView>(R.id.artemis_messages).apply {
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = true }
            itemAnimator = null
            setItemViewCacheSize(6)
            setHasFixedSize(true)
        }
        chatMessageAdapter = ChatMessageAdapter(
            features = artemisUiHandle.config.features,
            theme = artemisUiHandle.branding.theme,
            handle = artemisUiHandle,
            callbacks = object : ChatMessageAdapter.Callbacks {
                override fun onAuthorize(message: ChatMessage, rowId: String) = openAuthorization(message.messageId, rowId)
                override fun onCheckAuthorization(message: ChatMessage, rowId: String) = artemisChatViewModel.verifyAuthorization(message.messageId, rowId)
                override fun onCancelAuthorization(message: ChatMessage, rowId: String) = artemisChatViewModel.cancelAuthorization(message.messageId, rowId)
                override fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String, String>?, renderIdentifier: String?) =
                    artemisChatViewModel.submitAction(message.messageId, actionIdentifier, actionValue, formData, renderIdentifier)
                override fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?) {
                    message.serverMessageId?.let { serverMessageId ->
                        artemisChatViewModel.submitFeedback(serverMessageId, feedbackType, rating, feedbackText)
                    }
                }
                override fun isTemplateLocked(message: ChatMessage, actionIdentifier: String, renderId: String?) = artemisChatViewModel.isTemplateActionLocked(message.messageId, actionIdentifier, renderId)
                override fun templateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject) = artemisChatViewModel.templateDraft(message, renderId, scope, signature)
                override fun onTemplateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject, values: Map<String, String>) = artemisChatViewModel.updateTemplateDraft(message, renderId, scope, signature, values)
                override fun feedbackState(message: ChatMessage, data: com.google.gson.JsonObject) = artemisChatViewModel.feedbackState(message, data)
                override fun onFeedbackDraft(message: ChatMessage, data: com.google.gson.JsonObject, draft: FeedbackDraft) = artemisChatViewModel.updateFeedbackDraft(message, data, draft)
                override fun onFeedbackResponse(message: ChatMessage, data: com.google.gson.JsonObject, draft: FeedbackDraft) =
                    artemisChatViewModel.submitFeedbackResponse(message.messageId, draft, FeedbackContracts.renderId(message, data))
                override fun onAttachment(message: ChatMessage, attachment: AttachmentRef) {
                    artemisChatViewModel.openAttachment(attachment) { url ->
                        this@ArtemisChatFragment.context?.takeIf { view != null }?.let { ctx -> runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            .onFailure { artemisChatViewModel.contactCenterError(label(R.string.artemis_no_attachment_app)) } }
                    }
                }
                override fun onTransferSurvey(message: ChatMessage, score: Int, comments: String?) = artemisChatViewModel.submitTransferSurvey(message.messageId, score, comments)
                override fun surveyDraft(message: ChatMessage) = artemisChatViewModel.surveyDraft(message.messageId)
                override fun onSurveyDraft(message: ChatMessage, draft: SurveyDraft) = artemisChatViewModel.updateSurveyDraft(message.messageId, draft)
                override fun onContactCenterError(message: String) = artemisChatViewModel.contactCenterError(message)
                override fun isLocked(message: ChatMessage, actionIdentifier: String) =
                    artemisChatViewModel.isActionLocked(message.messageId, actionIdentifier)
            },
        )
        chatMessagesRecyclerView.adapter = chatMessageAdapter
        chatMessagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollFollow.scrollStateChanged(newState == RecyclerView.SCROLL_STATE_IDLE, !recyclerView.canScrollVertically(1))
            }
        })
        return chatRootView
    }

    private fun installCustomHeader(title: String) {
        val builder = artemisUiHandle.activeChatOptions.headerBuilder ?: return
        if (customHeaderTitle == title && customHeaderView != null) return
        val current = customHeaderView ?: defaultHeaderView ?: return
        val parent = current.parent as? ViewGroup ?: return
        val replacement = builder.build(requireContext(), ArtemisChatHeaderContext(
            title = title,
            onMinimize = {
                wasMinimized = true
                artemisUiHandle.minimizeChat()
                dismissChat()
            },
            onClose = { closeChat("CLOSE") },
        ))
        require(replacement.parent == null) { "Custom chat header must be an unattached view" }
        replaceChild(parent, current, replacement)
        customHeaderView = replacement
        customHeaderTitle = title
    }

    private fun installCustomFooter() {
        val builder = artemisUiHandle.activeChatOptions.footerBuilder ?: return
        val old = defaultFooterView ?: return
        val parent = old.parent as? ViewGroup ?: return
        val input = messageInputEditText
        val preview = old.findViewById<View>(R.id.artemis_attachment_preview)
        (input.parent as? ViewGroup)?.removeView(input)
        (preview.parent as? ViewGroup)?.removeView(preview)
        val replacement = builder.build(requireContext(), ArtemisChatFooterContext(
            input = input,
            enabled = customFooterEnabled.asStateFlow(),
            attachmentPreview = preview,
            onSend = ::sendCurrentMessage,
            onAttach = if (artemisChatViewModel.attachmentsEnabled) {
                { filePicker.launch(artemisUiHandle.config.attachment.allowedMimeTypes.toTypedArray()) }
            } else null,
        ))
        require(replacement.parent == null) { "Custom chat footer must be an unattached view" }
        replaceChild(parent, old, replacement)
        defaultFooterView = null
    }

    private fun replaceChild(parent: ViewGroup, old: View, replacement: View) {
        val index = parent.indexOfChild(old)
        val params = old.layoutParams
        parent.removeViewAt(index)
        parent.addView(replacement, index, params)
    }

    override fun onStart() {
        super.onStart()
        if (::artemisChatViewModel.isInitialized) artemisChatViewModel.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (isConfigurationUnavailable) return
        artemisUiHandleListenerRegistration = artemisUiHandle.addListener { event ->
            if (event is ArtemisUiEvent.Closed && event.reason in setOf("HANDLE_CLOSED", "END_SESSION")) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dismissChat()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(artemisChatViewModel.state, artemisUiHandle.presentation) { state, presentation -> state to presentation }.collect { (chatUiState, presentation) ->
                    applyPresentation(view, presentation)
                    connectionStatusTextView.text = when {
                        chatUiState.isOffline -> label(R.string.artemis_offline)
                        chatUiState.connectionStatus == ConnectionStatus.CONNECTED -> when (chatUiState.agentTransferStatus) {
                            AgentTransferStatus.QUEUED -> label(R.string.artemis_agent_waiting)
                            AgentTransferStatus.CONNECTED -> label(R.string.artemis_agent_connected)
                            else -> label(R.string.artemis_connected)
                        }
                        chatUiState.connectionStatus == ConnectionStatus.CONNECTING -> presentation.branding.connectingStatusText ?: label(R.string.artemis_connecting)
                        chatUiState.connectionStatus == ConnectionStatus.RECONNECTING -> label(R.string.artemis_reconnecting)
                        chatUiState.connectionStatus == ConnectionStatus.FAILED -> chatUiState.errorMessage ?: label(R.string.artemis_connection_failed)
                        else -> chatUiState.errorMessage ?: label(R.string.artemis_disconnected)
                    }
                    typingIndicatorTextView.text = chatUiState.activityText ?: if (chatUiState.agentTransferStatus == AgentTransferStatus.CONNECTED) label(R.string.artemis_agent_typing) else label(R.string.artemis_typing)
                    typingIndicatorTextView.visibility = if (artemisUiHandle.config.features.typingIndicator && (chatUiState.isTyping || chatUiState.initialAssistantMessagePending)) View.VISIBLE else View.GONE
                    retryConnectionButton.visibility = if (chatUiState.connectionStatus in setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED)) View.VISIBLE else View.GONE
                    val canSendMessages = chatUiState.connectionStatus == ConnectionStatus.CONNECTED && !chatUiState.isOffline
                    customFooterEnabled.value = canSendMessages
                    messageInputEditText.isEnabled = canSendMessages
                    attachButton.visibility = if (artemisChatViewModel.attachmentsEnabled) View.VISIBLE else View.GONE
                    attachButton.isEnabled = canSendMessages && chatUiState.attachmentDrafts.size < artemisChatViewModel.maxAttachmentsPerMessage
                    attachButton.alpha = if (attachButton.isEnabled) 1f else .45f
                    attachButton.contentDescription = if (chatUiState.attachmentDrafts.size >= artemisChatViewModel.maxAttachmentsPerMessage)
                        label(R.string.artemis_attachment_limit_reached, artemisChatViewModel.maxAttachmentsPerMessage) else label(R.string.artemis_attach_file)
                    attachmentStrip.render(chatUiState.attachmentDrafts, canSendMessages && artemisChatViewModel.attachmentsEnabled)
                    errorView.text = chatUiState.errorMessage.orEmpty()
                    errorView.visibility = if (chatUiState.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
                    updateSendState()
                    emptyStateView.visibility = if (chatUiState.messages.isEmpty()) View.VISIBLE else View.GONE
                    // Connection/status changes must not submit the same list or snap the user's scroll position.
                    if (submittedMessages != chatUiState.messages) {
                        submittedMessages = chatUiState.messages
                        chatMessageAdapter.submitList(chatUiState.messages) {
                            if (this@ArtemisChatFragment.view != null &&
                                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                scrollFollow.shouldFollow && chatMessageAdapter.itemCount > 0) {
                                chatMessagesRecyclerView.scrollToPosition(chatMessageAdapter.itemCount - 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun label(id: Int, vararg args: Any) = artemisUiHandle.presentation.value.strings.text(requireContext(), id, *args)

    private fun applyPresentation(root: View, value: ArtemisPresentation) {
        if (appliedPresentation == value) return
        val theme = value.branding.theme
        val primary = color(theme.primary, requireContext().getColor(R.color.artemis_primary))
        val foreground = color(theme.primaryForeground, if (androidx.core.graphics.ColorUtils.calculateLuminance(primary) > .5) Color.BLACK else Color.WHITE)
        val headerBackground = color(theme.headerBackground, primary)
        val headerText = color(theme.headerText, if (androidx.core.graphics.ColorUtils.calculateLuminance(headerBackground) > .5) Color.BLACK else Color.WHITE)
        root.setBackgroundColor(color(theme.background, requireContext().getColor(R.color.artemis_background)))
        val title = value.branding.assistantName ?: chatTitle
        installCustomHeader(title)
        defaultHeaderView?.let { header ->
            header.setBackgroundColor(headerBackground)
            header.findViewById<TextView>(R.id.artemis_title).apply { text = title; setTextColor(headerText) }
            connectionStatusTextView.setTextColor(headerText)
            val avatar = header.findViewById<TextView>(R.id.artemis_avatar)
            avatar.text = title.trim().take(1).uppercase().ifBlank { "A" }
            avatar.setTextColor(headerText)
            val logo = header.findViewById<android.widget.ImageView>(R.id.artemis_logo)
            // Resolvers are host-controlled local asset lookup. No image HTTP request is made here.
            if (appliedPresentation == null || appliedPresentation?.branding?.logoUrl != value.branding.logoUrl || appliedPresentation?.logoResolver !== value.logoResolver) {
                val drawable = value.branding.logoUrl?.let { url -> runCatching { value.logoResolver?.resolve(requireContext(), url) }.getOrNull() }
                logo.setImageDrawable(drawable)
                logo.visibility = if (drawable == null) View.GONE else View.VISIBLE
                avatar.visibility = if (drawable == null) View.VISIBLE else View.GONE
            }
            listOf(R.id.artemis_close to R.string.artemis_close, R.id.artemis_minimize to R.string.artemis_minimize).forEach { (id, text) ->
                header.findViewById<android.widget.ImageButton>(id).apply {
                    imageTintList = android.content.res.ColorStateList.valueOf(headerText); contentDescription = label(text)
                }
            }
        }
        val border = color(theme.border, requireContext().getColor(R.color.artemis_border))
        defaultFooterView?.apply {
            setBackgroundColor(color(theme.composerBackground, requireContext().getColor(R.color.artemis_background)))
            findViewById<View>(R.id.artemis_composer_divider).setBackgroundColor(border)
        }
        (messageInputEditText.background.mutate() as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(color(theme.surface, requireContext().getColor(R.color.artemis_background)))
            setStroke(convertDpToPixels(1).coerceAtLeast(1), border)
        }
        messageInputEditText.setTextColor(color(theme.composerText ?: theme.text, requireContext().getColor(R.color.artemis_text)))
        messageInputEditText.setHintTextColor(color(theme.composerPlaceholder ?: theme.mutedText, requireContext().getColor(R.color.artemis_muted)))
        messageInputEditText.hint = value.branding.placeholderText ?: label(R.string.artemis_hint)
        sendMessageButton.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
        sendMessageButton.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        sendMessageButton.contentDescription = label(R.string.artemis_send)
        attachButton.imageTintList = android.content.res.ColorStateList.valueOf(color(theme.composerText ?: theme.text, requireContext().getColor(R.color.artemis_text)))
        val welcome = value.branding.welcomeMessage?.trim()?.takeIf(String::isNotEmpty)
        root.findViewById<TextView>(R.id.artemis_empty_title).apply {
            text = welcome ?: label(R.string.artemis_empty_title)
            setTextColor(color(theme.text, requireContext().getColor(R.color.artemis_text)))
        }
        root.findViewById<TextView>(R.id.artemis_empty_body).apply {
            text = label(R.string.artemis_empty_body)
            visibility = if (welcome == null) View.VISIBLE else View.GONE
            setTextColor(color(theme.mutedText, requireContext().getColor(R.color.artemis_muted)))
        }
        typingIndicatorTextView.setTextColor(color(theme.mutedText, requireContext().getColor(R.color.artemis_muted)))
        retryConnectionButton.text = label(R.string.artemis_retry)
        retryConnectionButton.setTextColor(primary)
        errorView.setTextColor(color(theme.text, requireContext().getColor(R.color.artemis_text)))
        attachmentStrip.updatePresentation(primary, value.strings)
        chatMessageAdapter.updatePresentation(value)
        value.typography.applyTo(root)
        appliedPresentation = value
    }

    private fun updateSendState() {
        val state = artemisChatViewModel.state.value
        val hasAttachments = state.attachmentDrafts.isNotEmpty()
        sendMessageButton.isEnabled = messageInputEditText.isEnabled && !state.attachmentUploading && !state.attachmentUploadFailed &&
            (!hasAttachments || artemisChatViewModel.attachmentsEnabled) && (!messageInputEditText.text.isNullOrBlank() || hasAttachments)
        sendMessageButton.background.mutate().alpha = if (sendMessageButton.isEnabled) 255 else 128
    }

    private fun sendCurrentMessage() {
        if (!messageInputEditText.isEnabled) return
        val messageText = messageInputEditText.text?.toString().orEmpty()
        if (messageText.isBlank() && artemisChatViewModel.state.value.attachmentDrafts.isEmpty()) return
        scrollFollow.userSentMessage()
        if (artemisChatViewModel.sendComposedMessage(messageText)) messageInputEditText.text?.clear()
    }

    private fun closeChat(reason: String) {
        artemisChatViewModel.closeSession(reason)
        dismissChat()
    }

    private fun dismissChat() {
        if (activity is ArtemisChatActivity) (activity as ArtemisChatActivity).closeChat()
        else if (isAdded && !parentFragmentManager.isStateSaved) {
            parentFragmentManager.beginTransaction().remove(this).commit()
        }
    }

    override fun onDestroy() {
        if (::artemisUiHandle.isInitialized && !wasMinimized && activity?.isChangingConfigurations != true) {
            artemisChatViewModel.closeSession("VIEW_DESTROYED")
        }
        super.onDestroy()
    }

    private fun openAuthorization(messageId: String, rowId: String) {
        val request = artemisChatViewModel.beginAuthorization(messageId, rowId) ?: return
        runCatching {
            val launcher = artemisUiHandle.authorizationLauncher
            if (launcher != null) launcher.launch(request)
            else {
                artemisChatViewModel.trackAuthorizationBrowser(request)
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(request.authorizationUrl)).apply {
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                })
            }
        }.onFailure {
            artemisChatViewModel.completeAuthorization(request, com.artemis.uisdk.ArtemisAuthorizationResult.FAILED)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::artemisChatViewModel.isInitialized) artemisChatViewModel.authorizationBrowserReturned()
    }

    override fun onStop() {
        if (::artemisChatViewModel.isInitialized) artemisChatViewModel.authorizationBrowserDeparted()
        if (::artemisChatViewModel.isInitialized) artemisChatViewModel.sendPresence(false)
        super.onStop()
    }

    override fun onDestroyView() {
        artemisUiHandleListenerRegistration?.close()
        artemisUiHandleListenerRegistration = null
        if (::chatMessagesRecyclerView.isInitialized) chatMessagesRecyclerView.adapter = null
        submittedMessages = null
        appliedPresentation = null
        super.onDestroyView()
    }

    private fun convertDpToPixels(densityIndependentPixels: Int) =
        (densityIndependentPixels * resources.displayMetrics.density).toInt()

    private fun color(colorValue: String?, fallbackColor: Int) =
        runCatching { Color.parseColor(colorValue) }.getOrDefault(fallbackColor)
}
