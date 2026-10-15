package com.artemis.uisdk.chat

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.artemis.uisdk.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScrollingRegressionTest {
    private val context get() = android.view.ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_ArtemisUI)
    private fun form(id: String) = ChatMessage(id, id, MessageRole.ASSISTANT, "", richContent = JsonParser.parseString(
        """{"form":{"title":"Details","fields":[{"id":"name","label":"Name","type":"input"}],"submit_label":"Send"}}""").asJsonObject)
    private fun adapter() = ChatMessageAdapter(ArtemisFeatureConfig(), ArtemisThemeConfig(), object: ChatMessageAdapter.Callbacks {
        override fun onAction(message: ChatMessage, actionIdentifier: String, actionValue: String?, formData: Map<String,String>?, renderIdentifier: String?) { }
        override fun onFeedback(message: ChatMessage, feedbackType: String, rating: Int, feedbackText: String?) { }
        override fun isLocked(message: ChatMessage, actionIdentifier: String) = false
    })
    private fun submit(adapter: ChatMessageAdapter, messages: List<ChatMessage>) {
        var committed = false
        adapter.submitList(messages) { committed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!committed && System.nanoTime() < deadline) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue("List diff did not commit", committed)
    }
    private fun findInput(view: View): EditText? = if (view is EditText) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findInput(view.getChildAt(it)) } else null
    @Test fun repeatedBindOfUnchangedMessageKeepsTemplateTree() {
        val adapter = adapter(); val rv = RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) }; rv.adapter = adapter
        submit(adapter, listOf(form("a")))
        val holder = adapter.onCreateViewHolder(rv, 0); adapter.onBindViewHolder(holder, 0)
        val template = holder.templateContainer.getChildAt(0)
        val input = findInput(template)!!; input.setText("Alice")
        adapter.onBindViewHolder(holder, 0)
        assertSame(template, holder.templateContainer.getChildAt(0)); assertEquals("Alice", input.text.toString())
        rv.adapter = null
    }
    @Test fun recycledRowRestoresRecentTemplateAndFormInputWithoutReinflation() {
        val adapter = adapter(); val rv = RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) }; rv.adapter = adapter
        submit(adapter, listOf(form("a"), form("b")))
        val holder = adapter.onCreateViewHolder(rv, 0); adapter.onBindViewHolder(holder, 0)
        val template = holder.templateContainer.getChildAt(0); findInput(template)!!.setText("Alice")
        adapter.onViewRecycled(holder); adapter.onBindViewHolder(holder, 1)
        val restored = adapter.onCreateViewHolder(rv, 0); adapter.onBindViewHolder(restored, 0)
        assertSame(template, restored.templateContainer.getChildAt(0)); assertEquals("Alice", findInput(template)!!.text.toString())
        rv.adapter = null
    }
    @Test fun streamingTextChangesKeepUnrelatedTemplates() {
        val adapter=adapter();val rv=RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) };rv.adapter=adapter
        val original=form("a").copy(text="First",messageStatus=MessageStatus.STREAMING)
        submit(adapter,listOf(original));val holder=adapter.onCreateViewHolder(rv,0);adapter.onBindViewHolder(holder,0)
        val template=holder.templateContainer.getChildAt(0)
        submit(adapter,listOf(original.copy(text="First second")));adapter.onBindViewHolder(holder,0)
        assertSame(template,holder.templateContainer.getChildAt(0));assertEquals("First second",holder.textView.text.toString().trim())
        rv.adapter=null
    }
    @Test fun actionStateChangeInvalidatesCachedControls() {
        val adapter=adapter();val rv=RecyclerView(context).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) };rv.adapter=adapter
        val message=form("a");submit(adapter,listOf(message));val holder=adapter.onCreateViewHolder(rv,0);adapter.onBindViewHolder(holder,0)
        val old=holder.templateContainer.getChildAt(0);adapter.onViewRecycled(holder)
        submit(adapter,listOf(message.copy(presentationVersion=1)));adapter.onBindViewHolder(holder,0)
        assertNotSame(old,holder.templateContainer.getChildAt(0));rv.adapter=null
    }
    @Test fun stableIdsFollowMessagesAcrossReordering() {
        val adapter=adapter();val a=form("a");val b=form("b");submit(adapter,listOf(a,b));val idA=adapter.getItemId(0);val idB=adapter.getItemId(1)
        submit(adapter,listOf(b,a));assertEquals(idA,adapter.getItemId(1));assertEquals(idB,adapter.getItemId(0))
    }
    @Test fun templateCacheIsBoundedAndDoesNotRetainMedia() {
        val cache=TemplateViewCache()
        for(i in 0..20) cache.retain(form("$i"),listOf(View(context)))
        assertTrue(cache.retainedNodeCount()<=12)
        val media=form("media").copy(richContent=JsonParser.parseString("""{"audio":{"url":"https://example.com/audio"}}""").asJsonObject)
        cache.retain(media,listOf(View(context)));assertNull(cache.take(media));cache.clear();assertEquals(0,cache.retainedNodeCount())
    }
    @Test fun userScrollDisablesFollowUntilReturningToBottom() {
        val follow=ChatScrollFollow();assertTrue(follow.shouldFollow)
        follow.scrollStateChanged(false,true);assertFalse(follow.shouldFollow)
        follow.scrollStateChanged(true,false);assertFalse(follow.shouldFollow)
        follow.scrollStateChanged(false,false);follow.scrollStateChanged(true,true);assertTrue(follow.shouldFollow)
    }
    @Test fun sendingExplicitlyResumesFollowWithoutInterruptingCurrentGesture() {
        val follow=ChatScrollFollow();follow.scrollStateChanged(false,false);follow.userSentMessage();assertFalse(follow.shouldFollow)
        follow.scrollStateChanged(true,true);assertTrue(follow.shouldFollow)
    }
    @Test fun identicalMarkdownReusesParsedSpans() {
        val renderer=DefaultRichTemplateRenderer(ArtemisFeatureConfig(),ArtemisThemeConfig(),object:ChatMessageAdapter.Callbacks {
            override fun onAction(message:ChatMessage,actionIdentifier:String,actionValue:String?,formData:Map<String,String>?,renderIdentifier:String?){}
            override fun onFeedback(message:ChatMessage,feedbackType:String,rating:Int,feedbackText:String?){}
            override fun isLocked(message:ChatMessage,actionIdentifier:String)=false
        })
        val first=renderer.parseMarkdown(context,"**Hello**");assertSame(first,renderer.parseMarkdown(context,"**Hello**"));renderer.clearCaches()
        assertNotSame(first,renderer.parseMarkdown(context,"**Hello**"))
    }
    @Test fun imageLoadsAreDeduplicatedAndCached() {
        val tasks=mutableListOf<Runnable>();var fetched=0;var delivered=0
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val loader=TemplateImageLoader(fetch={fetched++;bitmap},worker=Executor{tasks.add(it)},deliver={it.run()})
        repeat(2){loader.load("https://example.com/image"){assertSame(bitmap,it);delivered++}}
        assertEquals(1,tasks.size);tasks.removeAt(0).run();assertEquals(1,fetched);assertEquals(2,delivered)
        loader.load("https://example.com/image"){delivered++};assertEquals(3,delivered);assertTrue(tasks.isEmpty())
        loader.clear()
    }
    @Test fun clearingImageLoaderDropsLateCallbacks() {
        val tasks=mutableListOf<Runnable>();var delivered=0
        val loader=TemplateImageLoader(fetch={Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)},worker=Executor{tasks.add(it)},deliver={it.run()})
        loader.load("https://example.com/image"){delivered++};loader.clear();tasks.single().run();assertEquals(0,delivered)
    }
}
