package com.artemis.uisdk.chat

import com.google.gson.JsonObject
import java.net.URI
import java.util.UUID

enum class AuthorizationKind { PREFLIGHT, CHALLENGE }
enum class AuthorizationStatus { PENDING, AUTHORIZING, VERIFYING, CANCELLING, CONNECTED, CANCELLED, FAILED }

/** URLs may contain OAuth state. Never log or persist an authorization card. */
data class AuthorizationRow(
    val id: String,
    val label: String,
    val authUrl: String? = null,
    val authProfileRef: String? = null,
    val connector: String? = null,
    val prompt: String = "",
    val canCancel: Boolean = false,
    val blocking: Boolean = false,
    val status: AuthorizationStatus = AuthorizationStatus.PENDING,
    val attempted: Boolean = false,
    val timeoutMs: Long? = null,
) {
    override fun toString() = "AuthorizationRow(status=$status, authUrl=<redacted>)"
}

data class AuthorizationCard(
    val sessionId: String,
    val kind: AuthorizationKind,
    val rows: List<AuthorizationRow>,
    val active: Boolean = true,
) {
    override fun toString() = "AuthorizationCard(kind=$kind, rows=${rows.size}, active=$active)"
}

internal sealed interface AuthorizationEvent {
    val sessionId: String?
    data class Gate(override val sessionId: String, val pending: List<AuthorizationRow>, val satisfied: List<AuthorizationRow>, val append: Boolean) : AuthorizationEvent
    data class Challenge(override val sessionId: String, val row: AuthorizationRow) : AuthorizationEvent
    data class Satisfied(override val sessionId: String) : AuthorizationEvent
    data class Resolved(override val sessionId: String, val id: String, val cancelled: Boolean) : AuthorizationEvent
    data class Queued(override val sessionId: String?) : AuthorizationEvent
}

internal object AuthorizationContracts {
    val eventTypes = setOf("auth_required", "auth_gate_updated", "auth_gate_satisfied", "auth_challenge", "auth_challenge_resolved", "message_queued")
    fun url(value: String?): String? = value?.takeIf { it.length <= 16_384 }?.let { raw ->
        runCatching { URI(raw) }.getOrNull()?.takeIf {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.rawUserInfo == null
        }?.toString()
    }
    private fun JsonObject.identity(key: String) = string(key)?.takeIf { it.isNotBlank() && it.length <= 8192 }
    private fun requirements(p: JsonObject, key: String, connected: Boolean): List<AuthorizationRow>? {
        val values = p.array(key) ?: return null
        if (values.size() > 50) return null
        val rows = values.map { value ->
            val r = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val profile = r.identity("authProfileRef") ?: return null
            val connector = r.identity("connector") ?: return null
            if (r.string("connectionMode") !in setOf("shared", "per_user")) return null
            val id = r.identity("requirementKey") ?: profile
            AuthorizationRow(id, (r.string("profileName")?.takeIf(String::isNotBlank) ?: connector).take(256), url(r.string("authUrl")),
                profile, connector, canCancel = r.string("usageMode") == "jit", blocking = r.flag("blockingAuth"),
                status = if (connected) AuthorizationStatus.CONNECTED else AuthorizationStatus.PENDING)
        }
        return rows.takeIf { it.map(AuthorizationRow::id).distinct().size == it.size }
    }
    fun parse(p: JsonObject): AuthorizationEvent? {
        if (p.string("type") == "message_queued") return AuthorizationEvent.Queued(p.identity("sessionId"))
        val session = p.identity("sessionId") ?: return null
        return when (p.string("type")) {
            "auth_required", "auth_gate_updated" -> AuthorizationEvent.Gate(session,
                requirements(p, "pending", false) ?: return null, requirements(p, "satisfied", true) ?: return null,
                p.string("type") == "auth_required" && p.string("presentation") == "append")
            "auth_gate_satisfied" -> AuthorizationEvent.Satisfied(session)
            "auth_challenge" -> AuthorizationEvent.Challenge(session, AuthorizationRow(
                p.identity("toolCallId") ?: return null,
                (p.string("profileName")?.takeIf(String::isNotBlank) ?: p.string("authType") ?: "Authorization").take(256),
                url(p.string("authUrl")), prompt = p.string("prompt").orEmpty().take(2000), canCancel = true,
                timeoutMs = p.number("timeoutMs")?.takeIf { it > 0 }))
            "auth_challenge_resolved" -> {
                val outcome = p.string("outcome")
                if (outcome != null && outcome !in setOf("authorized", "cancelled")) return null
                AuthorizationEvent.Resolved(session, p.identity("toolCallId") ?: return null, outcome == "cancelled")
            }
            else -> null
        }
    }
}

