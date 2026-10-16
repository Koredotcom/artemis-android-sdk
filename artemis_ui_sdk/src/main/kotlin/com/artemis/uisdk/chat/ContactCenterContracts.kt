package com.artemis.uisdk.chat

import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest

enum class AgentTransferStatus { NONE, QUEUED, CONNECTED, DISCONNECTED }
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
                "agent:typing",
                "agent:typing_stop"
            )
        ) return null
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

            "agent:queued", "agent:waiting_message" -> listOf(
                content(
                    text(
                        "message",
                        "text",
                        "body"
                    ).ifBlank { "Waiting for a human agent…" }, MessageRole.SYSTEM
                )
            )

            "agent:connected" -> listOf(content("Connected to a human agent.", MessageRole.SYSTEM))
            "agent:disconnected" -> buildList {
                text("closeMessage", "message", "text", "body").takeIf(String::isNotBlank)
                    ?.let { add(content(it, suffix = ":close")) }
                add(
                    content(
                        "The human agent has left the conversation.",
                        MessageRole.SYSTEM,
                        ":status"
                    )
                )
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
        return ChatFrame.Transfer(session, type, messages, timestamp)
    }
}
