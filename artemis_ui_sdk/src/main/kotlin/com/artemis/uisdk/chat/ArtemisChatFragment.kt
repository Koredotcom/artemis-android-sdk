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
import kotlinx.coroutines.launch

class ArtemisChatFragment : Fragment() {
    companion object {
        private const val ARG_HANDLE = "handle"
        private const val ARG_TITLE = "title"
        fun create(handleId: String, title: String) = ArtemisChatFragment().apply {
            arguments = Bundle().apply { putString(ARG_HANDLE, handleId); putString(ARG_TITLE, title) }
        }
    }

    private lateinit var handle: ArtemisUiHandle
    private lateinit var vm: ArtemisChatViewModel
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var typing: TextView
    private lateinit var input: EditText
    private lateinit var send: android.widget.ImageButton
    private lateinit var empty: View
    private lateinit var retry: Button
    private lateinit var adapter: ChatMessageAdapter
    private var unavailable = false
    private var handleRegistration: AutoCloseable? = null
    private var stickToBottom = true
    private val titleText get() = arguments?.getString(ARG_TITLE) ?: "Chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requireArguments().getString(ARG_HANDLE).orEmpty()
        val resolved = ArtemisUI.resolve(id)
        if (resolved == null) {
            unavailable = true
            return
        }
        handle = resolved
        vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ArtemisChatViewModel(handle) as T
        })[ArtemisChatViewModel::class.java]
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        if (unavailable) return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = "Configuration unavailable. Reopen chat."
                textSize = 16f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Close"
                setOnClickListener {
                    if (activity is ArtemisChatActivity) (activity as ArtemisChatActivity).closeChat()
                    else parentFragmentManager.popBackStack()
                }
            })
        }
        val primary = color(handle.config.theme.primary, Color.rgb(37, 99, 235))
        val root = inflater.inflate(com.artemis.uisdk.R.layout.artemis_fragment_chat, container, false)
        handle.config.theme.background?.let { root.setBackgroundColor(color(it, Color.WHITE)) }
        root.findViewById<View>(com.artemis.uisdk.R.id.artemis_header).setBackgroundColor(primary)
        root.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_title).text = titleText
        root.findViewById<TextView>(com.artemis.uisdk.R.id.artemis_avatar).text =
            titleText.trim().take(1).uppercase().ifBlank { "A" }
        root.findViewById<View>(com.artemis.uisdk.R.id.artemis_minimize).setOnClickListener { closeChat("MINIMIZE") }
        root.findViewById<View>(com.artemis.uisdk.R.id.artemis_close).setOnClickListener { closeChat("CLOSE") }
        status = root.findViewById(com.artemis.uisdk.R.id.artemis_status)
        empty = root.findViewById(com.artemis.uisdk.R.id.artemis_empty)
        typing = root.findViewById(com.artemis.uisdk.R.id.artemis_typing)
        retry = root.findViewById<Button>(com.artemis.uisdk.R.id.artemis_retry).apply {
            setOnClickListener { vm.retry() }
        }
        input = root.findViewById(com.artemis.uisdk.R.id.artemis_input)
        send = root.findViewById<android.widget.ImageButton>(com.artemis.uisdk.R.id.artemis_send).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            setOnClickListener { sendCurrentMessage() }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSendState()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
        }
        list = root.findViewById<RecyclerView>(com.artemis.uisdk.R.id.artemis_messages).apply {
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = true }
            itemAnimator = null
        }
        adapter = ChatMessageAdapter(
            features = handle.config.features,
            theme = handle.config.theme,
            callbacks = object : ChatMessageAdapter.Callbacks {
                override fun onAction(message: ChatMessage, id: String, value: String?, data: Map<String, String>?, renderId: String?) =
                    vm.submitAction(message.id, id, value, data, renderId)
                override fun onFeedback(message: ChatMessage, type: String, rating: Int, text: String?) {
                    message.serverId?.let { vm.submitFeedback(it, type, rating, text) }
                }
                override fun isLocked(message: ChatMessage, actionId: String) = vm.isActionLocked(message.id, actionId)
            },
        )
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                stickToBottom = lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
            }
        })
        return root
    }

    override fun onStart() { super.onStart(); if (::vm.isInitialized) vm.start() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (unavailable) return
        handleRegistration = handle.addListener { event ->
            if (event is ArtemisUiEvent.Closed && event.reason == "HANDLE_CLOSED") {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    dismissChat()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    status.text = when {
                        state.offline -> "No internet connection"
                        state.status == ConnectionStatus.CONNECTED -> "Connected"
                        state.status == ConnectionStatus.CONNECTING -> "Connecting…"
                        state.status == ConnectionStatus.RECONNECTING -> "Reconnecting…"
                        state.status == ConnectionStatus.FAILED -> state.error ?: "Connection failed"
                        else -> state.error ?: "Disconnected"
                    }
                    typing.visibility = if (handle.config.features.typingIndicator && state.typing) View.VISIBLE else View.GONE
                    retry.visibility = if (state.status in setOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.FAILED)) View.VISIBLE else View.GONE
                    val enabled = state.status == ConnectionStatus.CONNECTED && !state.offline
                    input.isEnabled = enabled
                    updateSendState()
                    empty.visibility = if (state.messages.isEmpty()) View.VISIBLE else View.GONE
                    adapter.submitList(state.messages) {
                        if (this@ArtemisChatFragment.view != null &&
                            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                            stickToBottom && adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            }
        }
    }

    private fun updateSendState() {
        send.isEnabled = input.isEnabled && !input.text.isNullOrBlank()
        send.alpha = if (send.isEnabled) 1f else .35f
    }

    private fun sendCurrentMessage() {
        if (!input.isEnabled) return
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) return
        vm.sendText(text)
        input.text?.clear()
        stickToBottom = true
    }

    private fun closeChat(reason: String) {
        vm.closeSession(reason)
        dismissChat()
    }

    private fun dismissChat() {
        if (activity is ArtemisChatActivity) (activity as ArtemisChatActivity).closeChat()
        else if (isAdded && !parentFragmentManager.isStateSaved) {
            parentFragmentManager.beginTransaction().remove(this).commit()
        }
    }

    override fun onDestroyView() {
        handleRegistration?.close()
        handleRegistration = null
        if (::list.isInitialized) list.adapter = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(value: String?, fallback: Int) = runCatching { Color.parseColor(value) }.getOrDefault(fallback)
}