/** Live auth events are session scoped. Only Runtime events produce CONNECTED. */
internal class AuthorizationTranscript {
    private var activeGate: String? = null
    private var activeChallenge: String? = null
    fun clear() { activeGate = null; activeChallenge = null }
    fun nextTurn() { activeChallenge = null }
    fun apply(state: ChatUiState, event: AuthorizationEvent): ChatUiState {
        if (state.sessionId == null || event.sessionId != null && event.sessionId != state.sessionId) return state
        var messages = state.messages
        fun replace(message: ChatMessage) {
            messages = (if (messages.any { it.messageId == message.messageId }) messages.map { if (it.messageId == message.messageId) message else it }
                else messages + message).takeLast(500)
        }
        when (event) {
            is AuthorizationEvent.Gate -> {
                val old = messages.find { it.messageId == activeGate }
                if (event.append && old != null) replace(old.copy(authorization = old.authorization?.copy(active = false)))
                val previous = old?.authorization?.takeUnless { event.append }?.rows.orEmpty()
                val pendingIds = event.pending.map { it.id }.toSet()
                val satisfied = (event.satisfied + previous.filter { it.id !in pendingIds }).distinctBy { it.id }
                    .map { it.copy(status = AuthorizationStatus.CONNECTED) }
                val connectedIds = satisfied.map { it.id }.toSet()
                val pending = event.pending.filter { it.id !in connectedIds }.map { row ->
                    previous.find { it.id == row.id && it.authUrl == row.authUrl }?.let { retained ->
                        row.copy(status = retained.status.takeUnless { it in setOf(AuthorizationStatus.CONNECTED, AuthorizationStatus.CANCELLED, AuthorizationStatus.VERIFYING) } ?: AuthorizationStatus.PENDING,
                            attempted = retained.attempted)
                    } ?: row
                }
                if (satisfied.size + pending.size > 100) return state
                val id = if (event.append) null else old?.messageId
                val message = (old?.takeUnless { event.append } ?: ChatMessage(id ?: "auth-gate-${UUID.randomUUID()}", role = MessageRole.SYSTEM, text = ""))
                    .copy(authorization = AuthorizationCard(event.sessionId, AuthorizationKind.PREFLIGHT, satisfied + pending))
                activeGate = message.messageId
                replace(message)
            }
            is AuthorizationEvent.Challenge -> {
                val owner = messages.find { it.authorization?.kind == AuthorizationKind.CHALLENGE && it.authorization.rows.any { r -> r.id == event.row.id } }
                    ?: messages.find { it.messageId == activeChallenge }
                val previous = owner?.authorization?.rows.orEmpty()
                val retained = previous.find { it.id == event.row.id }
                val row = if (retained == null || retained.authUrl != event.row.authUrl && retained.status != AuthorizationStatus.CONNECTED) event.row
                    else event.row.copy(status = retained.status, attempted = retained.attempted)
                val rows = if (retained == null) previous + row else previous.map { if (it.id == row.id) row else it }
                if (rows.size > 50) return state
                val message = (owner ?: ChatMessage("auth-challenge-${UUID.randomUUID()}", role = MessageRole.SYSTEM, text = ""))
                    .copy(authorization = AuthorizationCard(event.sessionId, AuthorizationKind.CHALLENGE, rows))
                activeChallenge = message.messageId
                replace(message)
            }
            is AuthorizationEvent.Satisfied -> {
                messages = messages.map { m -> m.authorization?.takeIf { it.kind == AuthorizationKind.PREFLIGHT && it.sessionId == event.sessionId }?.let { card ->
                    m.copy(authorization = card.copy(active = false, rows = card.rows.map { it.copy(status = AuthorizationStatus.CONNECTED) }))
                } ?: m }
                activeGate = null
            }
            is AuthorizationEvent.Resolved -> {
                messages = messages.map { m -> m.authorization?.takeIf { it.sessionId == event.sessionId }?.let { card ->
                    m.copy(authorization = card.copy(rows = card.rows.map { row ->
                        if (row.id != event.id || row.status == AuthorizationStatus.CONNECTED) row
                        else row.copy(status = if (event.cancelled) AuthorizationStatus.CANCELLED else AuthorizationStatus.CONNECTED)
                    }))
                } ?: m }
            }
            is AuthorizationEvent.Queued -> Unit // Runtime retains/replays the held message. Never resend it here.
        }
        val halted = event is AuthorizationEvent.Gate || event is AuthorizationEvent.Challenge || event is AuthorizationEvent.Queued
        if (halted) messages = messages.filterNot { it.role == MessageRole.ASSISTANT && it.messageStatus == MessageStatus.STREAMING && it.text.isBlank() && !it.hasTemplatePresentation() }
        return state.copy(messages = messages, isTyping = if (halted) false else state.isTyping,
            activityText = if (halted) null else state.activityText,
            initialAssistantMessagePending = if (halted) false else state.initialAssistantMessagePending)
    }
}
