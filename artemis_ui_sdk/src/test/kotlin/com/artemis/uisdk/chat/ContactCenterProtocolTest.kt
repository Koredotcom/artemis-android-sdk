package com.artemis.uisdk.chat

import com.artemis.uisdk.ArtemisAttachmentConfig
import com.artemis.uisdk.ArtemisConfigLoader
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ContactCenterProtocolTest {
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject
    private fun transcript(contact: Boolean = true, attachments: Boolean = true) = ChatTranscript(contact, attachments).apply {
        apply(ChatFrameParser.parse("""{"type":"session_start","sessionId":"runtime-session"}""")!!)
    }
    private fun transfer(type: String, data: String = "{}", id: String = "transfer-session", timestamp: String = "2026-10-15T10:00:00Z") =
        ChatFrameParser.parse("""{"type":"agent_transfer_event","sessionId":"$id","event":{"type":"$type","timestamp":"$timestamp","data":$data}}""")!!
    @Test fun transferIdMayDifferFromRuntimeConversationId() {
        val t = transcript(); t.apply(transfer("agent:connected")); t.apply(transfer("agent:message", """{"message":"Hello","messageId":"provider-1"}"""))
        assertEquals(AgentTransferStatus.CONNECTED, t.state.agentTransferStatus)
        assertEquals("transfer-session", t.state.agentTransferSessionId)
        assertEquals("runtime-session", t.state.sessionId); assertEquals("Hello", t.state.messages.last().text)
    }
    @Test fun featureSwitchesKeepLegacyStreamingAndHideNewCards() {
        val t = transcript(false, false); t.apply(transfer("agent:connected")); assertTrue(t.state.messages.isEmpty())
        t.apply(ChatFrameParser.parse("""{"type":"response_start","messageId":"a"}""")!!)
        t.apply(ChatFrameParser.parse("""{"type":"response_chunk","messageId":"a","content":"legacy"}""")!!)
        t.apply(ChatFrameParser.parse("""{"type":"response_end","messageId":"a","content":"legacy","metadata":{"attachmentIds":["f"],"agentTransferLinkUrl":"https://form.example.com"}}""")!!)
        assertEquals("legacy", t.state.messages.single().text); assertTrue(t.state.messages.single().attachments.isEmpty())
        assertNull(t.state.messages.single().agentLink); assertEquals(AgentTransferStatus.NONE, t.state.agentTransferStatus)
    }
    @Test fun typingIsEphemeralAndLateOldTransferCannotChangeCurrentTyping() {
        val t = transcript(); t.apply(transfer("agent:connected")); val count = t.state.messages.size
        t.apply(transfer("agent:typing")); assertTrue(t.state.isTyping); assertEquals(count, t.state.messages.size)
        t.apply(transfer("agent:typing_stop", id = "old-transfer")); assertTrue(t.state.isTyping)
        t.apply(transfer("agent:typing_stop")); assertFalse(t.state.isTyping)
    }
    @Test fun duplicateMessageIdsAndDiagnosticChangesDoNotAddRows() {
        val t = transcript(); t.apply(transfer("agent:message", """{"message":"Hello","messageId":"same"}"""))
        t.apply(transfer("agent:message", """{"message":"Hello","messageId":"same","providerResponse":"private"}"""))
        assertEquals(1, t.state.messages.size); assertFalse(t.state.messages.single().metadata.toString().contains("private"))
        val other = transcript(); other.apply(transfer("agent:message", """{"message":"Hello"}"""))
        other.apply(transfer("agent:message", """{"message":"Hello","providerResponse":"private"}"""))
        assertEquals(1, other.state.messages.size)
    }
    @Test fun staleTransitionDoesNotUndoDisconnectAndNewSessionClearsTransferState() {
        val t = transcript(); t.apply(transfer("agent:disconnected", timestamp = "2026-10-15T10:00:02Z"))
        t.apply(transfer("agent:connected")); assertEquals(AgentTransferStatus.DISCONNECTED, t.state.agentTransferStatus)
        t.apply(transfer("agent:typing")); assertFalse(t.state.isTyping)
        t.apply(ChatFrameParser.parse("""{"type":"session_start","sessionId":"new"}""")!!)
        assertEquals(AgentTransferStatus.NONE, t.state.agentTransferStatus); assertNull(t.state.agentTransferSessionId)
    }
    @Test fun transferEndsAssistantTypingAndAssistantChatStillWorksAfterDisconnect() {
        val t = transcript(); t.apply(ChatFrame.Start("bot")); t.apply(transfer("agent:connected"))
        assertFalse(t.state.isTyping); assertTrue(t.state.messages.none { it.messageId == "bot" })
        t.apply(ChatFrame.Chunk("bot", "late chunk")); assertFalse(t.state.isTyping); assertTrue(t.state.messages.none { it.messageId == "bot" })
        t.apply(transfer("agent:disconnected")); t.acceptedUser("u", "back to assistant"); t.apply(ChatFrame.Start("bot2")); t.apply(ChatFrame.Chunk("bot2", "Reply"))
        t.apply(ChatFrame.End(ChatMessage("bot2", role = MessageRole.ASSISTANT, text = "Reply")))
        assertEquals("Reply", t.state.messages.last().text); assertFalse(t.state.isTyping)
    }
    @Test fun queueDoesNotPretendAssistantIsTypingAndDurableStateWinsOverLateNotice() {
        val t = transcript(); t.apply(transfer("agent:queued")); t.acceptedUser("u", "waiting"); assertFalse(t.state.isTyping)
        val history = ChatFrameParser.history("""[{"id":"wait","role":"assistant","content":"Please wait","metadata":{"custom":{"source":"agent-transfer","eventType":"agent:waiting_message","transferState":"connected"}}}]""")
        val restored = transcript(); restored.mergeHistory(history); assertEquals(AgentTransferStatus.CONNECTED, restored.state.agentTransferStatus)
    }
    @Test fun clientTimestampOnTransferMarkerCannotRejectAProviderConnection() {
        val t = transcript()
        t.apply(ChatFrame.End(ChatMessage("marker", role = MessageRole.ASSISTANT, text = "Transferring", timestamp = Long.MAX_VALUE,
            metadata = json("""{"agentTransferEventType":"transfer_active"}"""))))
        t.apply(transfer("agent:connected")); assertEquals(AgentTransferStatus.CONNECTED, t.state.agentTransferStatus)
    }
    @Test fun metadataOnlyFormsRenderAndUnsafeLinksDoNotBecomeControls() {
        val t = transcript(); t.apply(ChatFrameParser.parse("""{"type":"response_end","messageId":"form","content":"","metadata":{"agentTransferLinkUrl":"https://forms.example.com/secure","agentTransferLinkTitle":"Complete form","agentTransferLinkType":"smartassist_form"}}""")!!)
        assertEquals(AgentLinkKind.FORM, t.state.messages.single().agentLink?.kind)
        assertNull(contactCenterUrl("javascript:alert(1)")); assertNull(contactCenterUrl("https://user:pass@example.com")); assertNull(contactCenterUrl("file:///tmp/a"))
        assertNotNull(contactCenterUrl("https://example.com/form"))
    }
    @Test fun transferredAttachmentUsesScopedIdAndRetainsFileMetadata() {
        val t = transcript(); t.apply(transfer("agent.attachment", """{"attachmentId":"a","runtimeSessionId":"runtime-session","fileName":"invoice.pdf","mimeType":"application/pdf","sizeBytes":123}"""))
        assertEquals(AttachmentRef("a", "invoice.pdf", "application/pdf", 123), t.state.messages.single().attachments.single())
    }
    @Test fun closeSurveyRequiresExplicitTriggerSupportedTypeAndTransferId() {
        val t = transcript()
        for (survey in listOf("{}", """{"triggerSurvey":false,"surveyType":"nps","artemisSessionId":"t"}""", """{"triggerSurvey":true,"surveyType":"other","artemisSessionId":"t"}""", """{"triggerSurvey":true,"surveyType":"csat","artemisSessionId":" "}""")) {
            t.apply(transfer("agent:disconnected", """{"survey":$survey}"""))
        }
        assertTrue(t.state.messages.none { it.transferSurvey != null })
        t.apply(transfer("agent:disconnected", """{"survey":{"triggerSurvey":true,"surveyType":"nps","artemisSessionId":"t","providerResponse":"private","comment_visibility":"conditional","comment_ratings":[0,1],"submit_label":"Send rating","max":100}}"""))
        val survey = t.state.messages.single { it.transferSurvey != null }.transferSurvey!!
        assertEquals(10, survey.max); assertEquals("Send rating", survey.submitLabel)
        assertTrue(survey.showComment(0)); assertFalse(survey.showComment(9)); assertFalse(survey.showComment(null))
        assertFalse(survey.toString().contains("private")); assertTrue(survey.validScore(0)); assertFalse(survey.validScore(11))
    }
    @Test fun historyRestoresFormsAndTransferStatusWithoutDuplicatingLiveProviderMessage() {
        val t = transcript(); t.apply(transfer("agent:message", """{"message":"Hello","messageId":"p1"}"""))
        val history = ChatFrameParser.history("""[{"id":"durable-id","role":"assistant","content":"Hello","timestamp":"2026-10-15T10:00:00Z","metadata":{"custom":{"source":"agent-transfer","transferSessionId":"transfer-session","providerMessageId":"p1","eventType":"agent:message","transferState":"connected","providerEventData":{"linkUrl":"https://forms.example.com","linkType":"smartassist_form"}}}}]""")
        // Link-bearing and plain messages are distinct; a matching provider identity is reconciled below.
        val matching = history.single().copy(agentLink = null)
        t.mergeHistory(listOf(matching)); assertEquals(1, t.state.messages.size)
        assertEquals("durable-id", t.state.messages.single().messageId); assertEquals(AgentTransferStatus.CONNECTED, t.state.agentTransferStatus)
        t.apply(transfer("agent:message", """{"message":"Hello","messageId":"p1"}""")); assertEquals(1, t.state.messages.size)
        assertEquals(AgentLinkKind.FORM, history.single().agentLink?.kind)
    }
    @Test fun historyRequestedBeforeLiveTransitionCannotUndoIt() {
        val t = transcript(); val revision = t.transferRevision; t.apply(transfer("agent:disconnected"))
        val history = ChatFrameParser.history("""[{"id":"a","role":"assistant","content":"Connected","timestamp":"2026-10-15T11:00:00Z","metadata":{"custom":{"source":"agent-transfer","transferSessionId":"transfer-session","eventType":"agent:connected"}}}]""")
        t.mergeHistory(history, revision); assertEquals(AgentTransferStatus.DISCONNECTED, t.state.agentTransferStatus)
    }
    @Test fun historyAndRedeliveryPreserveSubmittedSurveyState() {
        val t = transcript(); val frame = transfer("agent:disconnected", """{"survey":{"triggerSurvey":true,"surveyType":"csat","artemisSessionId":"t"}}""")
        t.apply(frame); val surveyMessage = t.state.messages.single { it.transferSurvey != null }
        t.update(t.state.copy(messages = t.state.messages.map { if (it.messageId == surveyMessage.messageId) it.copy(transferSurvey = it.transferSurvey!!.copy(status = SurveySubmissionStatus.SUBMITTED)) else it }))
        t.apply(frame); assertEquals(SurveySubmissionStatus.SUBMITTED, t.state.messages.single { it.transferSurvey != null }.transferSurvey!!.status)
        val history = ChatFrameParser.history("""[{"id":"close","role":"assistant","content":"Ended","metadata":{"custom":{"source":"agent-transfer","eventType":"agent:disconnected","providerEventData":{"survey":{"triggerSurvey":true,"surveyType":"csat","artemisSessionId":"t"}}}}}]""")
        t.mergeHistory(history); assertEquals(SurveySubmissionStatus.SUBMITTED, t.state.messages.single { it.transferSurvey != null }.transferSurvey!!.status)
    }
    @Test fun failedTransferAttachmentsRemainVisibleAndDisabledByDeliveryMetadata() {
        val messages = ChatFrameParser.history("""[{"id":"u","role":"user","content":"Files","metadata":{"attachmentIds":["sent","failed"],"attachmentFilenames":["a.pdf","b.pdf"],"attachmentSizeBytes":[1,2],"agentTransferAttachmentFailedIds":["failed"],"agentTransferAttachmentDeliveryStatus":"partial"}}]""")
        assertFalse(messages.single().attachments[0].deliveryFailed); assertTrue(messages.single().attachments[1].deliveryFailed)
        assertEquals(2L, messages.single().attachments[1].sizeBytes)
    }
    @Test fun attachmentMimePolicyAndLegacyYamlDefaultsAreExplicit() {
        val policy = ArtemisAttachmentConfig(); assertTrue(policy.allows("image/jpeg")); assertTrue(policy.allows("audio/wav")); assertFalse(policy.allows("application/x-executable"))
        assertEquals("application/pdf", AttachmentMime.resolve("report.PDF", "application/octet-stream"))
        val base = mapOf("connection" to mapOf("endpoint" to "https://runtime.example.com", "project_id" to "p", "api_key" to "pk"), "channel" to mapOf("channel_name" to "mobile"))
        val legacy = ArtemisConfigLoader.fromMap(base); assertFalse(legacy.features.attachments); assertFalse(legacy.features.contactCenter)
        val enabled = ArtemisConfigLoader.fromMap(base + mapOf("features" to mapOf("enable_attachments" to true, "enable_contact_center" to true), "attachments" to mapOf("max_file_size_bytes" to 1024, "allowed_mime_types" to listOf("application/pdf"))))
        assertTrue(enabled.features.attachments); assertTrue(enabled.features.contactCenter); assertEquals(1024L, enabled.attachment.maxFileSizeBytes)
        assertFalse(enabled.attachment.allows("image/png"))
    }
}
