package com.artemis.uisdk.chat

import com.artemis.uisdk.ArtemisUiEvent
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Web SDK parity: media declines, auth refresh signals and typed agent lifecycle events. */
class WebParityEventsTest {
    private fun media(data: String) = ChatFrameParser.parse("""{"type":"agent_media","event":{"type":"agent:media","data":$data}}""")

    @Test fun screenShareAndCobrowseInvitesAreDeclinedButEndFramesAreIgnored() {
        val share = media("""{"providerEventType":"webrtc_screenshare","action":"invite","conversationId":"c","mediaSessionId":"m"}""")
        assertEquals(ContactCenterMediaFrame.UnsupportedInvite("screen_share", "c", "m"), (share as ChatFrame.ContactCenterMedia).event)
        val bare = media("""{"providerEventType":"webrtc_screenshare","callId":"call"}""")
        assertEquals("call", ((bare as ChatFrame.ContactCenterMedia).event as ContactCenterMediaFrame.UnsupportedInvite).mediaSessionId)
        val cobrowse = media("""{"mediaKind":"cobrowse","action":"invite","conversationId":"c","mediaSessionId":"m"}""")
        assertEquals("cobrowse", ((cobrowse as ChatFrame.ContactCenterMedia).event as ContactCenterMediaFrame.UnsupportedInvite).kind)
        assertNull(media("""{"providerEventType":"webrtc_screenshare","action":"terminated"}"""))
        assertNull(media("""{"mediaKind":"cobrowse","action":"reconnect"}"""))
        assertNull(media("""{"providerEventType":"webrtc_screenshare_end","action":"ended"}"""))
        val viaTransfer = ContactCenterContracts.transfer(JsonParser.parseString(
            """{"sessionId":"t","event":{"type":"agent:media","data":{"mediaKind":"cobrowse","action":"invite","mediaSessionId":"m"}}}""").asJsonObject)
        assertTrue(viaTransfer?.mediaFrame is ContactCenterMediaFrame.UnsupportedInvite)
    }

    @Test fun authRefreshFramesBecomeHostSignalsWithWebValidation() {
        val refreshed = ChatFrameParser.parse("""{"type":"auth_token_refreshed","sessionId":"s","event":{"profile":"crm","reason":"renewal","issuedAt":"2026-11-20T10:00:00Z","refreshAfter":"2026-11-20T10:50:00Z"}}""")
        assertEquals(ArtemisUiEvent.AuthTokenRefreshed("s", "crm", "renewal", "2026-11-20T10:00:00Z", "2026-11-20T10:50:00Z"),
            (refreshed as ChatFrame.HostSignal).event)
        assertNull(ChatFrameParser.parse("""{"type":"auth_token_refreshed","event":{"profile":"crm","reason":"other","issuedAt":"a","refreshAfter":"b"}}"""))
        val requested = ChatFrameParser.parse("""{"type":"auth_context_refresh_requested","event":{"profile":"crm","refreshTicket":"ticket","expiresAt":"2026-11-20T11:00:00Z"}}""")
        assertEquals(ArtemisUiEvent.AuthContextRefreshRequested(null, "crm", "ticket", "2026-11-20T11:00:00Z"), (requested as ChatFrame.HostSignal).event)
        assertNull(ChatFrameParser.parse("""{"type":"auth_context_refresh_requested","event":{"profile":"crm","refreshTicket":"","expiresAt":"x"}}"""))
    }

    @Test fun agentLifecycleEventsProjectDisplayDataOnly() {
        fun data(json: String) = JsonParser.parseString(json).asJsonObject
        assertEquals(ArtemisUiEvent.AgentConnected("s", 5, true, "Priya Rao", "https://cdn.example.com/p.png"),
            AgentLifecycleEvents.project("agent:connected", data("""{"transferred":true,"agentInfo":{"firstName":"Priya","lastName":"Rao","profImageUrl":"https://cdn.example.com/p.png","email":"x@example.com"}}"""), 5, "s"))
        val unsafe = AgentLifecycleEvents.project("agent:connected", data("""{"agentInfo":{"fullName":"Sam","profImageUrl":"javascript:alert(1)"}}"""), 5, "s") as ArtemisUiEvent.AgentConnected
        assertEquals("Sam", unsafe.agentName); assertNull(unsafe.agentAvatarUrl); assertFalse(unsafe.transferred)
        assertNull(AgentLifecycleEvents.project("agent:connected", data("""{"syntheticConnected":true}"""), 5, "s"))
        assertEquals(ArtemisUiEvent.AgentTransferred("s", 5, "agent"), AgentLifecycleEvents.project("agent:transferred", data("""{"initiatedBy":"agent"}"""), 5, "s"))
        assertEquals(ArtemisUiEvent.AgentDisconnected("s", 5, "unknown"), AgentLifecycleEvents.project("agent:disconnected", null, 5, "s"))
        assertNull(AgentLifecycleEvents.project("agent:message", null, 5, "s"))
        assertNull(AgentLifecycleEvents.project("agent:connected", null, 5, ""))
        val transferred = ContactCenterContracts.transfer(JsonParser.parseString("""{"sessionId":"t","event":{"type":"agent:transferred","data":{"initiatedBy":"agent"}}}""").asJsonObject)
        assertEquals("agent:transferred", transferred?.eventType); assertTrue(transferred!!.messages.isEmpty())
    }
}
