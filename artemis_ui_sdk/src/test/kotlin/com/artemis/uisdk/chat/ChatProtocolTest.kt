package com.artemis.uisdk.chat

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun deepLinkTemplateKeepsIndependentValidatedActions() {
        val payload = com.google.gson.JsonParser.parseString("""{"text":"Choose an action","lang":"en","elements":[{"title":"Funds Transfer","url":"https://app.example.com/transfers","navigation_type":"internal"},{"title":"Contact Support","url":"https://support.example.com","navigation_type":"external"},{"title":"Bad","url":"javascript:alert(1)","navigation_type":"external"},{"title":"Missing URL","navigation_type":"internal"}]}""").asJsonObject
        val template = TemplateContracts.deepLink(payload)
        assertEquals("Choose an action", template.text)
        assertEquals("en", template.lang)
        assertEquals(2, template.elements.size)
        assertEquals(TemplateContracts.DeepLinkNavigation.INTERNAL, template.elements[0].navigation)
        assertEquals(TemplateContracts.DeepLinkNavigation.EXTERNAL, template.elements[1].navigation)
    }

    @Test fun deepLinkInternalKeepsRawHostRouteAndExternalStaysHttp() {
        val payload = JsonParser.parseString("""{"text":"Terms and Conditions","lang":"en","elements":[{"title":"Internal Condition","url":"/TermsAndCondition","navigation_type":"internal"},{"title":"External Conditions","url":"https://www.kore.ai","navigation_type":"external"},{"title":"Relative external","url":"/terms","navigation_type":"external"},{"title":"Script","url":"javascript:alert(1)","navigation_type":"internal"},{"title":"App scheme","url":"myapp://terms","navigation_type":"internal"}]}""").asJsonObject
        val template = TemplateContracts.deepLink(payload, base = "https://runtime.example.com")
        assertEquals(
            listOf(
                TemplateContracts.DeepLinkElement("Internal Condition", "/TermsAndCondition", TemplateContracts.DeepLinkNavigation.INTERNAL),
                TemplateContracts.DeepLinkElement("External Conditions", "https://www.kore.ai", TemplateContracts.DeepLinkNavigation.EXTERNAL),
                TemplateContracts.DeepLinkElement("Relative external", "https://runtime.example.com/terms", TemplateContracts.DeepLinkNavigation.EXTERNAL),
                TemplateContracts.DeepLinkElement("App scheme", "myapp://terms", TemplateContracts.DeepLinkNavigation.INTERNAL),
            ),
            template.elements,
        )
    }

    private fun frame(raw: String) = ChatFrameParser.parse(raw)!!
    private fun transcript(redacted: Boolean = false) = ChatTranscript().apply {
        apply(frame("""{"type":"session_start","sessionId":"s","capabilities":{"liveUserInputRedaction":$redacted}}"""))
    }
    @Test fun legacyStreamingKeepsTextAndTimestamp() {
        val t = transcript(); t.acceptedUser("u", "hello")
        t.apply(frame("""{"type":"response_start","messageId":"a"}""")); val timestamp = t.state.messages.last().timestamp
        t.apply(frame("""{"type":"response_chunk","messageId":"a","chunk":"one "}"""))
        t.apply(frame("""{"type":"response_chunk","messageId":"a","text":"two"}"""))
        t.apply(frame("""{"type":"response_end","messageId":"a"}"""))
        assertEquals("one two", t.state.messages.last().text); assertEquals(timestamp, t.state.messages.last().timestamp)
        assertFalse(t.state.isTyping)
    }
    @Test fun redactedUserNeverStoresRawTextOrMetadata() {
        val t = transcript(true); t.acceptedUser("u", "secret@example.com", JsonParser.parseString("""{"display_label":"secret@example.com"}""").asJsonObject)
        assertEquals("••••", t.state.messages.single().text); assertNull(t.state.messages.single().metadata)
        t.apply(frame("""{"type":"user_message_rendered","messageId":"u","content":"email [REDACTED_EMAIL]","status":"rendered"}"""))
        assertEquals("email ••••", t.state.messages.single().text); assertFalse(t.state.messages.single().pendingRender)
    }
    @Test fun withheldAndDuplicateRenderCannotLeakRawText() {
        val t = transcript(true); t.acceptedUser("u", "private")
        t.apply(frame("""{"type":"user_message_rendered","messageId":"u","content":"unsafe","status":"withheld"}"""))
        t.apply(frame("""{"type":"user_message_rendered","messageId":"u","content":"unsafe","status":"rendered"}"""))
        assertEquals("••••", t.state.messages.single().text)
    }
    @Test fun earlyRenderIsReconciledWhenUserMessageIsAccepted() {
        val t = transcript(true); t.apply(ChatFrame.UserRender("u", "safe", false)); t.acceptedUser("u", "private")
        assertEquals("safe", t.state.messages.single().text)
    }
    @Test fun privacyOffPreservesExistingUserEcho() {
        val t = transcript(); t.acceptedUser("u", "hello"); assertEquals("hello", t.state.messages.single().text)
    }
    @Test fun cancellationIgnoresLateChunksAndEndsOwnedActivity() {
        val t = transcript(); t.acceptedUser("u", "hello"); t.apply(ChatFrame.Start("a")); t.apply(ChatFrame.Chunk("a", "partial"))
        t.apply(ChatFrame.Cancel("a", "u", "timeout")); t.apply(ChatFrame.Chunk("a", "late")); t.apply(ChatFrame.End(ChatMessage("a", role=MessageRole.ASSISTANT,text="late")))
        assertEquals("partial", t.state.messages.last().text); assertEquals(MessageStatus.CANCELLED,t.state.messages.last().messageStatus); assertFalse(t.state.isTyping)
    }
    @Test fun oldTurnCancellationDoesNotClearNewTyping() {
        val t = transcript(); t.acceptedUser("old", "one"); t.apply(ChatFrame.Start("a")); t.acceptedUser("new", "two")
        t.apply(ChatFrame.Cancel("a", "old", "preempted")); assertTrue(t.state.isTyping)
    }
    @Test fun serverTypingCreatesNoEmptyMessage() {
        val t = transcript(); t.apply(ChatFrame.Typing(true)); assertTrue(t.state.isTyping); assertTrue(t.state.messages.isEmpty())
        t.apply(ChatFrame.Typing(false)); assertFalse(t.state.isTyping)
    }
    @Test fun activityRequiresServerPolicy() {
        val t = transcript(); val activity = frame("""{"type":"status_update","text":"Working"}""")
        t.apply(activity); assertNull(t.state.activityText)
        t.configureScope(JsonParser.parseString("""{"showActivityUpdates":true,"activityVisibilityPolicy":{"enabled":true,"fillers":true,"maxTextChars":4}}""").asJsonObject)
        t.apply(activity); assertEquals("Work",t.state.activityText)
        t.apply(frame("""{"type":"thought","thought":"hidden reasoning"}""")); assertTrue(t.state.messages.isEmpty())
        t.apply(frame("""{"type":"status_clear"}""")); assertNull(t.state.activityText)
    }
    @Test fun historyDeduplicatesByIdAndKeepsRepeatedMissedReplies() {
        val t = transcript(); t.apply(ChatFrame.End(ChatMessage("a",role=MessageRole.ASSISTANT,text="same",timestamp=1)))
        t.mergeHistory(listOf(ChatMessage("a",role=MessageRole.ASSISTANT,text="same",timestamp=1), ChatMessage("b",role=MessageRole.ASSISTANT,text="same",timestamp=2)))
        assertEquals(listOf("a","b"),t.state.messages.map{it.messageId})
        t.mergeHistory(t.state.messages); assertEquals(2,t.state.messages.size)
    }
    @Test fun clearLocalHistoryResetsMessagesButKeepsSession() {
        val t = transcript(); t.acceptedUser("u", "hello"); t.apply(ChatFrame.Start("stream"))
        val session = t.state.sessionId
        t.clearMessages()
        assertTrue(t.state.messages.isEmpty()); assertEquals(session, t.state.sessionId)
        t.acceptedUser("next", "new message"); assertEquals(listOf("next"), t.state.messages.map { it.messageId })
    }
    @Test fun historyMergesUnmatchedLocalEchoOnceAndKeepsActiveStream() {
        val t = transcript(); t.acceptedUser("local","hello"); t.apply(ChatFrame.Start("stream"))
        t.mergeHistory(listOf(ChatMessage("server",role=MessageRole.USER,text="hello",timestamp=1)))
        assertEquals(listOf("server","stream"),t.state.messages.map{it.messageId})
    }
    @Test fun malformedAndUnknownFramesAreIgnored() {
        listOf("no json", "[]", "{\"type\":\"response_chunk\",\"messageId\":3}","{\"type\":\"future_feature\"}").forEach { assertNull(ChatFrameParser.parse(it)) }
    }
    @Test fun envelopeCitationsAndRenderablesSurviveNormalization() {
        val f = frame("""{"type":"response_end","messageId":"a","contentEnvelope":{"text":"answer","richContent":{"type":"card","title":"Card","body":"Body"},"renderables":[{"name":"custom","payload":{},"fallbackText":"fallback"}],"metadata":{"x":"y"}},"citations":[{"index":1,"title":"Source","url":"https://example.com"}],"messageSource":{"system":"integration","provider":"support"}}""") as ChatFrame.End
        assertEquals("answer",f.message.text); assertTrue(f.message.richContent!!.string("markdown")!!.contains("Card"))
        assertEquals(1,f.message.citations.size); assertEquals(1,f.message.renderables!!.size()); assertEquals("support",f.message.messageSource!!.string("provider"))
    }
    @Test fun historySkipsInvalidRecordsAndMasksRedactionLabels() {
        val messages=ChatFrameParser.history("""[{"id":"u","role":"user","content":"[REDACTED_EMAIL]","timestamp":"2026-10-13T00:00:00Z"},{"role":"assistant"},null]""")
        assertEquals(1,messages.size); assertEquals("••••",messages.single().text); assertEquals(1791849600000L,messages.single().timestamp)
    }
    @Test fun newSessionResetsPreviousConversationState() {
        val t=transcript(true);t.acceptedUser("u","private");t.apply(frame("""{"type":"session_start","sessionId":"new"}"""))
        assertTrue(t.state.messages.isEmpty()); assertFalse(t.redaction)
    }
    @Test fun durableSequenceWinsOverInconsistentHistoryTimestamps() {
        val t=transcript();t.mergeHistory(listOf(ChatMessage("a",role=MessageRole.ASSISTANT,text="one",timestamp=100,sequence=1),ChatMessage("b",role=MessageRole.ASSISTANT,text="two",timestamp=1,sequence=2)))
        assertEquals(listOf("a","b"),t.state.messages.map{it.messageId})
    }
    @Test fun historyRestoresRenderScopedSubmissionState() {
        val t=transcript();val messages=ChatFrameParser.history("""[{"id":"a","role":"assistant","contentEnvelope":{"actions":{"renderId":"r","elements":[]}}},{"id":"u","role":"user","metadata":{"custom":{"actionSubmit":{"renderId":"r","actionId":"yes"}}}}]""")
        t.mergeHistory(messages);assertEquals("submitted",t.state.messages.first{it.messageId=="a"}.actions!!.obj("submitted")!!.string("status"))
    }
    @Test fun redactedHistoryCannotOverrideBubbleUsingRawDisplayLabel() {
        val t=transcript(true);t.mergeHistory(ChatFrameParser.history("""[{"id":"u","role":"user","content":"[REDACTED_EMAIL]","metadata":{"display_label":"private@example.com"}}]"""))
        assertEquals("••••",t.state.messages.single().text);assertFalse(t.state.messages.single().metadata!!.has("display_label"))
    }
    @Test fun legacyTraceEventShapeAndDefaultThoughtPolicyAreSupported() {
        val t=transcript();t.configureScope(JsonParser.parseString("""{"showActivityUpdates":true}""").asJsonObject)
        t.apply(frame("""{"type":"trace_event","eventType":"status_update","data":{"text":"Working"}}"""));assertEquals("Working",t.state.activityText)
        t.apply(frame("""{"type":"trace_event","event":{"type":"tool_thought","data":{"thought":"private"}}}"""));assertTrue(t.state.messages.isEmpty())
    }
    @Test fun legacyActionArraysAndObjectPayloadsNormalizeWithoutLosingValues() {
        val f=frame("""{"type":"response_end","messageId":"a","actions":[{"id":"select","label":"Choose","payload":{"sku":42}}]}""") as ChatFrame.End
        val action=f.message.actions!!.array("elements")!![0].asJsonObject
        assertEquals("button",action.string("type"));assertEquals("{\"sku\":42}",action.string("value"))
    }
    @Test fun buttonLinksAndValuesHaveSeparateContracts() {
        val button=JsonParser.parseString("""{"id":"buy","label":"Buy","value":"sku","url":"https://shop.example.com"}""").asJsonObject
        assertEquals("sku",TemplateContracts.actionValue(button));assertEquals("https://shop.example.com",TemplateContracts.actionUrl(button.string("url")))
        assertNull(TemplateContracts.actionUrl("javascript:alert(1)")); assertNull(TemplateContracts.actionUrl("https://user:password@example.com"))
        assertEquals("tel:+123",TemplateContracts.actionUrl("tel:+123"))
    }
    @Test fun pieChartPartitionsCircleAndIgnoresInvalidValues() {
        assertEquals(listOf(90f,270f),TemplateContracts.pieSweeps(listOf(1.0,3.0)))
        assertEquals(listOf(0f,0f,360f),TemplateContracts.pieSweeps(listOf(Double.NaN,-1.0,2.0)))
    }

    @Test fun phaseOneLifecycleAndAcknowledgementFramesPreserveWirePayloads() {
        val auth = frame("""{"type":"auth_challenge","sessionId":"s","toolCallId":"tool","authType":"oauth","profileId":"p","profileName":"Profile","prompt":"Authorize","timeoutMs":1000}""") as ChatFrame.Authorization
        assertEquals("auth_challenge", auth.payload.string("type"))
        assertEquals("tool", (auth.event as AuthorizationEvent.Challenge).row.id)
        assertTrue(frame("""{"type":"feedback.ack","messageId":"m","success":true,"feedbackId":"f"}""") is ChatFrame.FeedbackAck)
        assertTrue(frame("""{"type":"agent_transfer_survey.ack","artemisSessionId":"t","success":false}""") is ChatFrame.SurveyAck)
        assertTrue(frame("""{"type":"session_ended","reason":"server"}""") is ChatFrame.SessionEnd)
    }

    @Test fun initialAssistantStatusTracksAllWebStateFields() {
        val t = transcript()
        t.apply(frame("""{"type":"session_start","sessionId":"new","initialAssistantMessage":{"source":"dsl_on_start","expected":true}}"""))
        assertEquals("dsl_on_start", t.state.initialAssistantMessageSource)
        assertTrue(t.state.initialAssistantMessageExpected)
        assertTrue(t.state.initialAssistantMessagePending)
        t.apply(frame("""{"type":"initial_assistant_message_status","source":"dsl_on_start","status":"completed","rendered":true}"""))
        assertFalse(t.state.initialAssistantMessagePending)
        assertTrue(t.state.initialAssistantMessageRendered)
        assertFalse(t.state.initialAssistantMessageFailed)
    }

    @Test fun agentNoticesAreAcceptedAndRenderedAsSystemMessages() {
        val t = transcript()
        t.apply(frame("""{"type":"agent_transfer_event","sessionId":"transfer","event":{"type":"agent:joined","timestamp":"2026-10-15T10:00:00Z","data":{"noticeText":"Agent joined"}}}"""))
        t.apply(frame("""{"type":"agent_transfer_event","sessionId":"transfer","event":{"type":"agent:notice","timestamp":"2026-10-15T10:00:01Z","data":{"noticeText":"Please hold"}}}"""))
        assertEquals(listOf("Agent joined", "Please hold"), t.state.messages.map { it.text })
        assertTrue(t.state.messages.all { it.role == MessageRole.SYSTEM })
    }

    @Test fun statusClearEndsTheVisibleActivityWithoutCreatingATypingRow() {
        val t = transcript()
        t.configureScope(JsonParser.parseString("""{"showActivityUpdates":true,"activityVisibilityPolicy":{"enabled":true,"fillers":true}}""").asJsonObject)
        t.apply(frame("""{"type":"status_update","text":"Working"}"""))
        assertTrue(t.state.isTyping)
        t.apply(frame("""{"type":"status_clear"}"""))
        assertFalse(t.state.isTyping)
        assertNull(t.state.activityText)
        assertTrue(t.state.messages.isEmpty())
    }
}
