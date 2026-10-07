package com.artemis.uisdk.chat

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.google.gson.JsonParser

/** Offline gallery, packaged only in debug builds. Never initializes the socket SDK. */
class TemplatePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (intent.getBooleanExtra("dark", false))
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        setContentView(R.layout.artemis_fragment_chat)
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        findViewById<TextView>(R.id.artemis_title).text = "Artemis Assistant"
        findViewById<TextView>(R.id.artemis_status).text = "Template preview · Offline"
        findViewById<View>(R.id.artemis_empty).visibility = View.GONE
        findViewById<View>(R.id.artemis_close).setOnClickListener { finish() }
        findViewById<View>(R.id.artemis_minimize).setOnClickListener { finish() }
        val adapter = ChatMessageAdapter(ArtemisFeatureConfig(), ArtemisThemeConfig(),
            object : ChatMessageAdapter.Callbacks {
                override fun onAction(message: ChatMessage, id: String, value: String?, data: Map<String, String>?, renderId: String?) {
                    Toast.makeText(this@TemplatePreviewActivity, "Preview action: $id", Toast.LENGTH_SHORT).show()
                }
                override fun onFeedback(message: ChatMessage, type: String, rating: Int, text: String?) {
                    Toast.makeText(this@TemplatePreviewActivity, "Preview rating: $rating", Toast.LENGTH_SHORT).show()
                }
                override fun isLocked(message: ChatMessage, actionId: String) = false
            })
        val list = findViewById<RecyclerView>(R.id.artemis_messages)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val examples = listOf(
            """{"quick_replies":[{"id":"orders","label":"Track my order"},{"id":"products","label":"Explore products"},{"id":"help","label":"Talk to support"}]}""",
            """{"kpi":{"label":"Total savings this month","value":"2,480","unit":"USD","trend":"+12.8% compared with last month"}}""",
            """{"carousel":{"cards":[{"title":"Everyday essentials","subtitle":"Thoughtful picks for your day","buttons":[{"id":"explore","label":"Explore collection"}]},{"title":"Something special","subtitle":"Discover our latest arrivals","buttons":[{"id":"new","label":"See what is new"}]}]}}""",
            """{"list":{"title":"Your recent orders","items":[{"title":"Order #1024","subtitle":"Out for delivery · Today"},{"title":"Order #1023","subtitle":"Delivered · Yesterday"}]}}""",
            """{"table":{"columns":[{"key":"item","header":"Item"},{"key":"status","header":"Status"}],"rows":[{"item":"Essentials","status":"Delivered"},{"item":"New arrivals","status":"In transit"}]}}""",
            """{"progress":{"label":"Your request is being processed","value":72,"max":100}}""",
            """{"progress":{"label":"Profile completed","value":75,"max":100,"variant":"circle"}}""",
            """{"chart":{"title":"Weekly activity","type":"bar","data":[{"label":"Mon","value":12},{"label":"Tue","value":24},{"label":"Wed","value":18},{"label":"Thu","value":32}]}}""",
            """{"form":{"title":"How can we reach you?","fields":[{"id":"name","type":"input","label":"Name","required":true},{"id":"email","type":"input","label":"Email","input_type":"email","required":true}],"submit_label":"Send details"}}""",
            """{"feedback":{"prompt":"How was your experience?","type":"stars","max":5}}""",
            """{"file":{"filename":"Order confirmation.pdf","size_bytes":184320,"url":"https://example.com/confirmation.pdf"}}""",
            """{"html":"<b>Rich content</b><p>Native templates keep content structured and easy to read.</p>"}"""
        )
        val messages = mutableListOf(
            ChatMessage("welcome", role = MessageRole.ASSISTANT, text = "Hello! How can I help you today?"),
            ChatMessage("user", role = MessageRole.USER, text = "Show me what I can do here."),
            ChatMessage(
                "markdown-example",
                role = MessageRole.ASSISTANT,
                text = "Sure — I can help with a scheduling or Gmail planning issue that needs human review.\n\n" +
                    "Please tell me:\n1. What went wrong\n2. What action you want taken\n" +
                    "3. Any relevant people, dates, or times involved\n\n" +
                    "If you already have a reference, ticket, or case ID, send that too.",
            ),
        )
        messages += examples.mapIndexed { index, json ->
            ChatMessage("template-$index", serverId = "preview-$index", role = MessageRole.ASSISTANT,
                text = "", richContent = JsonParser.parseString(json).asJsonObject)
        }
        adapter.submitList(messages.toList()) {
            list.scrollToPosition(intent.getIntExtra("position", 0).coerceIn(0, messages.lastIndex))
        }
        val input = findViewById<EditText>(R.id.artemis_input)
        findViewById<View>(R.id.artemis_send).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                messages += ChatMessage("local-${messages.size}", role = MessageRole.USER, text = text)
                adapter.submitList(messages.toList()) { list.scrollToPosition(messages.lastIndex) }
                input.text.clear()
            }
        }
    }
}
