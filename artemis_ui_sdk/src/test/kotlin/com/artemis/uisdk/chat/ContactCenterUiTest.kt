package com.artemis.uisdk.chat

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.artemis.uisdk.ArtemisFeatureConfig
import com.artemis.uisdk.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class ContactCenterUiTest {
    private val context get() = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ArtemisUI)
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun render(message: ChatMessage, callbacks: Callbacks = Callbacks(), features: ArtemisFeatureConfig = ArtemisFeatureConfig(attachments = true, contactCenter = true)) = LinearLayout(context).also {
        ContactCenterRenderer(features, callbacks).render(context, message, it)
    }
    @Test fun featureFlagsHideAttachmentFormAndSurveyControls() {
        val message = ChatMessage("a", role = MessageRole.ASSISTANT, text = "", attachments = listOf(AttachmentRef("f")), agentLink = AgentLink("https://forms.example.com", "Open form"), transferSurvey = AgentTransferSurvey("nps", "transfer"))
        assertEquals(0, render(message, features = ArtemisFeatureConfig()).childCount)
    }
    @Test fun surveyHonorsConditionalCommentsAndPreservesDraftInCallback() {
        val callbacks = Callbacks(); val message = ChatMessage("survey", role = MessageRole.ASSISTANT, text = "Rate us", transferSurvey = AgentTransferSurvey("nps", "transfer", commentVisibility = "conditional", commentRatings = listOf(0, 1), submitLabel = "Send rating"))
        val card = render(message, callbacks); val comments = all(card).filterIsInstance<EditText>().single()
        val submit = all(card).filterIsInstance<Button>().single { it.text == "Send rating" }
        assertFalse(submit.isEnabled); assertEquals(View.GONE, comments.visibility)
        all(card).filterIsInstance<Button>().single { it.text == "0" }.performClick(); assertEquals(View.VISIBLE, comments.visibility)
        comments.setText("Please improve"); submit.performClick(); assertEquals(0, callbacks.score); assertEquals("Please improve", callbacks.comments)
        all(card).filterIsInstance<Button>().single { it.text == "10" }.performClick(); assertEquals(View.GONE, comments.visibility)
        submit.performClick(); assertNull(callbacks.comments)
        assertEquals(SurveyDraft(10, "Please improve"), callbacks.drafts["survey"])
    }
    @Test fun surveyPendingIsDisabledAndSubmittedRendersSuccessOnly() {
        val survey = AgentTransferSurvey("csat", "transfer", pendingMessage = "Sending rating", successMessage = "Thank you")
        val message = ChatMessage("s", role = MessageRole.ASSISTANT, text = "Rate us", transferSurvey = survey.copy(status = SurveySubmissionStatus.PENDING))
        val pending = render(message); assertTrue(all(pending).filterIsInstance<Button>().all { !it.isEnabled })
        assertTrue(all(pending).filterIsInstance<TextView>().any { it.text == "Sending rating" })
        val done = render(message.copy(transferSurvey = survey.copy(status = SurveySubmissionStatus.SUBMITTED)))
        assertTrue(all(done).filterIsInstance<Button>().isEmpty()); assertTrue(all(done).filterIsInstance<TextView>().any { it.text == "Thank you" })
    }
    @Test fun failedSurveyKeepsDraftAndOffersRetry() {
        val callbacks = Callbacks().apply { drafts["s"] = SurveyDraft(3, "Saved comment") }
        val message = ChatMessage("s", role = MessageRole.ASSISTANT, text = "Rate us", transferSurvey = AgentTransferSurvey("csat", "t", status = SurveySubmissionStatus.FAILED, errorMessage = "Retry rating"))
        val card = render(message, callbacks)
        assertEquals("Saved comment", all(card).filterIsInstance<EditText>().single().text.toString())
        assertTrue(all(card).filterIsInstance<TextView>().any { it.text == "Retry rating" })
        all(card).filterIsInstance<Button>().single { it.text == "Submit" }.performClick(); assertEquals(3, callbacks.score)
    }
    @Test fun failedDeliveryAttachmentIsVisibleButCannotOpen() {
        val callbacks = Callbacks(); val message = ChatMessage("a", role = MessageRole.USER, text = "", attachments = listOf(AttachmentRef("f", "report.pdf", deliveryFailed = true)))
        val card = all(render(message, callbacks)).single { it.contentDescription?.contains("report.pdf") == true }
        assertFalse(card.isEnabled); assertTrue(card.contentDescription.contains("not sent")); assertEquals(0, callbacks.opens)
    }
    @Test fun formOpensBrowserAndSubmitsNoChatAction() {
        val callbacks = Callbacks(); val message = ChatMessage("form", role = MessageRole.ASSISTANT, text = "", agentLink = AgentLink("https://forms.example.com/secure", "Complete form", AgentLinkKind.FORM))
        val button = all(render(message, callbacks)).filterIsInstance<Button>().single(); button.performClick()
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals("https://forms.example.com/secure", intent.dataString)
        assertEquals(0, callbacks.actions)
    }
    private class Callbacks : ChatMessageAdapter.Callbacks {
        val drafts = mutableMapOf<String, SurveyDraft>(); var score: Int? = null; var comments: String? = null; var actions = 0; var opens = 0
        override fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String,String>?, renderIdentifier: String?) { actions++ }
        override fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?) = Unit
        override fun isLocked(message: ChatMessage, actionIdentifier: String) = false
        override fun onAttachment(message: ChatMessage, attachment: AttachmentRef) { opens++ }
        override fun surveyDraft(message: ChatMessage) = drafts[message.messageId] ?: SurveyDraft()
        override fun onSurveyDraft(message: ChatMessage, draft: SurveyDraft) { drafts[message.messageId] = draft }
        override fun onTransferSurvey(message: ChatMessage, score: Int, comments: String?) { this.score = score; this.comments = comments }
    }
}
