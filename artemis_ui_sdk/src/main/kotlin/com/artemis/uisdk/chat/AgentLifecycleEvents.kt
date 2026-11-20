package com.artemis.uisdk.chat

import com.artemis.uisdk.ArtemisUiEvent
import com.google.gson.JsonObject
import java.net.URI

/** Projects `agent_transfer_event` frames into the Web SDK's typed agent lifecycle events (ABLP-6309). */
internal object AgentLifecycleEvents {
    fun project(eventType: String, data: JsonObject?, timestamp: Long, sessionId: String): ArtemisUiEvent? {
        if (sessionId.isBlank()) return null
        return when (eventType) {
            "agent:connected" -> {
                // A connect rebuilt from an agent message is not a real lifecycle change.
                if (data?.flag("syntheticConnected") == true) return null
                val info = data?.obj("agentInfo")
                val name = info?.nonEmpty("fullName")
                    ?: listOfNotNull(info?.nonEmpty("firstName"), info?.nonEmpty("lastName")).joinToString(" ").ifBlank { null }
                ArtemisUiEvent.AgentConnected(
                    sessionId = sessionId,
                    timestamp = timestamp,
                    transferred = data?.flag("transferred") == true,
                    agentName = name,
                    agentAvatarUrl = info?.nonEmpty("profImageUrl")?.takeIf(::isHttpUrl),
                )
            }
            "agent:transferred" -> ArtemisUiEvent.AgentTransferred(sessionId, timestamp, data?.nonEmpty("initiatedBy") ?: "unknown")
            "agent:disconnected" -> ArtemisUiEvent.AgentDisconnected(sessionId, timestamp, data?.nonEmpty("disconnectReason") ?: "unknown")
            else -> null
        }
    }

    private fun JsonObject.nonEmpty(key: String) = string(key)?.trim()?.takeIf(String::isNotEmpty)

    private fun isHttpUrl(value: String) = runCatching {
        val uri = URI(value)
        uri.isAbsolute && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
