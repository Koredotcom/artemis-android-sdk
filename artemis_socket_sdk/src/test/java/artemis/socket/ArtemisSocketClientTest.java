package artemis.socket;

import com.google.gson.*;
import okhttp3.*;
import okio.ByteString;
import org.junit.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Runtime contract fixtures; every HTTP request is intercepted and no test reaches the network. */
public class ArtemisSocketClientTest {
    private Harness h;
    @After public void cleanup() { if (h != null) h.client.shutdown(); }
    private ArtemisSocketConfiguration.Builder config() { return ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com")
        .projectId("project").apiKey("pk").channelName("mobile"); }
    private Harness start(ArtemisSocketConfiguration.Builder builder) throws Exception {
        h = new Harness(builder.build()); h.client.connect(); h.awaitSockets(1); return h;
    }
    @Test public void legacyHandshakeAndTextWireContract() throws Exception {
        start(config());
        assertFalse(h.client.isConnected());
        assertEquals("/api/v1/sdk/init", h.requests.get(0).url().encodedPath());
        assertEquals("pk", h.requests.get(0).header("X-Public-Key"));
        assertEquals("{\"channelName\":\"mobile\"}", h.bodies.get(0));
        assertEquals("/api/v1/sdk/ws-ticket", h.requests.get(1).url().encodedPath());
        assertEquals("token", h.requests.get(1).header("X-SDK-Token"));
        assertEquals("/ws/sdk", h.sockets.get(0).request.url().encodedPath());
        assertNull(h.sockets.get(0).request.url().query());
        assertEquals("sdk-ticket, ticket", h.sockets.get(0).request.header("Sec-WebSocket-Protocol"));
        h.ready(); h.client.sendMessage("hello");
        JsonObject frame = h.lastFrame();
        assertEquals("chat_message", frame.get("type").getAsString());
        assertEquals("hello", frame.get("text").getAsString());
        assertEquals("session", frame.get("sessionId").getAsString());
        assertFalse(frame.get("messageId").getAsString().isEmpty());
    }
    @Test public void sharedMessageIdMetadataAndPrivacyCapability() throws Exception {
        start(config()); h.message("{\"type\":\"session_start\",\"sessionId\":\"session\",\"capabilities\":{\"liveUserInputRedaction\":true}}");
        assertTrue(h.client.isLiveUserInputRedaction());
        JsonObject metadata = new JsonObject(); metadata.addProperty("source", "app");
        JsonObject context = new JsonObject(); context.addProperty("locale", "en-IN");
        h.client.sendMessage("private", "shared-id", metadata, context);
        assertEquals("shared-id", h.lastFrame().get("messageId").getAsString());
        assertEquals("en-IN", h.lastFrame().getAsJsonObject("interactionContext").get("locale").getAsString());
    }
    @Test public void actionsRemainStructuredAndFeedbackIsAckDriven() throws Exception {
        start(config()); h.ready(); h.client.submitAction("select", "value", Collections.singletonMap("field", "x"), "render");
        assertEquals("action_submit", h.lastFrame().get("type").getAsString());
        assertEquals("x", h.lastFrame().getAsJsonObject("formData").get("field").getAsString());
        AtomicInteger success = new AtomicInteger(); AtomicInteger failure = new AtomicInteger();
        h.client.submitFeedback("assistant", "thumbs", 1, null, "render", callback(success, failure));
        assertEquals(0, success.get());
        h.message("{\"type\":\"feedback.ack\",\"messageId\":\"assistant\",\"actionRenderId\":\"render\",\"success\":true,\"feedbackId\":\"f\"}");
        assertEquals(1, success.get()); assertEquals(0, failure.get());
    }
    @Test public void feedbackFailureCanRetry() throws Exception {
        start(config()); h.ready(); AtomicInteger failures = new AtomicInteger();
        h.client.submitFeedback("a", "star", 3, null, null, callback(new AtomicInteger(), failures));
        h.message("{\"type\":\"feedback.ack\",\"messageId\":\"a\",\"success\":false,\"error\":{\"code\":\"RETRY\",\"message\":\"Retry\"}}");
        assertEquals(1, failures.get());
        h.client.submitFeedback("a", "star", 3, null, null, callback(new AtomicInteger(), failures));
        assertEquals("feedback.submit", h.lastFrame().get("type").getAsString());
    }
    @Test public void socketRejectedSendDoesNotReportSuccess() throws Exception {
        start(config()); h.ready(); h.sockets.get(0).accept = false;
        try { h.client.sendMessage("hello"); fail("Expected rejection"); } catch (IllegalStateException expected) { }
    }
    @Test public void connectIsIdempotentDuringBootstrap() throws Exception {
        start(config()); h.client.connect(); h.client.connect(); assertEquals(1, h.sockets.size());
        h.ready(); h.client.connect(); assertEquals(1, h.sockets.size());
    }
    @Test public void disconnectReconnectResumesTokenWithoutReplayingInit() throws Exception {
        start(config()); h.ready(); h.client.disconnect(); h.client.connect(); h.awaitSockets(2); h.ready();
        assertEquals(1, h.count("/api/v1/sdk/init")); assertEquals(2, h.count("/api/v1/sdk/ws-ticket"));
        assertTrue(h.client.isConnected());
    }
    @Test public void hostedBootstrapNeverUsesPublicKeyOrUrlCredentials() throws Exception {
        start(ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com").projectId("project").bootstrapTokenProvider(() -> "hosted"));
        assertNull(h.requests.get(0).header("X-Public-Key"));
        assertEquals("hosted", JsonParser.parseString(h.bodies.get(0)).getAsJsonObject().get("bootstrapToken").getAsString());
        assertNull(h.sockets.get(0).request.url().query());
    }
    @Test public void lateOldSocketCallbacksCannotDisconnectReplacement() throws Exception {
        start(config()); h.ready(); FakeSocket old = h.sockets.get(0); h.client.disconnect(); h.client.connect(); h.awaitSockets(2); h.ready();
        old.listener.onClosed(old, 1006, "late"); old.listener.onMessage(old, "{\"type\":\"session_start\",\"sessionId\":\"stale\"}");
        assertTrue(h.client.isConnected()); assertEquals("session", h.client.getSessionId());
    }
    @Test public void displacementDoesNotReconnect() throws Exception {
        start(config().reconnection(true, 5, 1, 2)); h.ready(); FakeSocket socket = h.sockets.get(0);
        socket.listener.onClosed(socket, 4000, "displaced"); Thread.sleep(50);
        assertEquals(1, h.sockets.size()); assertFalse(h.client.isConnected());
    }
    @Test public void transientReconnectKeepsLegacyAttemptCap() throws Exception {
        start(config().reconnection(true, 1, 1, 2)); h.ready(); h.httpStatus = 503;
        FakeSocket socket = h.sockets.get(0); socket.listener.onClosed(socket, 1006, "drop");
        h.awaitRequests(3); Thread.sleep(50); assertEquals(3, h.requests.size());
    }
    @Test public void optionalWebTransientPolicyCanRecoverBeyondCap() throws Exception {
        start(config().reconnection(true, 0, 1, 2).unlimitedTransientReconnect(true)); h.ready();
        FakeSocket socket = h.sockets.get(0); socket.listener.onClosed(socket, 1006, "drop");
        h.awaitSockets(2); h.ready(); assertTrue(h.client.isConnected());
    }
    @Test public void authenticationFailureRemainsCapped() throws Exception {
        start(config().reconnection(true, 1, 1, 2).unlimitedTransientReconnect(true)); h.ready(); h.httpStatus = 401;
        FakeSocket socket = h.sockets.get(0); socket.listener.onClosed(socket, 4001, "auth");
        h.awaitRequests(3); Thread.sleep(50); assertEquals(3, h.requests.size());
    }
    @Test public void refreshUsesSdkTokenAndPreservesLiveSocket() throws Exception {
        h = new Harness(config().build()); h.expirySeconds = 1; h.client.connect(); h.awaitSockets(1); h.ready();
        h.awaitPath("/api/v1/sdk/refresh");
        Request request = h.requests.stream().filter(r -> r.url().encodedPath().equals("/api/v1/sdk/refresh")).findFirst().get();
        assertEquals("token", request.header("X-SDK-Token")); assertTrue(h.client.isConnected());
    }
    @Test public void paginatedHistoryUsesCurrentSessionAndToken() throws Exception {
        start(config()); h.ready(); h.client.loadHistory();
        assertTrue(h.historyReady.await(2, TimeUnit.SECONDS));
        assertEquals(2, JsonParser.parseString(h.history).getAsJsonArray().size());
        Request request = h.requests.stream().filter(r -> r.url().encodedPath().endsWith("/messages")).findFirst().get();
        assertEquals("/api/projects/project/sessions/session/messages", request.url().encodedPath());
        assertEquals("token", request.header("X-SDK-Token")); assertEquals("asc", request.url().queryParameter("direction"));
    }
    @Test public void unsupportedHistoryDoesNotBreakConnection() throws Exception {
        start(config()); h.ready(); h.httpStatus = 404; h.client.loadHistory(); h.awaitRequests(3);
        assertTrue(h.client.isConnected()); h.client.sendMessage("still works");
    }
    @Test public void endSessionSendsDedicatedFrameAndRequestsNewSession() throws Exception {
        start(config()); h.ready(); h.client.endSession(); assertEquals("end_session", h.lastFrame().get("type").getAsString());
        FakeSocket old = h.sockets.get(0); old.listener.onClosed(old, 1000, "ended");
        h.client.connect(); h.awaitSockets(2);
        assertEquals("new", JsonParser.parseString(h.bodies.get(2)).getAsJsonObject().get("sessionIntent").getAsString());
    }
    @Test public void malformedSessionStartCannotEnableSending() throws Exception {
        start(config()); h.message("{\"type\":\"session_start\"}"); h.message("not JSON"); assertFalse(h.client.isConnected());
    }
    @Test public void staleIdleSendRefreshesConnectionAndKeepsRetryExplicit() throws Exception {
        start(config().staleSessionRefresh(1)); h.ready(); Thread.sleep(10);
        try { h.client.sendMessage("hello"); fail("Expected refresh boundary"); } catch (IllegalStateException expected) { }
        h.awaitSockets(2); h.ready(); assertEquals(1, h.count("/api/v1/sdk/init"));
    }
    @Test public void optionalIdleDisconnectCanResumeWithSameToken() throws Exception {
        start(config().idleDisconnect(30, false)); h.ready(); h.await(() -> !h.client.isConnected());
        h.client.connect(); h.awaitSockets(2); h.ready(); assertEquals(1, h.count("/api/v1/sdk/init"));
    }
    @Test public void refreshScopeChangeIsRejected() throws Exception {
        h = new Harness(config().reconnection(false, 5, 1, 2).build()); h.expirySeconds = 1; h.changeScopeOnRefresh = true;
        h.client.connect(); h.awaitSockets(1); h.ready(); h.awaitPath("/api/v1/sdk/refresh"); h.await(() -> !h.client.isConnected());
        assertEquals("channel", h.client.getSessionScope().get("channelId").getAsString());
    }
    @Test(timeout=5000) public void disconnectDuringBootstrapCannotOpenLateSocket() throws Exception {
        h = new Harness(config().build()); h.blockInit = true; h.client.connect();
        assertTrue(h.initEntered.await(2, TimeUnit.SECONDS)); h.client.disconnect(); h.releaseInit.countDown();
        h.awaitRequests(2); Thread.sleep(30); assertTrue(h.sockets.isEmpty()); assertFalse(h.client.isConnected());
    }
    @Test public void deferredCallbackFromOldGenerationIsIgnored() throws Exception {
        h = new Harness(config().build(), true); h.client.connect(); h.awaitSockets(1); h.ready();
        h.client.disconnect(); h.client.connect(); h.awaitSockets(2); h.ready();
        h.flushCallbacks(); assertEquals(1,h.connectedCallbacks.get());assertEquals(0,h.disconnectedCallbacks.get());
    }
    @Test public void newSessionIntentCanBeRequestedAtInitialization() throws Exception {
        start(config().newSessionOnConnect(true));
        assertEquals("new",JsonParser.parseString(h.bodies.get(0)).getAsJsonObject().get("sessionIntent").getAsString());
    }
    @Test public void actionDisplayEchoUsesSameIdAsRuntimeRender() throws Exception {
        start(config());h.ready();h.client.submitAction("reply","payload",null,null,"Yes","action-user-id");
        assertEquals("payload",h.lastFrame().get("value").getAsString());assertEquals("Yes",h.lastFrame().get("displayText").getAsString());
        assertEquals("action-user-id",h.lastFrame().get("messageId").getAsString());
    }
    @Test public void shutdownPreventsFurtherConnections() throws Exception {
        start(config());h.client.shutdown();try {h.client.connect();fail("Expected closed client");}catch(IllegalStateException expected){}
    }
    @Test(timeout=5000) public void shutdownClosesPooledBootstrapSocketOffCallingThread() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Thread> closedBy = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        Thread callingThread = Thread.currentThread();
        javax.net.SocketFactory socketFactory = new javax.net.SocketFactory() {
            @Override public java.net.Socket createSocket() {
                return new java.net.Socket() {
                    @Override public synchronized void close() throws IOException {
                        closedBy.compareAndSet(null, Thread.currentThread());
                        try { super.close(); } finally { closed.countDown(); }
                    }
                };
            }
            @Override public java.net.Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
            @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress address, int localPort) { throw new UnsupportedOperationException(); }
            @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) { throw new UnsupportedOperationException(); }
            @Override public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress address, int localPort) { throw new UnsupportedOperationException(); }
        };
        ExecutorService serverWorker = Executors.newSingleThreadExecutor();
        OkHttpClient http = new OkHttpClient.Builder().socketFactory(socketFactory)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)).callTimeout(2, TimeUnit.SECONDS).build();
        ArtemisSocketClient client = new ArtemisSocketClient(config().build(), new HarnessListener(), http, null,
            Runnable::run, Executors.newSingleThreadScheduledExecutor());
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Future<?> served = serverWorker.submit(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n{}".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    input.read(); // Remain open until SDK cleanup evicts the pooled connection.
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            try (Response response = http.newCall(new Request.Builder().url("http://127.0.0.1:" + server.getLocalPort() + "/api/v1/sdk/init").build()).execute()) {
                assertEquals("{}", response.body().string());
            }
            assertEquals(1, http.connectionPool().idleConnectionCount());
            client.shutdown();
            assertTrue("SDK cleanup did not close the pooled connection", closed.await(2, TimeUnit.SECONDS));
            assertNotSame("Socket cleanup must never run on the calling/UI thread", callingThread, closedBy.get());
            served.get(2, TimeUnit.SECONDS);
            assertFalse(client.isConnected());
        } finally {
            client.shutdown(); serverWorker.shutdownNow();
        }
    }
    private static class HarnessListener implements ArtemisSocketListener {
        public void onLog(String message) { } public void onConnecting() { } public void onConnected(String id) { }
        public void onDisconnected(String reason) { } public void onMessage(String payload) { } public void onError(Throwable error) { }
    }
    @Test public void attachmentSendIsAdditiveAndTextOnlyWireStaysUnchanged() throws Exception {
        start(config()); h.ready(); h.client.sendMessage("hello", "legacy", null, null);
        assertFalse(h.lastFrame().has("attachmentIds"));
        h.client.sendMessage("", "file-user", null, null, Collections.singletonList("file-id"));
        assertEquals("file-id", h.lastFrame().getAsJsonArray("attachmentIds").get(0).getAsString());
        assertEquals("file-user", h.lastFrame().get("messageId").getAsString());
        try { h.client.sendMessage("", "empty", null, null); fail("Empty legacy send must fail"); } catch (IllegalArgumentException expected) { }
    }
    @Test public void uploadStreamsMultipartWithSdkAuthAndProgressOffCallerThread() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult(); Thread caller = Thread.currentThread();
        h.client.uploadAttachment("invoice.pdf", "application/pdf", 100000, 100000,
            () -> { assertNotSame(caller, Thread.currentThread()); return new java.io.ByteArrayInputStream(new byte[100000]); }, result);
        result.await(); assertEquals("attachment", result.id); assertEquals("session", result.session); assertNull(result.code);
        Request upload = h.requests.stream().filter(r -> r.url().encodedPath().endsWith("/attachments")).findFirst().get();
        assertEquals("token", upload.header("X-SDK-Token")); assertEquals("POST", upload.method());
        assertTrue(h.bodies.stream().anyMatch(b -> b.contains("name=\"file\"; filename=\"invoice.pdf\"")));
        assertTrue(result.progress.contains(100000L)); assertTrue(h.client.isConnected());
    }
    @Test public void unknownSizeStreamIsBoundedAndFailureDoesNotDisconnectChat() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult();
        h.client.uploadAttachment("file.txt", "text/plain", -1, 4, () -> new java.io.ByteArrayInputStream(new byte[5]), result);
        result.await(); assertEquals("FILE_TOO_LARGE", result.code); assertNull(result.id);
        h.client.sendMessage("still connected"); assertEquals("chat_message", h.lastFrame().get("type").getAsString());
    }
    @Test public void declaredSizeMismatchCannotSucceed() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult();
        h.client.uploadAttachment("file.txt", "text/plain", 4, 10, () -> new java.io.ByteArrayInputStream(new byte[3]), result);
        result.await(); assertEquals("FILE_CHANGED", result.code);
    }
    @Test public void invalidFilePolicyIsRejectedBeforeNetworkWork() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult(); int before = h.requests.size();
        for (long size : new long[]{0, -2, 21}) try {
            h.client.uploadAttachment("f", "text/plain", size, 20, () -> null, result); fail("Expected invalid size");
        } catch (IllegalArgumentException expected) { }
        try { h.client.uploadAttachment("f", "not a mime", 1, 20, () -> null, result); fail("Expected invalid type"); } catch (IllegalArgumentException expected) { }
        assertEquals(before, h.requests.size());
    }
    @Test public void cancelledUploadCompletesOnceAndKeepsChatConnected() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult(); CountDownLatch reading = new CountDownLatch(1);
        ArtemisAttachmentUpload task = h.client.uploadAttachment("f.txt", "text/plain", -1, 100,
            () -> blockingStream(reading), result);
        assertTrue(reading.await(2, TimeUnit.SECONDS)); task.cancel(); result.await(); task.cancel();
        assertEquals("CANCELLED", result.code); assertTrue(task.isDone()); assertEquals(1, result.completed.get());
        assertTrue(h.client.isConnected()); h.client.sendMessage("after cancel");
    }
    @Test public void tokenRefreshAndTextSendAreNotBlockedByFileRead() throws Exception {
        h = new Harness(config().build()); h.expirySeconds = 1; h.client.connect(); h.awaitSockets(1); h.ready();
        UploadResult result = new UploadResult(); CountDownLatch reading = new CountDownLatch(1);
        ArtemisAttachmentUpload task = h.client.uploadAttachment("f.txt", "text/plain", -1, 100,
            () -> blockingStream(reading), result);
        assertTrue(reading.await(2, TimeUnit.SECONDS)); h.client.sendMessage("while uploading");
        h.awaitPath("/api/v1/sdk/refresh"); assertTrue(h.client.isConnected()); task.cancel(); result.await();
    }
    @Test public void disconnectCancelsOnlyOutstandingAttachmentCallbacksOnce() throws Exception {
        start(config()); h.ready(); UploadResult result = new UploadResult(); CountDownLatch reading = new CountDownLatch(1);
        h.client.uploadAttachment("f.txt", "text/plain", -1, 100, () -> blockingStream(reading), result);
        assertTrue(reading.await(2, TimeUnit.SECONDS)); h.client.disconnect(); result.await();
        assertEquals("DISCONNECTED", result.code); assertEquals(1, result.completed.get());
        h.client.connect(); h.awaitSockets(2); h.ready(); assertTrue(h.client.isConnected());
    }
    @Test public void sessionChangeRejectsDeferredSuccessfulUpload() throws Exception {
        h = new Harness(config().build(), true); h.client.connect(); h.awaitSockets(1); h.ready(); h.flushCallbacks();
        UploadResult result = new UploadResult();
        h.client.uploadAttachment("f.txt", "text/plain", 1, 10, () -> new java.io.ByteArrayInputStream(new byte[1]), result);
        h.await(() -> !h.callbacks.isEmpty());
        h.message("{\"type\":\"session_start\",\"sessionId\":\"replacement\"}"); h.flushCallbacks(); result.await();
        assertEquals("SESSION_CHANGED", result.code); assertNull(result.id);
    }
    @Test public void scopedDownloadUsesFreshAuthenticatedRouteAndSafeUrl() throws Exception {
        start(config()); h.ready(); CountDownLatch done = new CountDownLatch(1); List<String> urls = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("id /?", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { urls.add(url); done.countDown(); }
            public void onFailure(String code, String message) { done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(Collections.singletonList("https://files.example.com/download"), urls);
        Request request = h.requests.stream().filter(r -> r.url().encodedPath().endsWith("/url")).findFirst().get();
        assertTrue(request.url().encodedPath().contains("id%20%2F%3F")); assertEquals("attachment", request.url().queryParameter("disposition"));
        assertEquals("token", request.header("X-SDK-Token"));
    }
    @Test public void runtimeRelativeDownloadUrlResolvesAgainstRuntimeEndpoint() throws Exception {
        start(config()); h.ready(); h.downloadResponse = "{\"data\":{\"url\":\"/files/safe-download\"}}";
        CountDownLatch done = new CountDownLatch(1); List<String> urls = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("a", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { urls.add(url); done.countDown(); }
            public void onFailure(String code, String message) { done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(Collections.singletonList("https://runtime.example.com/files/safe-download"), urls);
    }
    @Test public void serverLocalFileCanUseScopedAuthenticatedDeliveryRoute() throws Exception {
        start(config()); h.ready(); h.downloadResponse = "{\"data\":{\"url\":\"file:///server-private/file.txt\"}}"; h.deliveryStatus = 201;
        CountDownLatch done = new CountDownLatch(1); List<String> result = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("a", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { result.add(url); done.countDown(); }
            public void onFailure(String code, String message) { done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(Collections.singletonList("https://files.example.com/scoped-delivery"), result);
        Request mint = h.requests.stream().filter(r -> r.url().encodedPath().endsWith("/delivery-url")).findFirst().get();
        assertEquals("POST", mint.method()); assertEquals("token", mint.header("X-SDK-Token"));
        assertNull(mint.url().queryParameter("disposition")); assertTrue(h.client.isConnected());
    }
    @Test public void deliveryNotReadyIsRecoverableWithoutOpeningServerFile() throws Exception {
        start(config()); h.ready(); h.downloadResponse = "{\"data\":{\"url\":\"file:///server-private/file.txt\"}}"; h.deliveryStatus = 409;
        CountDownLatch done = new CountDownLatch(1); List<String> errors = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("a", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { fail("Not ready"); }
            public void onFailure(String code, String message) { errors.add(code); done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(Collections.singletonList("ATTACHMENT_NOT_READY"), errors);
        assertTrue(h.client.isConnected());
    }
    @Test public void filesystemDownloadUrlIsRejectedWithoutExposingServerPaths() throws Exception {
        start(config()); h.ready(); h.downloadResponse = "{\"data\":{\"url\":\"file:///private/server-only/data.txt\"}}";
        CountDownLatch done = new CountDownLatch(1); List<String> result = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("a", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { fail("Server file URL must not be opened on a customer device"); }
            public void onFailure(String code, String message) { result.add(code); assertFalse(message.contains("server-only")); done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(Collections.singletonList("DOWNLOAD_LINK_UNAVAILABLE"), result);
        assertTrue(h.client.isConnected());
    }
    @Test public void unsafeDownloadAndHttpFailuresAreRecoverable() throws Exception {
        start(config()); h.ready(); h.downloadResponse = "{\"url\":\"javascript:alert(1)\"}";
        CountDownLatch done = new CountDownLatch(1); List<String> errors = new CopyOnWriteArrayList<>();
        h.client.resolveAttachmentDownloadUrl("a", new ArtemisAttachmentUrlCallback() {
            public void onSuccess(String url) { fail("Unsafe URL"); }
            public void onFailure(String code, String message) { errors.add(code); done.countDown(); }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals("DOWNLOAD_LINK_UNAVAILABLE", errors.get(0));
        h.attachmentStatus = 415; UploadResult rejected = new UploadResult();
        h.client.uploadAttachment("a.txt", "text/plain", () -> new java.io.ByteArrayInputStream(new byte[1]), rejected);
        rejected.await(); assertEquals("FILE_TYPE_REJECTED", rejected.code);
        h.attachmentStatus = 200; UploadResult retried = new UploadResult();
        h.client.uploadAttachment("a.txt", "text/plain", () -> new java.io.ByteArrayInputStream(new byte[1]), retried);
        retried.await(); assertEquals("attachment", retried.id); assertTrue(h.client.isConnected());
    }
    @Test public void transferSurveyHasDedicatedAckLaneAndAcceptsOptionalFeedbackId() throws Exception {
        start(config()); h.ready(); AtomicInteger success = new AtomicInteger(), failure = new AtomicInteger();
        h.client.submitAgentTransferSurvey("transfer", "provider", "nps", 0, "comment", callback(success, failure));
        assertEquals("agent_transfer_survey.submit", h.lastFrame().get("type").getAsString());
        assertEquals("provider", h.lastFrame().get("smartAssistSessionId").getAsString());
        h.message("{\"type\":\"feedback.ack\",\"messageId\":\"transfer\",\"success\":true,\"feedbackId\":\"wrong-lane\"}");
        h.message("{\"type\":\"agent_transfer_survey.ack\",\"artemisSessionId\":\"other\",\"success\":true}");
        assertEquals(0, success.get());
        h.message("{\"type\":\"agent_transfer_survey.ack\",\"artemisSessionId\":\"transfer\",\"success\":true}");
        assertEquals(1, success.get()); assertEquals(0, failure.get());
    }
    @Test public void transferSurveyValidatesScoreAndSuppressesDuplicatePendingSubmission() throws Exception {
        start(config()); h.ready(); ArtemisFeedbackCallback callback = callback(new AtomicInteger(), new AtomicInteger());
        try { h.client.submitAgentTransferSurvey("t", null, "csat", 0, null, callback); fail("Invalid score"); } catch (IllegalArgumentException expected) { }
        h.client.submitAgentTransferSurvey("t", null, "csat", 5, null, callback);
        try { h.client.submitAgentTransferSurvey("t", null, "csat", 4, null, callback); fail("Duplicate pending"); } catch (IllegalStateException expected) { }
    }
    @Test public void transferSurveyTimeoutAndRejectedSendAllowRetry() throws Exception {
        start(config()); h.ready(); CountDownLatch timeout = new CountDownLatch(1);
        h.client.submitAgentTransferSurvey("t", null, "likeDislike", 1, null, 20, new ArtemisFeedbackCallback() {
            public void onSuccess(String id) { fail("No acknowledgement"); }
            public void onFailure(String code, String message) { assertEquals("SURVEY_TIMEOUT", code); timeout.countDown(); }
        });
        assertTrue(timeout.await(2, TimeUnit.SECONDS));
        AtomicInteger failures = new AtomicInteger(); h.sockets.get(0).accept = false;
        h.client.submitAgentTransferSurvey("t", null, "likeDislike", 1, null, callback(new AtomicInteger(), failures));
        assertEquals(1, failures.get()); h.sockets.get(0).accept = true;
        h.client.submitAgentTransferSurvey("t", null, "likeDislike", 1, null, callback(new AtomicInteger(), failures));
        h.message("{\"type\":\"agent_transfer_survey.ack\",\"artemisSessionId\":\"t\",\"success\":false,\"error\":{\"message\":\"secret-provider-diagnostics\"}}");
        assertEquals(2, failures.get()); assertTrue(h.client.isConnected());
    }
    @Test public void disconnectFailsPendingTransferSurveyWithoutCompletingBasicFeedback() throws Exception {
        start(config()); h.ready(); AtomicInteger surveyFailures = new AtomicInteger(), basicFailures = new AtomicInteger();
        h.client.submitAgentTransferSurvey("t", null, "nps", 10, null, callback(new AtomicInteger(), surveyFailures));
        h.client.submitFeedback("a", "thumbs", 1, null, null, callback(new AtomicInteger(), basicFailures));
        h.client.disconnect(); assertEquals(1, surveyFailures.get()); assertEquals(1, basicFailures.get());
    }
    private static java.io.InputStream blockingStream(CountDownLatch reading) {
        return new java.io.InputStream() {
            final CountDownLatch closed = new CountDownLatch(1);
            public int read() throws IOException {
                reading.countDown();
                try { closed.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                return -1;
            }
            @Override public void close() { closed.countDown(); }
        };
    }
    private static class UploadResult implements ArtemisAttachmentCallback {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger completed = new AtomicInteger();
        final List<Long> progress = new CopyOnWriteArrayList<>(); volatile String id, session, code;
        public void onProgress(long uploaded, long total) { progress.add(uploaded); }
        public void onSuccess(String id, String session) { this.id = id; this.session = session; completed.incrementAndGet(); done.countDown(); }
        public void onFailure(String code, String message) { this.code = code; completed.incrementAndGet(); done.countDown(); }
        void await() throws Exception { assertTrue("Attachment callback timed out", done.await(3, TimeUnit.SECONDS)); }
    }

    private ArtemisFeedbackCallback callback(AtomicInteger success, AtomicInteger failure) { return new ArtemisFeedbackCallback() {
        public void onSuccess(String id) { success.incrementAndGet(); }
        public void onFailure(String code, String message) { failure.incrementAndGet(); }
    }; }
    private static class Harness implements ArtemisSocketListener {
        final List<Request> requests = new CopyOnWriteArrayList<>(); final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<FakeSocket> sockets = new CopyOnWriteArrayList<>(); final ArtemisSocketClient client;
        volatile boolean changeScopeOnRefresh, blockInit;
        final CountDownLatch initEntered = new CountDownLatch(1), releaseInit = new CountDownLatch(1);
        final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        final AtomicInteger connectedCallbacks = new AtomicInteger(), disconnectedCallbacks = new AtomicInteger();
        volatile int deliveryStatus = 404;
        volatile String deliveryResponse = "{\"data\":{\"download\":{\"available\":true,\"url\":\"https://files.example.com/scoped-delivery\"}}}";
        volatile int attachmentStatus = 200;
        volatile String downloadResponse = "{\"data\":{\"url\":\"https://files.example.com/download\"}}";
        volatile int httpStatus = 200; volatile long expirySeconds = 3600; volatile String history;
        final CountDownLatch historyReady = new CountDownLatch(1);
        Harness(ArtemisSocketConfiguration configuration) { this(configuration,false); }
        Harness(ArtemisSocketConfiguration configuration, boolean deferCallbacks) {
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                Request request = chain.request(); String body = "";
                if (request.body() != null) { okio.Buffer b = new okio.Buffer(); request.body().writeTo(b); body = b.readUtf8(); }
                bodies.add(body); requests.add(request);
                String path = request.url().encodedPath(); String payload;
                if (path.endsWith("/init") && blockInit) {
                    initEntered.countDown();
                    try { if (!releaseInit.await(3,TimeUnit.SECONDS)) throw new IOException("Fixture timeout"); }
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
                }
                if (path.endsWith("/delivery-url")) payload = deliveryResponse;
                else if (path.endsWith("/attachments")) payload = "{\"attachmentId\":\"attachment\"}";
                else if (path.endsWith("/url")) payload = downloadResponse;
                else if (path.endsWith("ws-ticket")) payload = "{\"ticket\":\"ticket\"}";
                else if (path.endsWith("messages")) payload = request.url().queryParameter("cursor") == null
                    ? "{\"messages\":[{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"one\"}],\"hasMore\":true,\"nextCursor\":\"cursor\"}"
                    : "{\"messages\":[{\"id\":\"b\",\"role\":\"assistant\",\"content\":\"two\"}],\"hasMore\":false}";
                else payload = "{\"token\":\"token\",\"expiresIn\":"+expirySeconds+",\"tenantId\":\"t\",\"projectId\":\"project\",\"channelId\":\"channel\"}";
                if (path.endsWith("/refresh") && changeScopeOnRefresh) payload = payload.replace("\"channel\"", "\"other-channel\"");
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(path.endsWith("/delivery-url") ? deliveryStatus : path.contains("/attachments") ? attachmentStatus : httpStatus).message("fixture")
                    .body(ResponseBody.create(payload, MediaType.get("application/json"))).build();
            }).build();
            client = new ArtemisSocketClient(configuration, this, http, (request, listener) -> {
                FakeSocket socket = new FakeSocket(request, listener); sockets.add(socket); return socket;
            }, deferCallbacks ? callbacks::add : Runnable::run, Executors.newSingleThreadScheduledExecutor());
        }
        void awaitSockets(int count) throws Exception { await(() -> sockets.size() >= count); }
        void awaitRequests(int count) throws Exception { await(() -> requests.size() >= count); }
        void awaitPath(String path) throws Exception { await(() -> count(path) > 0); }
        int count(String path) { return (int) requests.stream().filter(r -> r.url().encodedPath().equals(path)).count(); }
        void await(java.util.function.BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("Timed out waiting for fixture operation", condition.getAsBoolean());
            // Factory records before the client assigns its socket; acquire its monitor before emitting frames.
            synchronized (client) { }
        }
        void flushCallbacks() {for(Runnable callback: new ArrayList<>(callbacks)) callback.run();callbacks.clear();}
        void ready() { message("{\"type\":\"session_start\",\"sessionId\":\"session\"}"); }
        void message(String text) { FakeSocket socket = sockets.get(sockets.size()-1); socket.listener.onMessage(socket, text); }
        JsonObject lastFrame() { List<String> frames = sockets.get(sockets.size()-1).frames; return JsonParser.parseString(frames.get(frames.size()-1)).getAsJsonObject(); }
        public void onLog(String message) { } public void onConnecting() { } public void onConnected(String id) { connectedCallbacks.incrementAndGet(); }
        public void onDisconnected(String reason) { disconnectedCallbacks.incrementAndGet(); } public void onMessage(String payload) { } public void onError(Throwable error) { }
        public void onHistory(String id, String payload) { history = payload; historyReady.countDown(); }
    }
    private static class FakeSocket implements WebSocket {
        final Request request; final WebSocketListener listener; final List<String> frames = new CopyOnWriteArrayList<>(); boolean accept = true;
        FakeSocket(Request request, WebSocketListener listener) { this.request=request; this.listener=listener; }
        public Request request() { return request; } public long queueSize() { return 0; }
        public boolean send(String text) { if (accept) frames.add(text); return accept; }
        public boolean send(ByteString bytes) { return accept; } public boolean close(int code, String reason) { return true; } public void cancel() { }
    }
}
