package com.artemis.uisdk.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import artemis.socket.UiSocketFixture
import com.artemis.uisdk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentFlowTest {
    private lateinit var handle: ArtemisUiHandle
    private lateinit var vm: ArtemisChatViewModel
    private val wire = UiSocketFixture()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var file: File
    private lateinit var provider: Provider
    private val uri = Uri.parse("content://sdk.attachment.test/file")
    private fun start(features: ArtemisFeatureConfig = ArtemisFeatureConfig(attachments = true, contactCenter = true)) {
        file = File.createTempFile("attachment", ".pdf").apply { writeBytes(ByteArray(100000)) }
        provider = Provider(file).also { it.attachInfo(context, ProviderInfo().apply { authority = "sdk.attachment.test" }) }
        ShadowContentResolver.registerProviderInternal("sdk.attachment.test", provider)
        handle = ArtemisUI.initialize(context, ArtemisUIConfig(ArtemisConnectionConfig("https://runtime.example.com", "project", "pk", channelName = "mobile"), features = features))
        vm = ArtemisChatViewModel(handle, wire::create); handle.chatViewModel = vm
        vm.start(); await { wire.socketListener != null }
        synchronized(wire.client) { }
        wire.receive("""{"type":"session_start","sessionId":"runtime-session"}""")
        await { vm.state.value.connectionStatus == ConnectionStatus.CONNECTED }
        state(vm.state.value.copy(isOffline = false))
    }
    private fun state(state: ChatUiState) { vm.javaClass.getDeclaredMethod("setState", ChatUiState::class.java).apply { isAccessible = true }.invoke(vm, state) }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        ShadowLooper.idleMainLooper(); assertTrue("Timed out waiting for UI fixture", condition())
    }
    @After fun close() {
        if (::handle.isInitialized) handle.close()
        ShadowLooper.idleMainLooper()
        if (::file.isInitialized) file.delete()
    }
    @Test fun uploadsOffMainThreadAndSendsAttachmentOnlyWithDurableMetadata() {
        start(); vm.uploadAttachment(uri); await { vm.state.value.pendingAttachment != null }
        assertFalse(provider.queriedOnMain); assertFalse(provider.openedOnMain)
        assertEquals(100, vm.state.value.attachmentUploadProgress)
        assertTrue(vm.sendComposedMessage("")); val frame = wire.lastFrame()
        assertEquals("uploaded-file", frame.array("attachmentIds")!!.single().asString)
        assertEquals("invoice.pdf", frame.obj("metadata")!!.array("attachmentFilenames")!!.single().asString)
        assertEquals("uploaded-file", vm.state.value.messages.last().attachments.single().id)
        assertNull(vm.state.value.pendingAttachment)
    }
    @Test fun failedUploadCanRetryAndTextDraftSurvivesMinimizeReopen() {
        start(); vm.updateComposerDraft("draft text"); wire.uploadStatus = 503
        vm.uploadAttachment(uri); await { vm.state.value.attachmentUploadFailed }
        assertEquals("draft text", vm.composerDraft); assertFalse(vm.sendComposedMessage("draft text"))
        handle.createChatFragment(); handle.minimizeChat(); handle.createChatFragment()
        assertSame(vm, handle.chatViewModel); assertEquals("draft text", vm.composerDraft)
        wire.uploadStatus = 200; vm.retryAttachment(); await { vm.state.value.pendingAttachment != null }
        assertTrue(vm.sendComposedMessage(vm.composerDraft)); assertEquals("", vm.composerDraft)
    }
    @Test fun removeFailedAttachmentRestoresTextOnlySending() {
        start(); wire.uploadStatus = 415; vm.uploadAttachment(uri); await { vm.state.value.attachmentUploadFailed }
        vm.removePendingAttachment(); assertTrue(vm.sendComposedMessage("text only")); assertFalse(wire.lastFrame().has("attachmentIds"))
    }
    @Test fun disabledFeaturesDoNotQueryFilesAndLegacyChatStillSends() {
        start(ArtemisFeatureConfig()); vm.uploadAttachment(uri); ShadowLooper.idleMainLooper()
        assertFalse(provider.queried); assertFalse(vm.state.value.attachmentUploading)
        wire.receive("""{"type":"agent_transfer_event","sessionId":"transfer","event":{"type":"agent:connected"}}""")
        ShadowLooper.idleMainLooper(); assertEquals(AgentTransferStatus.NONE, vm.state.value.agentTransferStatus)
        assertTrue(vm.sendComposedMessage("legacy")); assertEquals("legacy", wire.lastFrame().string("text"))
    }
    @Test fun newRuntimeSessionClearsPreparedAttachmentAndPreservesUnsentText() {
        start(); vm.updateComposerDraft("draft"); vm.uploadAttachment(uri); await { vm.state.value.pendingAttachment != null }
        wire.receive("""{"type":"session_start","sessionId":"new-session"}""")
        await { vm.state.value.sessionId == "new-session" }; assertNull(vm.state.value.pendingAttachment); assertEquals("draft", vm.composerDraft)
    }
    @Test fun olderRuntimeWithoutUploadRouteFallsBackWithoutDisconnectingChat() {
        start(); wire.uploadStatus = 404; vm.uploadAttachment(uri); await { vm.state.value.attachmentUploadFailed }
        assertFalse(vm.attachmentsEnabled); assertEquals(ConnectionStatus.CONNECTED, vm.state.value.connectionStatus)
        vm.removePendingAttachment(); assertTrue(vm.sendComposedMessage("legacy Runtime still works"))
    }
    @Test fun mimeValidationRejectsBeforeUploadingAndRetainsDraft() {
        start(); provider.mime = "application/x-executable"; vm.updateComposerDraft("keep me")
        vm.uploadAttachment(uri); await { vm.state.value.attachmentUploadFailed }
        assertFalse(wire.requests.any { it.endsWith("/attachments") }); assertEquals("keep me", vm.composerDraft)
    }
    @Test fun fiveFilesSendTogetherAndKeepOrderedMetadata() {
        start(); wire.uniqueUploadIds = true
        vm.updateComposerDraft("five-file draft")
        vm.uploadAttachments((1..5).map { Uri.parse("content://sdk.attachment.test/file-$it") })
        await { vm.state.value.attachmentDrafts.size == 5 && vm.state.value.attachmentDrafts.all { it.status == AttachmentDraftStatus.READY } }
        val drafts = vm.state.value.attachmentDrafts
        assertEquals(5, drafts.map { it.attachment.id }.toSet().size)
        assertEquals(5, wire.requests.count { it.endsWith("/attachments") })
        assertTrue(vm.sendComposedMessage(vm.composerDraft))
        val frame = wire.lastFrame()
        assertEquals("five-file draft", frame.string("text"))
        assertEquals(drafts.map { it.attachment.id }, frame.array("attachmentIds")!!.map { it.asString })
        assertEquals(5, frame.obj("metadata")!!.array("attachmentFilenames")!!.size())
        assertEquals(5, vm.state.value.messages.last().attachments.size)
        assertTrue(vm.state.value.attachmentDrafts.isEmpty()); assertEquals("", vm.composerDraft)
    }
    @Test fun sixFileSelectionDoesNotQueryOrUploadAndSocketAlsoRejectsSix() {
        start(); vm.updateComposerDraft("keep this draft")
        vm.uploadAttachments((1..6).map { Uri.parse("content://sdk.attachment.test/file-$it") })
        ShadowLooper.idleMainLooper()
        assertTrue(vm.state.value.attachmentDrafts.isEmpty()); assertFalse(provider.queried)
        assertEquals(0, wire.requests.count { it.endsWith("/attachments") })
        assertEquals("keep this draft", vm.composerDraft)
        assertTrue(vm.state.value.errorMessage!!.contains("5"))
        assertThrows(IllegalArgumentException::class.java) {
            wire.client.sendMessage("", "six-files", null, null, (1..6).map { "id-$it" })
        }
        assertTrue(vm.sendComposedMessage("text only still works"))
    }
    @Test fun overflowPreservesReadyFilesAndRemovalFreesOnlyOneSlot() {
        start(); wire.uniqueUploadIds = true
        vm.uploadAttachments((1..5).map { Uri.parse("content://sdk.attachment.test/file-$it") })
        await { vm.state.value.attachmentDrafts.size == 5 && vm.state.value.attachmentDrafts.all { it.status == AttachmentDraftStatus.READY } }
        vm.removePendingAttachment(vm.state.value.attachmentDrafts.first().localId)
        val existing = vm.state.value.attachmentDrafts
        assertEquals(4, existing.size)
        vm.uploadAttachments((6..7).map { Uri.parse("content://sdk.attachment.test/file-$it") })
        assertEquals(existing, vm.state.value.attachmentDrafts)
        assertEquals(5, wire.requests.count { it.endsWith("/attachments") })
        vm.uploadAttachment(Uri.parse("content://sdk.attachment.test/file-6"))
        await { vm.state.value.attachmentDrafts.size == 5 && vm.state.value.attachmentDrafts.all { it.status == AttachmentDraftStatus.READY } }
        assertEquals(existing, vm.state.value.attachmentDrafts.take(4))
        handle.createChatFragment(); handle.minimizeChat(); handle.createChatFragment()
        assertEquals(5, vm.state.value.attachmentDrafts.size)
        assertTrue(vm.sendComposedMessage("")); assertEquals(5, wire.lastFrame().array("attachmentIds")!!.size())
    }
    @Test fun surveyPendingAckFailureRetryAndSuccessUseSeparateLane() {
        start(); wire.receive("""{"type":"agent_transfer_event","sessionId":"transfer","event":{"type":"agent:disconnected","data":{"survey":{"triggerSurvey":true,"surveyType":"csat","artemisSessionId":"transfer","comment_visibility":"never"}}}}""")
        await { vm.state.value.messages.any { it.transferSurvey != null } }
        val id = vm.state.value.messages.single { it.transferSurvey != null }.messageId
        vm.submitTransferSurvey(id, 5, "must not send hidden comment")
        assertEquals("agent_transfer_survey.submit", wire.lastFrame().string("type")); assertFalse(wire.lastFrame().has("comments"))
        assertEquals(SurveySubmissionStatus.PENDING, vm.state.value.messages.last().transferSurvey!!.status)
        val count = wire.frames.size; vm.submitTransferSurvey(id, 5, null); assertEquals(count, wire.frames.size)
        wire.receive("""{"type":"agent_transfer_survey.ack","artemisSessionId":"transfer","success":false,"error":{"code":"PROVIDER_UNAVAILABLE","message":"private diagnostics"}}""")
        await { vm.state.value.messages.last().transferSurvey!!.status == SurveySubmissionStatus.FAILED }
        assertFalse(vm.state.value.toString().contains("private diagnostics"))
        vm.submitTransferSurvey(id, 5, null)
        wire.receive("""{"type":"agent_transfer_survey.ack","artemisSessionId":"transfer","success":true}""")
        await { vm.state.value.messages.last().transferSurvey!!.status == SurveySubmissionStatus.SUBMITTED }
        val completed = wire.frames.size; vm.submitTransferSurvey(id, 4, null); assertEquals(completed, wire.frames.size)
        assertTrue(vm.sendComposedMessage("back to assistant"))
    }
    class Provider(private val file: File) : ContentProvider() {
        @Volatile var queried = false; @Volatile var queriedOnMain = false; @Volatile var openedOnMain = false
        @Volatile var mime = "application/pdf"
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queried = true; queriedOnMain = Looper.myLooper() == Looper.getMainLooper()
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf("invoice.pdf", file.length())) }
        }
        override fun getType(uri: Uri) = mime
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            openedOnMain = Looper.myLooper() == Looper.getMainLooper()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
