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
import androidx.lifecycle.ViewModelProvider
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
    private lateinit var sendMessageButton: android.widget.ImageButton
    private lateinit var emptyStateView: View
    private lateinit var retryConnectionButton: Button
    private lateinit var chatMessageAdapter: ChatMessageAdapter
    private var isConfigurationUnavailable = false
    private var artemisUiHandleListenerRegistration: AutoCloseable? = null
    private var shouldStickToBottom = true
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
        artemisChatViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ArtemisChatViewModel(artemisUiHandle) as T
        })[ArtemisChatViewModel::class.java]
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
        chatRootView.findViewById<View>(R.id.artemis_header).setBackgroundColor(primaryColor)
        chatRootView.findViewById<TextView>(R.id.artemis_title).text = chatTitle
        chatRootView.findViewById<TextView>(R.id.artemis_avatar).text =
            chatTitle.trim().take(1).uppercase().ifBlank { "A" }
        chatRootView.findViewById<View>(R.id.artemis_minimize).setOnClickListener { closeChat("MINIMIZE") }
        chatRootView.findViewById<View>(R.id.artemis_close).setOnClickListener { closeChat("CLOSE") }
        connectionStatusTextView = chatRootView.findViewById(R.id.artemis_status)
        emptyStateView = chatRootView.findViewById(R.id.artemis_empty)
        typingIndicatorTextView = chatRootView.findViewById(R.id.artemis_typing)
        retryConnectionButton = chatRootView.findViewById<Button>(R.id.artemis_retry).apply {
            setOnClickListener { artemisChatViewModel.retry() }
        }
        messageInputEditText = chatRootView.findViewById(R.id.artemis_input)
        sendMessageButton = chatRootView.findViewById<android.widget.ImageButton>(R.id.artemis_send).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            setOnClickListener { sendCurrentMessage() }
        }
        messageInputEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = updateSendState()
            override fun afterTextChanged(editableText: android.text.Editable?) = Unit
        })
        messageInputEditText.setOnEditorActionListener { _, editorActionIdentifier, _ ->
            if (editorActionIdentifier == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
        }
        chatMessagesRecyclerView = chatRootView.findViewById<RecyclerView>(R.id.artemis_messages).apply {
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = true }
            itemAnimator = null
        }
        chatMessageAdapter = ChatMessageAdapter(
            features = artemisUiHandle.config.features,
            theme = artemisUiHandle.config.theme,
            callbacks = object : ChatMessageAdapter.Callbacks {
                override fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String, String>?, renderIdentifier: String?) =
                    artemisChatViewModel.submitAction(message.messageId, actionIdentifier, actionValue, formData, renderIdentifier)
                override fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?) {
                    message.serverMessageId?.let { serverMessageId ->
                        artemisChatViewModel.submitFeedback(serverMessageId, feedbackType, rating, feedbackText)
                    }
                }
                override fun isLocked(message: ChatMessage, actionIdentifier: String) =
                    artemisChatViewModel.isActionLocked(message.messageId, actionIdentifier)
            },
        )
        chatMessagesRecyclerView.adapter = chatMessageAdapter
        chatMessagesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val messageLayoutManager = recyclerView.layoutManager as LinearLayoutManager
                shouldStickToBottom = messageLayoutManager.findLastVisibleItemPosition() >= chatMessageAdapter.itemCount - 2
            }
        })
        return chatRootView
    }

    override fun onStart() {
        super.onStart()
        if (::artemisChatViewModel.isInitialized) artemisChatViewModel.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (isConfigurationUnavailable) return
        artemisUiHandleListenerRegistration = artemisUiHandle.addListener { event ->
            if (event is ArtemisUiEvent.Closed && event.reason == "HANDLE_CLOSED") {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dismissChat()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                artemisChatViewModel.state.collect { chatUiState ->
                    connectionStatusTextView.text = when {
                        chatUiState.isOffline -> "No internet connection"
                        chatUiState.connectionStatus == ConnectionStatus.CONNECTED -> "Connected"
                        chatUiState.connectionStatus == ConnectionStatus.CONNECTING -> "Connecting…"
                        chatUiState.connectionStatus == ConnectionStatus.RECONNECTING -> "Reconnecting…"
                        chatUiState.connectionStatus == ConnectionStatus.FAILED -> chatUiState.errorMessage ?: "Connection failed"
                        else -> chatUiState.errorMessage ?: "Disconnected"
                    }
                    typingIndicatorTextView.visibility = if (artemisUiHandle.config.features.typingIndicator && chatUiState.isTyping) View.VISIBLE else View.GONE
                    retryConnectionButton.visibility = if (chatUiState.connectionStatus in setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED)) View.VISIBLE else View.GONE
                    val canSendMessages = chatUiState.connectionStatus == ConnectionStatus.CONNECTED && !chatUiState.isOffline
                    messageInputEditText.isEnabled = canSendMessages
                    updateSendState()
                    emptyStateView.visibility = if (chatUiState.messages.isEmpty()) View.VISIBLE else View.GONE
                    chatMessageAdapter.submitList(chatUiState.messages) {
                        if (this@ArtemisChatFragment.view != null &&
                            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            shouldStickToBottom && chatMessageAdapter.itemCount > 0) {
                            chatMessagesRecyclerView.scrollToPosition(chatMessageAdapter.itemCount - 1)
                        }
                    }
                }
            }
        }
    }

    private fun updateSendState() {
        sendMessageButton.isEnabled = messageInputEditText.isEnabled && !messageInputEditText.text.isNullOrBlank()
        sendMessageButton.alpha = if (sendMessageButton.isEnabled) 1f else .35f
    }

    private fun sendCurrentMessage() {
        if (!messageInputEditText.isEnabled) return
        val messageText = messageInputEditText.text?.toString().orEmpty()
        if (messageText.isBlank()) return
        artemisChatViewModel.sendText(messageText)
        messageInputEditText.text?.clear()
        shouldStickToBottom = true
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

    override fun onDestroyView() {
        artemisUiHandleListenerRegistration?.close()
        artemisUiHandleListenerRegistration = null
        if (::chatMessagesRecyclerView.isInitialized) chatMessagesRecyclerView.adapter = null
        super.onDestroyView()
    }

    private fun convertDpToPixels(densityIndependentPixels: Int) =
        (densityIndependentPixels * resources.displayMetrics.density).toInt()

    private fun color(colorValue: String?, fallbackColor: Int) =
        runCatching { Color.parseColor(colorValue) }.getOrDefault(fallbackColor)
}
