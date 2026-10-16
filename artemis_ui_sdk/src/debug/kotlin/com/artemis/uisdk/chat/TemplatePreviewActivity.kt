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
        val previewRootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(previewRootView) { previewView, windowInsets ->
            val systemAndKeyboardInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            previewView.setPadding(
                systemAndKeyboardInsets.left,
                systemAndKeyboardInsets.top,
                systemAndKeyboardInsets.right,
                systemAndKeyboardInsets.bottom,
            )
            windowInsets
        }
        findViewById<TextView>(R.id.artemis_title).text = "Artemis Assistant"
        findViewById<TextView>(R.id.artemis_status).text = "Template preview · Offline"
        findViewById<View>(R.id.artemis_empty).visibility = View.GONE
        findViewById<View>(R.id.artemis_close).setOnClickListener { finish() }
        findViewById<View>(R.id.artemis_minimize).setOnClickListener { finish() }
        val contactCenterPreview = intent.getBooleanExtra("contactCenter", false)
        val drafts = mutableMapOf<String, SurveyDraft>()
        val fieldDrafts = mutableMapOf<String, Pair<com.google.gson.JsonObject, Map<String, String>>>()
        val chatMessageAdapter = ChatMessageAdapter(ArtemisFeatureConfig(attachments = contactCenterPreview, contactCenter = contactCenterPreview, authentication = true), ArtemisThemeConfig(),
            object : ChatMessageAdapter.Callbacks {
                override fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String, String>?, renderIdentifier: String?) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline action: $actionIdentifier · value=$actionValue · render=$renderIdentifier", Toast.LENGTH_LONG).show()
                }
                override fun templateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject) =
                    fieldDrafts[TemplateContracts.draftKey(message, renderId, scope)]?.takeIf { it.first == signature }?.second.orEmpty()
                override fun onTemplateDraft(message: ChatMessage, renderId: String?, scope: String, signature: com.google.gson.JsonObject, values: Map<String, String>) {
                    fieldDrafts[TemplateContracts.draftKey(message, renderId, scope)] = signature.deepCopy() to values.toMap()
                }
                override fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?) {
                    Toast.makeText(this@TemplatePreviewActivity, "Preview rating: $rating", Toast.LENGTH_SHORT).show()
                }
                override fun onAttachment(message: ChatMessage, attachment: AttachmentRef) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline preview: ${attachment.filename}", Toast.LENGTH_SHORT).show()
                }
                override fun onAuthorize(message: ChatMessage, rowId: String) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline preview: sign-in is not launched", Toast.LENGTH_SHORT).show()
                }
                override fun onCheckAuthorization(message: ChatMessage, rowId: String) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline preview: Runtime confirmation required", Toast.LENGTH_SHORT).show()
                }
                override fun onCancelAuthorization(message: ChatMessage, rowId: String) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline preview: no workflow was cancelled", Toast.LENGTH_SHORT).show()
                }
                override fun surveyDraft(message: ChatMessage) = drafts[message.messageId] ?: SurveyDraft()
                override fun onSurveyDraft(message: ChatMessage, draft: SurveyDraft) { drafts[message.messageId] = draft }
                override fun onTransferSurvey(message: ChatMessage, score: Int, comments: String?) {
                    Toast.makeText(this@TemplatePreviewActivity, "Offline preview rating: $score", Toast.LENGTH_SHORT).show()
                }
                override fun isLocked(message: ChatMessage, actionIdentifier: String) = false
            })
        val chatMessagesRecyclerView = findViewById<RecyclerView>(R.id.artemis_messages)
        chatMessagesRecyclerView.layoutManager = LinearLayoutManager(this)
        chatMessagesRecyclerView.itemAnimator = null
        chatMessagesRecyclerView.setItemViewCacheSize(6)
        chatMessagesRecyclerView.setHasFixedSize(true)
        chatMessagesRecyclerView.adapter = chatMessageAdapter
        val richContentExamples = listOf(
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
        val additionalExamples = listOf(
            """{"form":{"title":"Template parity: required fields and pickers","fields":[{"id":"email","type":"input","label":"Email","input_type":"email","required":true,"description":"Must be a valid address"},{"id":"date","type":"input","label":"Date","input_type":"date","required":true},{"id":"time","type":"input","label":"Time","input_type":"time","required":true},{"id":"queue","type":"select","label":"Team","placeholder":"Choose a team","required":true,"options":[{"id":"sales","label":"Sales","description":"Product questions"},{"id":"support","label":"Support","description":"Existing requests"}]},{"id":"details","type":"button","label":"View details","value":"details"}],"submit_label":"Send request"}}""",
            """{"chart":{"title":"Signed values","type":"bar","data":[{"label":"Loss","value":-20,"color":"#DC2626"},{"label":"Zero","value":0},{"label":"Gain","value":15,"color":"#16A34A"}]}}""",
            """{"chart":{"title":"Negative trend","type":"line","data":[{"label":"A","value":-10},{"label":"B","value":-5},{"label":"C","value":-12}]}}""",
            """{"chart":{"title":"Pie shares","type":"pie","data":[{"label":"A","value":2},{"label":"B","value":3},{"label":"C","value":5}]}}""",
            """{"table":{"columns":[{"key":"item","header":"Item","align":"left"},{"key":"amount","header":"Amount","align":"right"}],"max_visible_rows":1,"rows":[{"item":"One","amount":10},{"item":"Two","amount":20},{"item":"Three","amount":30}]}}""",
            """{"markdown":"## Markdown table\n\n| Item | Amount |\n| :--- | ---: |\n| **One** | 10 |\n| Two | 20 |\n\n- [x] Selected task\n- [ ] Pending task\n\n[Reference][guide]\n\n[guide]: https://example.com/guide"}""",
            """{"adaptive_card":"{\"body\":[{\"type\":\"TextBlock\",\"text\":\"Readable channel preview\"}]}"}"""
        )
        val previewMessages = mutableListOf(
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
        previewMessages += (richContentExamples + additionalExamples).mapIndexed { exampleIndex, exampleJson ->
            ChatMessage("template-$exampleIndex", serverMessageId = "preview-$exampleIndex", role = MessageRole.ASSISTANT,
                text = "", richContent = JsonParser.parseString(exampleJson).asJsonObject)
        }
        previewMessages += ChatMessage("canonical-form", "canonical-form", MessageRole.ASSISTANT, "", renderables = JsonParser.parseString("""[{"name":"form","renderId":"offline-render-1","payload":{"title":"Canonical render-ID form","fields":[{"id":"count","type":"input","label":"Count","input_type":"number","required":true}],"submit_label":"Submit"}}]""").asJsonArray)
        previewMessages += ChatMessage("auth-preflight", role = MessageRole.SYSTEM, text = "", authorization = AuthorizationCard("offline", AuthorizationKind.PREFLIGHT, listOf(
            AuthorizationRow("calendar", "Calendar", "https://example.com/authorize", authProfileRef = "calendar", connector = "calendar", blocking = true),
            AuthorizationRow("directory", "Company directory", status = AuthorizationStatus.CONNECTED),
        )))
        previewMessages += ChatMessage("auth-challenge", role = MessageRole.SYSTEM, text = "", authorization = AuthorizationCard("offline", AuthorizationKind.CHALLENGE, listOf(
            AuthorizationRow("crm-call", "CRM", "https://example.com/authorize", prompt = "Authorize CRM access to continue this request.", canCancel = true),
            AuthorizationRow("mail-call", "Mail", "https://example.com/authorize", canCancel = true, status = AuthorizationStatus.FAILED, attempted = true),
        )))
        if (contactCenterPreview) {
            findViewById<TextView>(R.id.artemis_status).text = "Human-agent preview · Offline"
            previewMessages.clear()
            previewMessages += listOf(
                ChatMessage("queue", role = MessageRole.SYSTEM, text = "Waiting for a human agent…"),
                ChatMessage("connected", role = MessageRole.SYSTEM, text = "Connected to a human agent."),
                ChatMessage("agent", role = MessageRole.ASSISTANT, text = "Hello, I can help with your request."),
                ChatMessage("attachment", role = MessageRole.ASSISTANT, text = "Your document", attachments = listOf(AttachmentRef("offline-file", "confirmation.pdf", "application/pdf", 12345))),
                ChatMessage("failed-file", role = MessageRole.USER, text = "", attachments = listOf(AttachmentRef("failed", "report.pdf", deliveryFailed = true))),
                ChatMessage("form", role = MessageRole.ASSISTANT, text = "Please complete this secure form.", agentLink = AgentLink("https://example.com/form", "Open form", AgentLinkKind.FORM)),
                ChatMessage("survey", role = MessageRole.ASSISTANT, text = "How was your support experience?", transferSurvey = AgentTransferSurvey("nps", "offline-transfer", commentVisibility = "conditional", commentRatings = listOf(0,1,2,3,4,5,6))),
                ChatMessage("survey-done", role = MessageRole.ASSISTANT, text = "Support feedback", transferSurvey = AgentTransferSurvey("csat", "offline-done", status = SurveySubmissionStatus.SUBMITTED)),
            )
        }
        intent.getStringExtra("example")?.let { exampleId ->
            val focused = previewMessages.filter { it.messageId == exampleId }
            if (focused.isNotEmpty()) { previewMessages.clear(); previewMessages.addAll(focused) }
        }
        chatMessageAdapter.submitList(previewMessages.toList()) {
            chatMessagesRecyclerView.scrollToPosition(intent.getIntExtra("position", 0).coerceIn(0, previewMessages.lastIndex))
        }
        val messageInputEditText = findViewById<EditText>(R.id.artemis_input)
        findViewById<View>(R.id.artemis_send).setOnClickListener {
            val messageText = messageInputEditText.text.toString().trim()
            if (messageText.isNotEmpty()) {
                previewMessages += ChatMessage("local-${previewMessages.size}", role = MessageRole.USER, text = messageText)
                chatMessageAdapter.submitList(previewMessages.toList()) {
                    chatMessagesRecyclerView.scrollToPosition(previewMessages.lastIndex)
                }
                messageInputEditText.text.clear()
            }
        }
    }
}
