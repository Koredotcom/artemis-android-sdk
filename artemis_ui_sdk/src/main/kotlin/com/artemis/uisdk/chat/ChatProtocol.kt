package com.artemis.uisdk.chat

import com.google.gson.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** The sole wire boundary. Malformed/unknown events never mutate a conversation. */
internal sealed interface ChatFrame {
    data class Authorization(val event: AuthorizationEvent) : ChatFrame
    data class Transfer(val sessionId: String, val eventType: String, val messages: List<ChatMessage>, val timestamp: Long = System.currentTimeMillis()) : ChatFrame
    data class Session(val payload: JsonObject) : ChatFrame
    data class Start(val id: String) : ChatFrame
    data class Chunk(val id: String, val text: String) : ChatFrame
    data class End(val message: ChatMessage) : ChatFrame
    data class UserRender(val id: String, val text: String, val withheld: Boolean) : ChatFrame
    data class Typing(val active: Boolean) : ChatFrame
    data class Cancel(val id: String?, val requestId: String?, val reason: String) : ChatFrame
    data class Activity(val type: String, val payload: JsonObject) : ChatFrame
    data class Error(val text: String, val code: String? = null) : ChatFrame
    data class InitialMessage(val pending: Boolean) : ChatFrame
}

internal fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
internal fun JsonObject.obj(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { key -> get(key)?.takeIf { it.isJsonObject }?.asJsonObject }
internal fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
internal fun JsonObject.flag(key: String, default: Boolean = false): Boolean = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default
internal fun JsonObject.number(key: String): Long? = runCatching { get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong }.getOrNull()
internal fun userDisplay(text: String) = text.replace(Regex("\\[REDACTED(?:_[A-Z0-9]+)*]"), "••••")

internal object ChatFrameParser {
    fun parse(raw: String): ChatFrame? = runCatching {
        val p = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = p.string("messageId")?.takeIf { it.isNotBlank() }
        when (p.string("type")) {
            in AuthorizationContracts.eventTypes -> AuthorizationContracts.parse(p)?.let(ChatFrame::Authorization)
            "agent_transfer_event" -> ContactCenterContracts.transfer(p)
            "session_start" -> p.string("sessionId")?.takeIf(String::isNotBlank)?.let { ChatFrame.Session(p) }
            "response_start" -> id?.let { ChatFrame.Start(it) }
            "response_chunk" -> id?.let { ChatFrame.Chunk(it, p.string("chunk", "content", "text") ?: return null) }
            "response_end" -> ChatFrame.End(message(p, MessageRole.ASSISTANT, id ?: UUID.randomUUID().toString()))
            "user_message_rendered" -> id?.let { ChatFrame.UserRender(it, p.string("content", "text") ?: "", p.string("status") == "withheld") }
            "typing_update" -> ChatFrame.Typing(p.flag("isTyping"))
            "execution_cancelled" -> ChatFrame.Cancel(id, p.string("requestMessageId"), p.string("reason") ?: "client_cancel")
            "status_update", "status_clear", "thought", "handoff", "tool_warnings", "session_health" -> ChatFrame.Activity(p.string("type")!!, p)
            "trace_event" -> {
                val event = p.obj("event") ?: p.string("eventType")?.let { p.deepCopy().apply { addProperty("type", it) } } ?: return null
                val type = event.string("type") ?: return null
                ChatFrame.Activity(if (type == "tool_thought") "thought" else type, event.obj("data", "payload") ?: event)
            }
            "initial_assistant_message_status" -> ChatFrame.InitialMessage(p.flag("pending"))
            "error" -> ChatFrame.Error(p.string("message", "content", "error") ?: "The assistant could not complete the response", p.string("code"))
            else -> null
        }
    }.getOrNull()

    fun history(raw: String): List<ChatMessage> = runCatching {
        JsonParser.parseString(raw).asJsonArray.mapNotNull { entry ->
            runCatching record@{
                val p = entry.asJsonObject
                val id = p.string("id", "messageId")?.takeIf(String::isNotBlank) ?: return@record null
                val role = when (p.string("role")) { "user" -> MessageRole.USER; "assistant" -> MessageRole.ASSISTANT; "system" -> MessageRole.SYSTEM; else -> return@record null }
                message(p, role, id)
            }.getOrNull()
        }.flatMap { message ->
            val survey = ContactCenterContracts.restoredSurvey(message.metadata)
            if (survey == null) listOf(message) else listOf(message, message.copy(
                messageId = "agent-transfer-survey-${survey.artemisSessionId}", serverMessageId = null,
                text = survey.prompt, transferSurvey = survey, agentLink = null, attachments = emptyList()))
        }
    }.getOrDefault(emptyList())

    internal fun parseTimestamp(value: String): Long? {
        val normalized = value.replace(Regex("\\.([0-9]+)(?=Z|[+-])")) { match -> "." + match.groupValues[1].take(3).padEnd(3, '0') }
        return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }.parse(normalized)?.time }.getOrNull()
        }
    }

    private fun message(p: JsonObject, role: MessageRole, id: String): ChatMessage {
        val envelope = p.obj("contentEnvelope", "content_envelope")?.deepCopy()
        val text = p.string("fullText", "text", "content") ?: envelope?.string("text", "content") ?: ""
        var metadata = envelope?.obj("metadata")?.deepCopy() ?: JsonObject()
        p.obj("metadata")?.entrySet()?.forEach { metadata.add(it.key, it.value.deepCopy()) }
        (envelope?.obj("localization") ?: p.obj("localization"))?.entrySet()?.forEach { metadata.add(it.key, it.value.deepCopy()) }
        metadata = ContactCenterContracts.normalizeMetadata(metadata)
        val rich = normalizeRich(p.obj("richContent", "rich_content") ?: envelope?.obj("richContent", "rich_content"))
        val actions = normalizeActions(p.get("actions") ?: envelope?.get("actions"))
        val renderables = (p.array("renderables") ?: envelope?.array("renderables"))?.let { raw ->
            JsonArray().apply { raw.forEach { value ->
                if (value.isJsonObject && !value.asJsonObject.string("name").isNullOrBlank() && value.asJsonObject.has("payload")) add(value.deepCopy())
            } }
        }?.takeIf { it.size() > 0 }
        val citations = (p.array("citations") ?: envelope?.array("citations"))?.mapNotNull { entry ->
            val c = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val url = c.string("url") ?: return@mapNotNull null
            Citation(c.number("index")?.toInt() ?: 0, c.string("title") ?: "Source", url, c.string("sourceType") ?: "")
        }.orEmpty()
        val timestamp = p.number("timestamp") ?: p.string("timestamp")?.let { parseTimestamp(it) } ?: System.currentTimeMillis()
        val source = p.obj("messageSource")?.deepCopy() ?: JsonObject().apply {
            if (metadata.obj("custom")?.string("source") == "agent-transfer") { addProperty("system", "integration"); addProperty("provider", metadata.obj("custom")?.string("provider") ?: "Support agent") }
            else addProperty("system", "artemis")
        }
        return ChatMessage(id, id, role, if (role == MessageRole.USER) userDisplay(text) else text,
            timestamp, metadata.takeIf { it.size() > 0 }, rich, actions, MessageStatus.COMPLETE,
            citations = citations, messageSource = source, renderables = renderables, contentEnvelope = envelope,
            sequence = p.number("sequence"), attachments = ContactCenterContracts.attachments(p, metadata),
            agentLink = ContactCenterContracts.link(metadata), transferSurvey = ContactCenterContracts.survey(metadata.obj("agentTransferSurvey"), wire = false))
    }

    private fun normalizeRich(raw: JsonObject?): JsonObject? {
        val rich = raw?.deepCopy() ?: return null
        if (rich.string("type") == "card" && rich.string("markdown") == null) {
            val fields = rich.array("fields")?.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject?.let { "- **${it.string("label") ?: "Field"}:** ${it.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()}" } }.orEmpty()
            rich.addProperty("markdown", (listOfNotNull(rich.string("title")?.let { "### $it" }, rich.string("body")) + fields).joinToString("\n\n"))
        }
        if (rich.string("type") == "quick_replies" && rich.array("quick_replies") == null) {
            val replies = JsonArray()
            rich.array("replies")?.forEachIndexed { i, value ->
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> replies.add(JsonObject().apply {
                        addProperty("id", value.asString.lowercase().replace(Regex("[^a-z0-9_-]"), "_")); addProperty("label", value.asString)
                    })
                    value.isJsonObject && value.asJsonObject.string("label") != null -> replies.add(value.deepCopy().asJsonObject.apply { if (string("id") == null) addProperty("id", "reply-$i") })
                }
            }
            rich.add("quick_replies", replies)
        }
        return rich.takeIf { it.size() > 0 }
    }

    private fun normalizeActions(raw: JsonElement?): JsonObject? {
        val actions = when {
            raw == null || raw.isJsonNull -> return null
            raw.isJsonArray -> JsonObject().apply { add("elements", raw.deepCopy()) }
            raw.isJsonObject -> raw.deepCopy().asJsonObject
            else -> return null
        }
        val elements = actions.array("elements") ?: return null
        val valid = JsonArray()
        elements.forEach { value ->
            val element = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (element.string("id").isNullOrBlank()) return@forEach
            val label = element.string("label", "text", "title") ?: return@forEach
            element.addProperty("label", label)
            val payload = element.get("payload") ?: element.get("value")
            if (payload != null) element.addProperty("value", if (payload.isJsonPrimitive && payload.asJsonPrimitive.isString) payload.asString else payload.toString())
            if (element.string("type") !in setOf("button", "select", "input")) element.addProperty("type", "button")
            valid.add(element)
        }
        actions.add("elements", valid)
        return actions
    }

}

