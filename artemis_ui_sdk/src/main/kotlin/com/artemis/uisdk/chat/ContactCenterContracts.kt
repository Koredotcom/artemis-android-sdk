package com.artemis.uisdk.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest

enum class AgentTransferStatus { NONE, QUEUED, CONNECTED, DISCONNECTED }
data class AgentMediaInvite(
    val callType: String,
    val conversationId: String?,
    val providerConversationId: String?,
    val providerAccountId: String?,
    val mediaSessionId: String?,
    val idempotencyKey: String?,
    val callId: String? = null,
    val displayName: String? = null,
)
data class AgentMediaIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)
data class AgentMediaEndpoint(
    val callId: String,
    val mediaSessionId: String,
    val callType: String,
    val conversationId: String?,
    val providerConversationId: String?,
    val sipUri: String?,
    val sipUser: String?,
    val sipPassword: String?,
    val addresses: List<String>,
    val domain: String?,
    val iceServers: List<AgentMediaIceServer>,
    val customerUserId: String?,
    val customerSipUser: String?,
    val customerSipPassword: String?,
    val customerSipAuthUser: String?,
    val customerDisplayName: String?,
)
data class AgentMediaCallTerminated(val callId: String?, val reason: String?)
data class AgentMediaSbcDetails(
    val requestId: String?,
    val ready: Boolean,
    val addresses: List<String>,
    val domain: String?,
    val iceServers: List<AgentMediaIceServer>,
)
internal sealed interface ContactCenterMediaFrame {
    data class Incoming(val call: AgentMediaInvite) : ContactCenterMediaFrame
    data class EndpointReleased(val endpoint: AgentMediaEndpoint) : ContactCenterMediaFrame
    data class Terminated(val call: AgentMediaCallTerminated) : ContactCenterMediaFrame
    data class SbcDetails(val details: AgentMediaSbcDetails) : ContactCenterMediaFrame
    /** A screen-share or cobrowse invite. Android cannot serve either, so the SDK declines it (Web parity). */
    data class UnsupportedInvite(val kind: String, val conversationId: String?, val mediaSessionId: String?) : ContactCenterMediaFrame
}
enum class SurveySubmissionStatus { READY, PENDING, SUBMITTED, FAILED }
data class AttachmentRef(
    val id: String,
    val filename: String = "Attachment",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val deliveryFailed: Boolean = false
)

enum class AgentLinkKind { FORM, ATTACHMENT, LINK }
data class AgentLink(
    val url: String,
    val title: String,
    val kind: AgentLinkKind = AgentLinkKind.LINK
)

data class SurveyDraft(val score: Int? = null, val comments: String = "")
data class AgentTransferSurvey(
    val surveyType: String,
    val artemisSessionId: String,
    val smartAssistSessionId: String? = null,
    val prompt: String = "How would you rate your experience?",
    val max: Int = if (surveyType == "nps") 10 else if (surveyType == "csat") 5 else 1,
    val submitLabel: String = "Submit",
    val pendingMessage: String = "Submitting your feedback…",
    val successMessage: String = "Thanks for your feedback.",
    val errorMessage: String = "We could not submit your feedback. Please try again.",
    val commentPrompt: String? = null,
    val commentPlaceholder: String = "Additional comments (optional)",
    val commentVisibility: String? = null,
    val commentRatings: List<Int> = emptyList(),
    val status: SurveySubmissionStatus = SurveySubmissionStatus.READY,
    val suppliedLabels: Set<String> = emptySet(),
) {
    fun validScore(score: Int) = when (surveyType) {
        "nps" -> score in 0..10; "csat" -> score in 1..5; "likeDislike" -> score in 0..1; else -> false
    }

    fun showComment(score: Int?) = when (commentVisibility) {
        "always" -> true; "never" -> false
        "conditional" -> score != null && (commentRatings.isEmpty() || score in commentRatings)
        else -> score != null
    }

    val choices: IntRange
        get() = when (surveyType) {
            "nps" -> 0..max; "csat" -> 1..max; else -> 0..1
        }
}

