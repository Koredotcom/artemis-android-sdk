package com.artemis.uisdk.chat

import android.content.Intent
import android.widget.LinearLayout
import android.widget.Button
import com.artemis.uisdk.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Native UI regression fixtures. No connection is started and no URL is fetched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeChatRegressionTest {
    private val context get() = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ArtemisUI)
    private fun handle() = ArtemisUI.initialize(context, ArtemisUIConfig(ArtemisConnectionConfig(
        "https://runtime.example.com", "project", "pk", channelName = "mobile")))
    @Test fun minimizeRetainsConversationAndHandleCloseReleasesIt() {
        val handle=handle()
        val model=ArtemisChatViewModel(handle)
        handle.chatViewModel=model
        handle.createChatFragment()
        handle.minimizeChat()
        assertFalse(handle.isChatOpen); assertTrue(handle.isMinimized)
        handle.createChatFragment()
        assertSame(model,handle.chatViewModel);assertFalse(handle.isMinimized)
        handle.close(); assertNull(handle.chatViewModel)
    }
    @Test fun explicitConversationCloseAllowsFreshReopen() {
        val handle=handle();val model=ArtemisChatViewModel(handle);handle.chatViewModel=model
        model.closeSession("CLOSE");assertNull(handle.chatViewModel);assertFalse(handle.isChatOpen)
        assertNotNull(handle.createChatFragment());handle.close()
    }
    @Test fun urlButtonNavigatesWithoutSubmittingAnAction() {
        val callbacks=Callbacks();val parent=LinearLayout(context)
        val renderer=DefaultRichTemplateRenderer(ArtemisFeatureConfig(),ArtemisThemeConfig(),callbacks)
        val message=ChatMessage("a", "a", MessageRole.ASSISTANT, "")
        renderer.renderActions(context,message,parent,JsonParser.parseString("""{"elements":[{"type":"button","id":"link","label":"Open","url":"https://example.com"}]}""").asJsonObject)
        (parent.getChildAt(0) as Button).performClick()
        assertEquals(0,callbacks.actions)
        val intent=shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("https://example.com",intent.dataString)
    }
    @Test fun carouselButtonPreservesSemanticValue() {
        val callbacks=Callbacks();val parent=LinearLayout(context)
        val renderer=DefaultRichTemplateRenderer(ArtemisFeatureConfig(),ArtemisThemeConfig(),callbacks)
        val message=ChatMessage("a", "a", MessageRole.ASSISTANT, "")
        val holder=ChatMessageAdapter.ChatMessageViewHolder(LinearLayout(context))
        renderer.renderRich(context,holder,message,parent,JsonParser.parseString("""{"carousel":{"cards":[{"title":"Item","buttons":[{"id":"buy","label":"Buy","value":"sku-42"}]}]}}""").asJsonObject)
        fun findButton(view: android.view.View): Button? = if(view is Button) view else if(view is android.view.ViewGroup) (0 until view.childCount).firstNotNullOfOrNull{findButton(view.getChildAt(it))} else null
        findButton(parent)!!.performClick();assertEquals("sku-42",callbacks.value)
    }
    @Test fun fileCardKeepsFilenameVisibleAndDoesNotExpandToScreenHeight() {
        val parent=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        val renderer=DefaultRichTemplateRenderer(ArtemisFeatureConfig(),ArtemisThemeConfig(),Callbacks())
        val message=ChatMessage("file", "file", MessageRole.ASSISTANT, "")
        renderer.renderRich(context,ChatMessageAdapter.ChatMessageViewHolder(LinearLayout(context)),message,parent,
            JsonParser.parseString("""{"file":{"url":"https://example.com/ticket.pdf","filename":"TravelDesk-dev-ticket.pdf","size_bytes":245760}}""").asJsonObject)
        val width=(300*context.resources.displayMetrics.density).toInt()
        parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
        val card=parent.getChildAt(0) as LinearLayout
        val row=card.getChildAt(0) as LinearLayout
        assertTrue("Filename must have width next to Open", row.getChildAt(0).measuredWidth > width/3)
        assertTrue("File card must remain compact", parent.measuredHeight < 180*context.resources.displayMetrics.density)
        renderer.clearCaches()
    }
    @Test fun legacyYamlAndHostedBootstrapConfigurationLoad() {
        val config=ArtemisConfigLoader.fromMap(mapOf("connection" to mapOf("endpoint" to "https://runtime.example.com", "project_id" to "p", "api_key" to "pk"), "channel" to mapOf("channel_name" to "mobile")))
        assertEquals("pk",config.connection.apiKey);assertEquals(5,config.reconnection.maxAttempts);assertEquals(0,config.session.idleDisconnectTimeoutMs)
        val hosted=ArtemisConfigLoader.fromMap(mapOf("connection" to mapOf("endpoint" to "https://runtime.example.com", "project_id" to "p", "bootstrap_token" to "bt")))
        assertEquals("bt",hosted.connection.bootstrapToken)
    }
    @Test fun optInSessionIdentitySurvivesHandleRecreationAndIsScoped() {
        fun persistent(channel:String)=ArtemisUI.initialize(context,ArtemisUIConfig(ArtemisConnectionConfig("https://runtime.example.com","project","pk",channelName=channel,clientSessionIdentifier=true)))
        val first=persistent("mobile");val id=first.clientSessionIdentifier;first.close()
        val second=persistent("mobile");assertEquals(id,second.clientSessionIdentifier)
        val other=persistent("other");assertNotEquals(id,other.clientSessionIdentifier)
        second.endSession();assertTrue(second.requestsNewSession);second.close()
        val reopened=persistent("mobile");assertTrue(reopened.requestsNewSession);reopened.confirmSession();assertFalse(reopened.requestsNewSession)
        reopened.close();other.close()
    }
    private class Callbacks:ChatMessageAdapter.Callbacks {
        var actions=0;var value:String?=null
        override fun onAction(message:ChatMessage,actionIdentifier:String,actionValue:String?,formData:Map<String,String>?,renderIdentifier:String?) { actions++;value=actionValue }
        override fun onFeedback(message:ChatMessage,feedbackType:String,rating:Int,feedbackText:String?) {}
        override fun isLocked(message:ChatMessage,actionIdentifier:String)=false
    }
}