/** Pure transcript reducer, shared by live events and persisted-history reconciliation. */
internal class ChatTranscript(private val contactCenterEnabled: Boolean = true, private val attachmentsEnabled: Boolean = true, private val authenticationEnabled: Boolean = false) {
    private val authorization = AuthorizationTranscript()
    private var lastTransferTransitionAt = 0L
    private var transferLiveRevision = 0L
    val transferRevision: Long get() = transferLiveRevision
    private fun presentation(message: ChatMessage) = message.copy(
        attachments = if (attachmentsEnabled) message.attachments else emptyList(),
        agentLink = message.agentLink?.takeIf { contactCenterEnabled && (it.kind != AgentLinkKind.ATTACHMENT || attachmentsEnabled) },
        transferSurvey = message.transferSurvey?.takeIf { contactCenterEnabled },
        authorization = message.authorization?.takeIf { authenticationEnabled },
    )
    var state = ChatUiState(); private set
    var redaction = false; private set
    private var scope = JsonObject()
    private var activeResponse: String? = null
    private var activeRequest: String? = null
    private val cancelled = linkedSetOf<String>()
    private val parked = linkedMapOf<String, ChatFrame.UserRender>()
    fun configureScope(value: JsonObject) { scope = value.deepCopy() }
    fun update(value: ChatUiState) { state = value }
    fun acceptedUser(id: String, text: String, metadata: JsonObject? = null) {
        upsert(ChatMessage(id, id, MessageRole.USER, if (redaction) "••••" else text,
            metadata = if (redaction) null else metadata?.deepCopy(), messageStatus = MessageStatus.ACCEPTED, pendingRender = redaction))
        activeRequest = id
        parked.remove(id)?.let(::apply)
        state = state.copy(isTyping = state.agentTransferStatus !in setOf(AgentTransferStatus.QUEUED, AgentTransferStatus.CONNECTED), activityText = null, errorMessage = null)
    }
    fun apply(frame: ChatFrame) {
        when (frame) {
            is ChatFrame.Authorization -> if (authenticationEnabled) { state = authorization.apply(state, frame.event) }
            is ChatFrame.Session -> {
                val session = frame.payload.string("sessionId")
                if (state.sessionId != null && state.sessionId != session) {
                    authorization.clear()
                    state = state.copy(messages = emptyList(), agentTransferStatus = AgentTransferStatus.NONE, agentTransferSessionId = null, pendingAttachment = null, attachmentDrafts = emptyList(), attachmentFilename = null, attachmentUploading = false, attachmentUploadFailed = false, attachmentUploadProgress = null, attachmentsUnavailable = false); lastTransferTransitionAt = 0; transferLiveRevision = 0; cancelled.clear(); parked.clear()
                }
                redaction = frame.payload.obj("capabilities")?.flag("liveUserInputRedaction") == true
                val initial = frame.payload.obj("initialAssistantMessage")
                state = state.copy(sessionId = session, initialAssistantMessagePending = initial?.flag("expected") == true,
                    isTyping = false, activityText = null)
                activeResponse = null; activeRequest = null
            }
            is ChatFrame.Transfer -> {
                // Runtime routes transfer IDs over the authenticated conversation socket. They are
                // distinct from session_start's Runtime session ID; socket generations reject stale sockets.
                if (!contactCenterEnabled || state.sessionId == null) return
                val transitionEvent = frame.eventType in setOf("agent:queued", "agent:waiting_message", "agent:connected", "agent:disconnected")
                if (transitionEvent && frame.timestamp < lastTransferTransitionAt) return
                frame.messages.map(::presentation).forEach { incoming ->
                    if (incoming.text.isBlank() && incoming.attachments.isEmpty() && incoming.agentLink == null && incoming.transferSurvey == null) return@forEach
                    val identity = ContactCenterContracts.identity(incoming)
                    val old = state.messages.find { it.messageId == incoming.messageId || identity != null && ContactCenterContracts.identity(it) == identity }
                    upsert(if (old != null) incoming.copy(messageId = old.messageId, serverMessageId = old.serverMessageId,
                        timestamp = old.timestamp, transferSurvey = old.transferSurvey ?: incoming.transferSurvey) else incoming)
                }
                val transition = when (frame.eventType) {
                    "agent:queued" -> AgentTransferStatus.QUEUED
                    "agent:waiting_message" -> if (state.agentTransferStatus == AgentTransferStatus.CONNECTED && state.agentTransferSessionId == frame.sessionId) null else AgentTransferStatus.QUEUED
                    "agent:connected" -> AgentTransferStatus.CONNECTED
                    "agent:message", "agent:form", "agent.attachment" -> if (state.agentTransferStatus != AgentTransferStatus.DISCONNECTED) AgentTransferStatus.CONNECTED else null
                    "agent:disconnected" -> AgentTransferStatus.DISCONNECTED
                    else -> null
                }
                if (transition != null && frame.timestamp >= lastTransferTransitionAt) {
                    lastTransferTransitionAt = frame.timestamp; transferLiveRevision++
                    state = state.copy(agentTransferStatus = transition, agentTransferSessionId = frame.sessionId, isTyping = false,
                        activityText = null, initialAssistantMessagePending = false)
                    activeResponse?.let { cancelled.add(it); while (cancelled.size > 100) cancelled.remove(cancelled.first()) }
                    activeResponse = null; activeRequest = null
                    state = state.copy(messages = state.messages.filterNot { it.messageStatus == MessageStatus.STREAMING && it.text.isBlank() }
                        .map { if (it.messageStatus == MessageStatus.STREAMING) it.copy(messageStatus = MessageStatus.CANCELLED) else it })
                } else if (frame.eventType in setOf("agent:typing", "agent:typing_stop", "agent:message") &&
                    (state.agentTransferSessionId == null || state.agentTransferSessionId == frame.sessionId) && state.agentTransferStatus != AgentTransferStatus.DISCONNECTED) {
                    state = state.copy(isTyping = frame.eventType == "agent:typing", activityText = null)
                }
            }
            is ChatFrame.Start -> {
                authorization.nextTurn()
                if (frame.id in cancelled) return
                activeResponse = frame.id
                if (state.messages.none { it.messageId == frame.id }) upsert(ChatMessage(frame.id, frame.id, MessageRole.ASSISTANT, "", messageStatus = MessageStatus.STREAMING))
                state = state.copy(isTyping = true)
            }
            is ChatFrame.Chunk -> {
                if (frame.id in cancelled) return
                activeResponse = frame.id
                val old = state.messages.find { it.messageId == frame.id }
                upsert((old ?: ChatMessage(frame.id, frame.id, MessageRole.ASSISTANT, "")).copy(text = old?.text.orEmpty() + frame.text, messageStatus = MessageStatus.STREAMING))
                state = state.copy(isTyping = true)
            }
            is ChatFrame.End -> {
                if (frame.message.messageId in cancelled) return
                val old = state.messages.find { it.messageId == frame.message.messageId }
                val message = presentation(frame.message).copy(timestamp = old?.timestamp ?: frame.message.timestamp,
                    text = frame.message.text.ifEmpty { old?.text.orEmpty() })
                if (message.text.isNotBlank() || message.richContent != null || message.actions != null || message.renderables != null || message.attachments.isNotEmpty() || message.agentLink != null || message.transferSurvey != null) upsert(message)
                else state = state.copy(messages = state.messages.filterNot { it.messageId == message.messageId })
                if (contactCenterEnabled && message.metadata?.string("agentTransferEventType") == "transfer_active" && state.agentTransferStatus != AgentTransferStatus.CONNECTED) {
                    // response_end may use a client fallback timestamp; only provider event timestamps order transfer transitions.
                    transferLiveRevision++
                    state = state.copy(agentTransferStatus = AgentTransferStatus.QUEUED)
                }
                if (activeResponse == null || activeResponse == message.messageId) {
                    activeResponse = null; activeRequest = null
                    state = state.copy(isTyping = false, activityText = null, errorMessage = null, initialAssistantMessagePending = false)
                }
            }
            is ChatFrame.UserRender -> {
                val old = state.messages.find { it.messageId == frame.id && it.role == MessageRole.USER }
                if (old == null) { parked[frame.id] = frame; while (parked.size > 100) parked.remove(parked.keys.first()); return }
                if (old.pendingRender) upsert(old.copy(text = if (frame.withheld) "••••" else userDisplay(frame.text), pendingRender = false))
            }
            is ChatFrame.Typing -> if (state.activityText == null) state = state.copy(isTyping = frame.active)
            is ChatFrame.Cancel -> {
                frame.id?.let { cancelled.add(it); while (cancelled.size > 100) cancelled.remove(cancelled.first()) }
                val owned = if (frame.requestId != null) frame.requestId == activeRequest || activeRequest == null && frame.id == activeResponse
                    else if (frame.id != null) frame.id == activeResponse else frame.reason != "preempted"
                state = state.copy(messages = state.messages.map { if (it.messageId == frame.id && it.messageStatus == MessageStatus.STREAMING) it.copy(messageStatus = MessageStatus.CANCELLED) else it })
                if (owned) { activeResponse = null; activeRequest = null; state = state.copy(isTyping = false, activityText = null) }
            }
            is ChatFrame.Activity -> activity(frame)
            is ChatFrame.Error -> { activeResponse = null; activeRequest = null; state = state.copy(isTyping = false, activityText = null, errorMessage = frame.text) }
            is ChatFrame.InitialMessage -> state = state.copy(initialAssistantMessagePending = frame.pending)
        }
    }
    private fun activity(frame: ChatFrame.Activity) {
        if (frame.type == "status_clear") { state = state.copy(activityText = null); return }
        val policy = scope.obj("activityVisibilityPolicy") ?: JsonObject()
        if (!scope.flag("showActivityUpdates") || !policy.flag("enabled", true)) return
        val category = when (frame.type) { "thought" -> "thoughtSummaries"; "status_update" -> "fillers"; "handoff", "tool_warnings", "session_health" -> "systemNotices"; else -> return }
        if (!policy.flag(category, category != "thoughtSummaries")) return
        val limit = (policy.number("maxTextChars") ?: 240).coerceIn(1, 2000).toInt()
        val p = frame.payload
        val text = when (frame.type) {
            "thought" -> p.string("thought", "content", "reasoning")
            "status_update" -> p.string("text", "content")
            "handoff" -> if (policy.flag("exposeAgentNames")) "Transferring to ${p.string("toAgent", "to") ?: "another assistant"}" else "Transferring to another assistant"
            "tool_warnings" -> "Some tools could not complete their work"
            "session_health" -> "The conversation needs attention"
            else -> null
        }?.take(limit) ?: return
        if (frame.type == "status_update") state = state.copy(activityText = text, isTyping = true)
        else upsert(ChatMessage(p.string("messageId", "id") ?: UUID.randomUUID().toString(), role = MessageRole.SYSTEM, text = text))
    }
    fun mergeHistory(messages: List<ChatMessage>, transferRevisionAtRequest: Long = transferLiveRevision) {
        val history = messages.map(::presentation).distinctBy { it.messageId }.map { message ->
            if (redaction && message.role == MessageRole.USER) message.copy(metadata = message.metadata?.deepCopy()?.apply { remove("display_label") }) else message
        }
        val existingFeedback = state.messages.associateBy { it.serverMessageId ?: it.messageId }
        val existingSurveys = state.messages.mapNotNull { m -> m.transferSurvey?.let { m.messageId to it } }.toMap()
        val durableTransferIds = history.mapNotNull { ContactCenterContracts.identity(it) }.toSet()
        val historyIds = history.map { it.messageId }.toSet()
        // Only unmatched IDs enter the occurrence pool: equal repeated replies remain separate turns.
        val pool = history.filter { h -> state.messages.none { it.messageId == h.messageId } }.groupingBy(::fingerprint).eachCount().toMutableMap()
        val locals = state.messages.filter { local ->
            if (local.messageId in historyIds || ContactCenterContracts.identity(local)?.let { it in durableTransferIds } == true) false
            else if (local.messageStatus == MessageStatus.STREAMING || local.pendingRender) true
            else { val key = fingerprint(local); val count = pool[key] ?: 0; if (count > 0) { pool[key] = count - 1; false } else true }
        }
        val selections = (history + locals).mapNotNull { it.metadata?.obj("custom")?.obj("actionSubmit") }
            .filter { !it.string("renderId").isNullOrBlank() && !it.string("actionId").isNullOrBlank() }
            .associateBy { it.string("renderId") }
        val merged = if (history.isNotEmpty() && history.all { it.sequence != null }) {
            history.sortedBy { it.sequence } + locals.sortedBy { it.timestamp }
        } else (history + locals).sortedBy { it.timestamp }
        state = state.copy(messages = merged.takeLast(500).map { message ->
            val selection = message.actions?.string("renderId")?.let { selections[it] }
            val retainedSurvey = existingSurveys[message.messageId]
            val preserved = retainFeedbackResponses(existingFeedback[message.serverMessageId ?: message.messageId],
                if (retainedSurvey != null) message.copy(transferSurvey = retainedSurvey) else message)
            if (selection == null) preserved else preserved.copy(actions = message.actions.deepCopy().apply {
                add("submitted", selection.deepCopy().apply { remove("renderId"); if (string("status") == null) addProperty("status", "submitted") })
            })
        })
        if (contactCenterEnabled && transferRevisionAtRequest == transferLiveRevision) {
            state.messages.lastOrNull { ContactCenterContracts.status(it) != null && it.transferSurvey == null }?.let { latest ->
                // Ignore historical state older than a live transition already observed.
                if (latest.timestamp >= lastTransferTransitionAt) {
                    lastTransferTransitionAt = latest.timestamp
                    state = state.copy(agentTransferStatus = ContactCenterContracts.status(latest)!!,
                        agentTransferSessionId = latest.metadata?.string("agentTransferSessionId"))
                }
            }
        }
    }
    private fun fingerprint(m: ChatMessage) = "${m.role}:${m.text}:${m.richContent}:${m.actions}:${m.attachments}:${m.agentLink}:${m.transferSurvey?.artemisSessionId}:${m.authorization?.let { m.messageId }}"
    private fun upsert(message: ChatMessage) {
        val previous = state.messages.find { it.messageId == message.messageId }
        val next = retainFeedbackResponses(previous, message)
        state = state.copy(messages = (if (previous != null) state.messages.map { if (it.messageId == message.messageId) next else it } else state.messages + next).takeLast(500))
    }
}