/** Only HTTP(S) links without embedded credentials are opened outside the SDK. */
internal fun contactCenterUrl(value: String?): String? = value?.trim()?.takeIf { candidate ->
    runCatching {
        URI(candidate).let {
            it.scheme?.lowercase() in setOf(
                "http",
                "https"
            ) && !it.host.isNullOrBlank() && it.rawUserInfo == null
        }
    }.getOrDefault(false)
}

internal object ContactCenterContracts {
    fun survey(raw: JsonObject?, wire: Boolean): AgentTransferSurvey? {
        raw ?: return null
        if (wire && !raw.flag("triggerSurvey")) return null
        val type = raw.string("surveyType")?.takeIf { it in setOf("nps", "csat", "likeDislike") }
            ?: return null
        val id = raw.string("artemisSessionId")?.trim()?.takeIf(String::isNotBlank) ?: return null
        val cap = if (type == "nps") 10 else if (type == "csat") 5 else 1
        fun label(model: String, wireKey: String, default: String) =
            raw.string(if (wire) wireKey else model)?.trim()?.takeIf(String::isNotBlank) ?: default

        val defaults = AgentTransferSurvey(type, id)
        val labelKeys = mapOf(
            "prompt" to "prompt",
            "submitLabel" to "submit_label",
            "pendingMessage" to "pending_message",
            "successMessage" to "success_message",
            "errorMessage" to "error_message",
            "commentPlaceholder" to "comment_placeholder"
        )
        val supplied = if (!wire && raw.has("suppliedLabels")) raw.array("suppliedLabels")
            ?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
            ?.toSet().orEmpty()
        else labelKeys.filter { (model, wireKey) ->
            raw.string(if (wire) wireKey else model)?.isNotBlank() == true
        }.keys
        return defaults.copy(
            suppliedLabels = supplied,
            smartAssistSessionId = raw.string("smartAssistSessionId")?.trim()
                ?.takeIf(String::isNotBlank),
            prompt = label("prompt", "prompt", defaults.prompt),
            max = (raw.number("max")?.toInt() ?: cap).coerceIn(1, cap),
            submitLabel = label("submitLabel", "submit_label", defaults.submitLabel),
            pendingMessage = label("pendingMessage", "pending_message", defaults.pendingMessage),
            successMessage = label("successMessage", "success_message", defaults.successMessage),
            errorMessage = label("errorMessage", "error_message", defaults.errorMessage),
            commentPrompt = raw.string(if (wire) "comment_prompt" else "commentPrompt"),
            commentPlaceholder = label(
                "commentPlaceholder",
                "comment_placeholder",
                defaults.commentPlaceholder
            ),
            commentVisibility = raw.string(if (wire) "comment_visibility" else "commentVisibility")
                ?.takeIf { it in setOf("always", "never", "conditional") },
            commentRatings = raw.array(if (wire) "comment_ratings" else "commentRatings")
                ?.mapNotNull {
                    runCatching { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.asDouble == v.asInt.toDouble() }?.asInt }.getOrNull()
                }.orEmpty()
        )
    }

    fun link(metadata: JsonObject?): AgentLink? {
        metadata ?: return null
        val form = contactCenterUrl(metadata.string("agentTransferFormUrl", "smartAssistFormUrl"))
        val url = form ?: contactCenterUrl(metadata.string("agentTransferLinkUrl")) ?: return null
        val title = if (form != null) metadata.string(
            "agentTransferFormDisplayName",
            "smartAssistFormDisplayName",
            "agentTransferFormTitle",
            "smartAssistFormTitle"
        ) ?: "Open form"
        else metadata.string("agentTransferLinkTitle") ?: "Open link"
        val kind =
            if (form != null || metadata.string("agentTransferLinkType") == "smartassist_form") AgentLinkKind.FORM
            else if (metadata.string("agentTransferLinkType") == "agent_attachment") AgentLinkKind.ATTACHMENT else AgentLinkKind.LINK
        return AgentLink(url, title, kind)
    }

    fun attachments(p: JsonObject, metadata: JsonObject?): List<AttachmentRef> {
        val failedIds = metadata?.array("agentTransferAttachmentFailedIds")
            ?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString }
            .orEmpty().toSet()

        fun failed(id: String) =
            id in failedIds || metadata?.string("agentTransferAttachmentDeliveryStatus") == "not_sent"

        val refs = p.array("attachments") ?: metadata?.obj("custom")?.array("customerAttachments")
        val explicit = refs?.mapNotNull { v ->
            val a = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id =
                a.string("id", "attachmentId")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AttachmentRef(
                id,
                a.string("filename", "fileName") ?: "Attachment",
                a.string("mimeType") ?: "application/octet-stream",
                (a.number("sizeBytes") ?: 0).coerceAtLeast(0),
                failed(id)
            )
        }.orEmpty()
        if (explicit.isNotEmpty()) return explicit.distinctBy { it.id }
        fun textAt(key: String, i: Int) = metadata?.array(key)?.getOrNull(i)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return metadata?.array("attachmentIds")?.mapIndexedNotNull { i, v ->
            val id =
                v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(
                    String::isNotBlank
                ) ?: return@mapIndexedNotNull null
            AttachmentRef(
                id,
                textAt("attachmentFilenames", i) ?: "Attachment",
                textAt("attachmentMimeTypes", i) ?: "application/octet-stream",
                metadata.array("attachmentSizeBytes")?.getOrNull(i)
                    ?.let { runCatching { it.asLong.coerceAtLeast(0) }.getOrDefault(0) } ?: 0,
                failed(id))
        }?.distinctBy { it.id }.orEmpty()
    }

    private fun com.google.gson.JsonArray.getOrNull(i: Int) =
        if (i in 0 until size()) get(i) else null

    fun normalizeMetadata(metadata: JsonObject): JsonObject {
        val result = metadata.deepCopy()
        val custom = result.obj("custom")
        if (custom?.string("source") == "agent-transfer") {
            custom.string("eventType")?.let { result.addProperty("agentTransferEventType", it) }
            custom.string("transferSessionId")
                ?.let { result.addProperty("agentTransferSessionId", it) }
            result.addProperty("contentFormat", "plain_text")
            val data = custom.obj("providerEventData")
            for ((from, to) in mapOf(
                "linkUrl" to "agentTransferLinkUrl",
                "linkTitle" to "agentTransferLinkTitle",
                "linkType" to "agentTransferLinkType",
                "formUrl" to "agentTransferFormUrl",
                "formTitle" to "agentTransferFormTitle",
                "formDisplayName" to "agentTransferFormDisplayName"
            )) {
                data?.string(from)?.let { result.addProperty(to, it) }
            }
        }
        return result
    }

    fun restoredSurvey(metadata: JsonObject?): AgentTransferSurvey? {
        val custom = metadata?.obj("custom") ?: return null
        if (custom.string("source") != "agent-transfer" || custom.string("eventType") != "agent:disconnected") return null
        return survey(custom.obj("providerEventData")?.obj("survey"), wire = true)
    }

    fun status(message: ChatMessage): AgentTransferStatus? {
        // Persisted transfer state is authoritative when a provider reorders notices.
        when (message.metadata?.obj("custom")?.takeIf { it.string("source") == "agent-transfer" }
            ?.string("transferState")) {
            "ended" -> return AgentTransferStatus.DISCONNECTED
            "connected" -> return AgentTransferStatus.CONNECTED
            "queued" -> return AgentTransferStatus.QUEUED
        }
        return when (message.metadata?.string("agentTransferEventType")) {
            "transfer_active", "agent:queued", "agent:waiting_message" -> AgentTransferStatus.QUEUED
            "agent:connected", "agent:message", "agent:form", "attachment", "agent.attachment" -> AgentTransferStatus.CONNECTED
            "agent:disconnected" -> AgentTransferStatus.DISCONNECTED
            else -> null
        }
    }

    fun identity(message: ChatMessage): String? {
        val custom = message.metadata?.obj("custom") ?: return null
        if (custom.string("source") != "agent-transfer") return null
        val providerId = custom.string("providerMessageId") ?: return null
        return listOf(
            custom.string("transferSessionId"),
            custom.string("eventType"),
            providerId,
            message.text,
            message.agentLink?.url
        ).joinToString("|")
    }

    fun transfer(p: JsonObject): ChatFrame.Transfer? {
        val session = p.string("sessionId")?.takeIf(String::isNotBlank) ?: return null
        val event = p.obj("event") ?: return null
        val type = event.string("type") ?: return null
        val data = event.obj("data") ?: JsonObject()
        if (type !in setOf(
                "agent:message",
                "agent:form",
                "agent.attachment",
                "agent:queued",
                "agent:waiting_message",
                "agent:connected",
                "agent:disconnected",
                "agent:transferred",
                "agent:joined",
                "agent:exited",
                "agent:notice",
                "agent:typing",
                "agent:typing_stop",
                "agent:media"
            )
        ) return null
        val mediaFrame = if (type == "agent:media") mediaFrame(data) else null
        if (type == "agent:media" && mediaFrame == null) return null
        val metadata = JsonObject().apply {
            add("custom", JsonObject().apply {
                addProperty("source", "agent-transfer"); addProperty(
                "transferSessionId",
                session
            ); addProperty("eventType", type)
                data.string("providerMessageId", "messageId", "msgId", "id")
                    ?.let { addProperty("providerMessageId", it) }
            })
            addProperty("contentFormat", "plain_text"); addProperty(
            "agentTransferEventType",
            type
        ); addProperty("agentTransferSessionId", session)
            for ((from, to) in mapOf(
                "linkUrl" to "agentTransferLinkUrl",
                "linkTitle" to "agentTransferLinkTitle",
                "linkType" to "agentTransferLinkType",
                "formUrl" to "agentTransferFormUrl",
                "formTitle" to "agentTransferFormTitle",
                "formRenderType" to "agentTransferFormRenderType",
                "formDisplayName" to "agentTransferFormDisplayName"
            )) {
                data.string(from)?.let { addProperty(to, it) }
            }
        }
        val source = p.obj("messageSource")?.deepCopy() ?: JsonObject().apply {
            addProperty(
                "system",
                "integration"
            ); addProperty("provider", "smartassist")
        }
        val timestamp = event.string("timestamp")?.let(ChatFrameParser::parseTimestamp)
            ?: System.currentTimeMillis()
        val providerId = data.string(
            "providerMessageId",
            "idempotencyKey",
            "messageId",
            "msgId",
            "eventId",
            "requestId",
            "attachmentId",
            "_id",
            "id"
        )
        // Stable across provider-only diagnostics and key ordering on redelivery.
        val fingerprint = listOf(
            session,
            type,
            event.string("timestamp").orEmpty(),
            data.string("message", "text", "body", "closeMessage").orEmpty(),
            data.string("linkUrl").orEmpty(),
            data.string("formUrl").orEmpty()
        ).joinToString("|")
        val stableId =
            providerId ?: MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
        val id = "agent-transfer:$session:$type:$stableId"
        fun content(text: String, role: MessageRole = MessageRole.ASSISTANT, suffix: String = "") =
            ChatMessage(
                id + suffix, id + suffix, role, text,
                timestamp, metadata.deepCopy(), messageSource = source, agentLink = link(metadata)
            )

        fun text(vararg keys: String) =
            keys.firstNotNullOfOrNull { data.string(it)?.takeIf(String::isNotBlank) }.orEmpty()

        val messages = when (type) {
            "agent:message" -> listOf(
                content(
                    text(
                        "message",
                        "text",
                        "body"
                    )
                )
            ).filter { it.text.isNotBlank() || it.agentLink != null }

            "agent:form" -> listOf(
                content(
                    text(
                        "formText",
                        "formTitle",
                        "linkTitle",
                        "message"
                    )
                )
            ).filter { it.text.isNotBlank() || it.agentLink != null }

            "agent.attachment" -> {
                val attachmentId = data.string("attachmentId")?.takeIf(String::isNotBlank)
                val runtimeSession = data.string("runtimeSessionId")?.takeIf(String::isNotBlank)
                if (attachmentId == null || runtimeSession == null) emptyList() else listOf(
                    content("").copy(
                        attachments = listOf(
                            AttachmentRef(
                                attachmentId,
                                data.string("fileName") ?: "Attachment",
                                data.string("mimeType") ?: "application/octet-stream",
                                (data.number("sizeBytes") ?: 0).coerceAtLeast(0)
                            )
                        )
                    )
                )
            }

            // Web SDK wording (ChatClient / agent-transfer-notice): a queued frame always shows the
            // SDK text; only a waiting message carries provider text.
            "agent:queued" -> listOf(content(AGENT_TRANSFER_QUEUED_MESSAGE, MessageRole.SYSTEM))
            "agent:waiting_message" -> listOf(
                content(text("message", "text", "body").ifBlank { AGENT_TRANSFER_QUEUED_MESSAGE }, MessageRole.SYSTEM)
            )

            "agent:connected" -> listOf(
                content(text("noticeText").ifBlank { AGENT_TRANSFER_CONNECTED_MESSAGE }, MessageRole.SYSTEM)
            )
            "agent:joined", "agent:exited", "agent:notice" -> {
                val notice = text("noticeText")
                if (notice.isBlank()) emptyList() else listOf(content(notice, MessageRole.SYSTEM))
            }
            "agent:disconnected" -> buildList {
                text("closeMessage", "message", "text", "body").takeIf(String::isNotBlank)
                    ?.let { add(content(it, suffix = ":close")) }
                disconnectStatus(data)?.let { add(content(it, MessageRole.SYSTEM, ":status")) }
                survey(data.obj("survey"), wire = true)?.let { survey ->
                    add(
                        content(survey.prompt).copy(
                            messageId = "agent-transfer-survey-${survey.artemisSessionId}",
                            serverMessageId = null,
                            transferSurvey = survey
                        )
                    )
                }
            }

            else -> emptyList()
        }
        return ChatFrame.Transfer(session, type, messages, timestamp, mediaFrame, data.deepCopy())
    }

    /** Web SDK `resolveAgentTransferDisconnectStatus`: the status line after the agent leg ends. */
    internal fun disconnectStatus(data: JsonObject): String? {
        val reason = data.string("disconnectReason")
        if (data.flag("suppressDisconnectNotice") || reason == "transfer_to_email") return null
        return if (reason in AGENT_TRANSFER_HAND_BACK_REASONS) AGENT_TRANSFER_HAND_BACK_MESSAGE else AGENT_TRANSFER_DISCONNECT_MESSAGE
    }

    // Same text as the Web SDK so both clients read identically.
    internal const val AGENT_TRANSFER_QUEUED_MESSAGE = "Waiting for a human agent."
    internal const val AGENT_TRANSFER_CONNECTED_MESSAGE = "Connected to a human agent."
    internal const val AGENT_TRANSFER_DISCONNECT_MESSAGE = "Human agent has disconnected. You are now back with the AI assistant."
    internal const val AGENT_TRANSFER_HAND_BACK_MESSAGE = "No agent is available right now. You are back with the AI assistant."
    /** Disconnect reasons where no agent ever joined: the customer is told they are back with the bot. */
    private val AGENT_TRANSFER_HAND_BACK_REASONS = setOf("wait_timeout", "long_wait", "no_agents")

    fun directMedia(p: JsonObject): ChatFrame.ContactCenterMedia? {
        val type = p.string("type")
        val frame = when (type) {
            "agent_media" -> p.obj("event")?.let { event -> mediaFrame(event.obj("data") ?: event) }
            "contact_center.terminate_agent_webrtc", "terminate_agent_webrtc" ->
                ContactCenterMediaFrame.Terminated(
                    AgentMediaCallTerminated(callId(p), p.string("reason") ?: "terminated")
                )
            "contact_center.call_agent_webrtc", "call_agent_webrtc" -> {
                val source = p.obj("call") ?: p
                val call = call(source) ?: return null
                ContactCenterMediaFrame.Incoming(
                    AgentMediaInvite(
                        callType = call.callType,
                        conversationId = call.conversationId,
                        providerConversationId = call.providerConversationId,
                        providerAccountId = source.string("providerAccountId"),
                        mediaSessionId = call.mediaSessionId,
                        idempotencyKey = source.string("idempotencyKey"),
                        callId = call.callId,
                        displayName = source.string("displayName", "agentName", "agentDisplayName")
                            ?: source.obj("agent")?.string("firstName"),
                    )
                )
            }
            else -> null
        } ?: return null
        return ChatFrame.ContactCenterMedia(frame)
    }

    fun sbcDetails(p: JsonObject): ChatFrame.ContactCenterMedia? {
        val response = p.obj("responseData")
        val addresses = strings(response?.array("addresses"))
        val domain = response?.string("domain")
        // Match the Web normalizer: a ready response is usable only when the
        // response contains the SBC address and domain needed to establish a
        // media session. Malformed/partial responses remain unsupported.
        val details = AgentMediaSbcDetails(
            requestId = p.string("requestId"),
            ready = p.flag("ready") && addresses.isNotEmpty() && !domain.isNullOrBlank(),
            addresses = addresses,
            domain = domain,
            iceServers = iceServers(response?.array("iceServers")),
        )
        return ChatFrame.ContactCenterMedia(ContactCenterMediaFrame.SbcDetails(details))
    }

    private fun mediaFrame(data: JsonObject): ContactCenterMediaFrame? {
        val providerType = data.string("providerEventType")
        val action = data.string("action")
        val mediaKind = data.string("mediaKind")
        // Match the Web classifier: only an invite is answered; end/terminal frames are ignored.
        val isScreenShare = providerType == "webrtc_screenshare" || mediaKind == "screen_share"
        val isCobrowse = mediaKind == "cobrowse" || providerType?.contains("cobrowse") == true
        if (isScreenShare || isCobrowse) {
            val invited = action == "invite" || (action == null && providerType == "webrtc_screenshare")
            if (!invited) return null
            return ContactCenterMediaFrame.UnsupportedInvite(
                kind = if (isCobrowse) "cobrowse" else "screen_share",
                conversationId = data.string("conversationId"),
                mediaSessionId = data.string("mediaSessionId") ?: callId(data),
            )
        }
        if (providerType?.startsWith("webrtc_screenshare") == true) return null

        if (providerType == "call_agent_webrtc" && action == "invite") {
            val call = call(data) ?: return null
            return ContactCenterMediaFrame.Incoming(
                AgentMediaInvite(
                    callType = call.callType,
                    conversationId = call.conversationId,
                    providerConversationId = call.providerConversationId,
                    providerAccountId = data.string("providerAccountId"),
                    mediaSessionId = call.mediaSessionId,
                    idempotencyKey = data.string("idempotencyKey"),
                    callId = call.callId,
                    displayName = data.string("displayName", "agentName", "agentDisplayName")
                        ?: data.obj("agent")?.string("firstName"),
                )
            )
        }
        if (providerType == "call_agent_webrtc_accepted" || action == "accepted") {
            val call = call(data) ?: return null
            val endpoint = endpoint(data.obj("endpoint")) ?: return null
            val customer = customer(data.obj("customer")) ?: return null
            return ContactCenterMediaFrame.EndpointReleased(
                AgentMediaEndpoint(
                    callId = call.callId ?: return null,
                    mediaSessionId = call.mediaSessionId ?: return null,
                    callType = call.callType,
                    conversationId = call.conversationId,
                    providerConversationId = call.providerConversationId,
                    sipUri = endpoint.sipUri,
                    sipUser = endpoint.sipUser,
                    sipPassword = endpoint.sipPassword,
                    addresses = endpoint.addresses,
                    domain = endpoint.domain,
                    iceServers = endpoint.iceServers,
                    customerUserId = customer.userId,
                    customerSipUser = customer.sipUser,
                    customerSipPassword = customer.sipPassword,
                    customerSipAuthUser = customer.sipAuthUser,
                    customerDisplayName = customer.displayName,
                )
            )
        }
        if (providerType in setOf(
                "call_agent_webrtc_terminated",
                "call_agent_webrtc_rejected",
                "call_agent_webrtc_failed"
            ) || action in setOf("terminated", "cancelled", "failed")) {
            return ContactCenterMediaFrame.Terminated(
                AgentMediaCallTerminated(callId(data), data.string("reason") ?: action ?: providerType)
            )
        }
        return null
    }

    private data class ParsedMediaCall(
        val callId: String?,
        val mediaSessionId: String?,
        val callType: String,
        val conversationId: String?,
        val providerConversationId: String?,
    )

    private data class ParsedMediaEndpoint(
        val sipUri: String?,
        val sipUser: String?,
        val sipPassword: String?,
        val addresses: List<String>,
        val domain: String?,
        val iceServers: List<AgentMediaIceServer>,
    )

    private data class ParsedMediaCustomer(
        val userId: String?,
        val sipUser: String?,
        val sipPassword: String?,
        val sipAuthUser: String?,
        val displayName: String?,
    )

    private fun call(source: JsonObject): ParsedMediaCall? {
        val callId = callId(source)
        val mediaSessionId = source.string("mediaSessionId") ?: callId
        if (callId.isNullOrBlank() && mediaSessionId.isNullOrBlank()) return null
        val callType = source.string("callType")?.lowercase()?.takeIf { it in setOf("audio", "video") }
            ?: if (source.flag("videoCall")) "video" else "audio"
        return ParsedMediaCall(
            callId = callId ?: mediaSessionId,
            mediaSessionId = mediaSessionId ?: callId,
            callType = callType,
            conversationId = source.string("conversationId"),
            providerConversationId = source.string("providerConversationId"),
        )
    }

    private fun callId(source: JsonObject): String? = source.string(
        "callId", "mediaSessionId", "callSid", "conversationId"
    )

    private fun endpoint(source: JsonObject?): ParsedMediaEndpoint? {
        source ?: return null
        val result = ParsedMediaEndpoint(
            sipUri = source.string("sipURI", "sipUri"),
            sipUser = source.string("sipUser"),
            sipPassword = source.string("sipPassword"),
            addresses = strings(source.array("addresses")),
            domain = source.string("domain"),
            iceServers = iceServers(source.array("iceServers")),
        )
        return result.takeIf {
            !it.sipUri.isNullOrBlank() || !it.sipUser.isNullOrBlank() ||
                !it.sipPassword.isNullOrBlank() || it.addresses.isNotEmpty() || !it.domain.isNullOrBlank()
        }
    }

    private fun customer(source: JsonObject?): ParsedMediaCustomer? {
        source ?: return null
        val result = ParsedMediaCustomer(
            userId = source.string("userId"),
            sipUser = source.string("sipUser"),
            sipPassword = source.string("sipPassword"),
            sipAuthUser = source.string("sipAuthUser"),
            displayName = source.string("displayName"),
        )
        return result.takeIf { !it.userId.isNullOrBlank() || !it.sipUser.isNullOrBlank() || !it.sipPassword.isNullOrBlank() }
    }

    private fun strings(values: JsonArray?): List<String> = values?.mapNotNull {
        it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()

    private fun iceServers(values: JsonArray?): List<AgentMediaIceServer> = values?.mapNotNull { value ->
        val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val urls = when {
            item.string("urls", "url") != null -> listOf(item.string("urls", "url")!!)
            else -> strings(item.array("urls"))
        }
        urls.takeIf { it.isNotEmpty() }?.let {
            AgentMediaIceServer(it, item.string("username"), item.string("credential"))
        }
    }.orEmpty()
}
