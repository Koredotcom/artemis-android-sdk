package artemis.socket;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** API-key or hosted bootstrap, SDK token, then a one-shot ticket on /ws/sdk. */
public final class ArtemisSocketClient {
    private static final long DEFAULT_FEEDBACK_TIMEOUT_MS = 10000L;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();
    private static final Executor RESOURCE_CLEANUP = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "ArtemisSocketCleanup");
        thread.setDaemon(true);
        return thread;
    });
    private final ArtemisSocketConfiguration configuration;
    private final ArtemisSocketListener listener;
    private final OkHttpClient httpClient;
    private final OkHttpClient attachmentHttpClient;
    private final ExecutorService attachmentExecutor = Executors.newFixedThreadPool(2);
    private final Set<AttachmentJob> attachmentJobs = ConcurrentHashMap.newKeySet();
    private final WebSocket.Factory socketFactory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler;
    private final Dispatch mainHandler;
    private final Map<String, PendingFeedback> pendingFeedback = new ConcurrentHashMap<>();
    private final Map<String, PendingFeedback> pendingSurveys = new ConcurrentHashMap<>();
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean disconnectRequested = true;
    private volatile boolean shutdown;
    private volatile boolean endingSession;
    private volatile String sessionId;
    private volatile boolean liveUserInputRedaction;
    private volatile JsonObject sessionScope = new JsonObject();
    private volatile String sdkToken;
    private volatile long tokenExpiresAt;
    private int reconnectAttempts;
    private int cappedReconnectAttempts;
    private volatile int generation;
    private long lastActivityAt;
    private volatile boolean newSessionRequested;
    private volatile long newSessionRevision;
    private ScheduledFuture<?> reconnectTask, refreshTask, readyTask, idleTask, endTask;

    public ArtemisSocketClient(ArtemisSocketConfiguration configuration, ArtemisSocketListener listener) {
        this(configuration, listener, new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
            null, androidCallbacks(), Executors.newSingleThreadScheduledExecutor());
    }

    // Injected dependencies let connection regression tests run without Android or a network.
    ArtemisSocketClient(ArtemisSocketConfiguration configuration, ArtemisSocketListener listener,
            OkHttpClient httpClient, WebSocket.Factory socketFactory, Executor callbacks,
            ScheduledExecutorService scheduler) {
        if (configuration == null || listener == null) throw new IllegalArgumentException("configuration and listener are required");
        this.configuration = configuration;
        this.newSessionRequested = configuration.isNewSessionOnConnect();
        this.listener = listener;
        this.httpClient = httpClient;
        this.attachmentHttpClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(120, TimeUnit.SECONDS).build();
        this.socketFactory = socketFactory == null ? httpClient : socketFactory;
        this.scheduler = scheduler;
        this.mainHandler = new Dispatch(callbacks, scheduler);
    }

    private static Executor androidCallbacks() {
        Handler handler = new Handler(Looper.getMainLooper());
        return task -> handler.post(task);
    }

    public synchronized void connect() {
        if (shutdown) throw new IllegalStateException("Artemis client is shut down");
        if (connected || connecting || webSocket != null) return;
        disconnectRequested = false;
        endingSession = false;
        cancel(reconnectTask); reconnectTask = null;
        reconnectAttempts = 0; cappedReconnectAttempts = 0;
        beginConnect();
    }

    private synchronized void beginConnect() {
        if (disconnectRequested || shutdown || connecting) return;
        connecting = true;
        int epoch = ++generation;
        notifyConnecting();
        executor.execute(() -> startSession(epoch));
    }

    /** Socket disconnect preserves the token so a later connect can resume it. */
    public synchronized void disconnect() {
        disconnectRequested = true;
        connecting = false; connected = false;
        ++generation;
        cancel(reconnectTask); cancel(refreshTask); cancel(readyTask); cancel(idleTask); cancel(endTask);
        reconnectTask = null;
        WebSocket socket = webSocket; webSocket = null;
        if (socket != null) socket.close(1000, "Client disconnect");
        cancelAttachmentJobs("DISCONNECTED");
        failPendingFeedback("DISCONNECTED", "Connection closed before feedback acknowledgement");
        notifyDisconnected("Client disconnect");
    }

    /** Ends the Runtime conversation; disconnect() only detaches the socket. */
    public synchronized void endSession() {
        newSessionRequested = true;
        ++newSessionRevision;
        endingSession = true;
        cancelAttachmentJobs("SESSION_CHANGED");
        cancel(refreshTask); cancel(idleTask); cancel(reconnectTask);
        if (!connected || webSocket == null) { disconnect(); sdkToken = null; return; }
        JsonObject frame = new JsonObject(); frame.addProperty("type", "end_session");
        if (!webSocket.send(GSON.toJson(frame))) { disconnect(); sdkToken = null; return; }
        int epoch = generation;
        endTask = scheduler.schedule(() -> { if (epoch == generation) disconnect(); }, 3, TimeUnit.SECONDS);
    }

    public boolean isConnected() { return connected; }
    public String getSessionId() { return sessionId; }
    public boolean isLiveUserInputRedaction() { return liveUserInputRedaction; }
    public JsonObject getSessionScope() { return sessionScope.deepCopy(); }

    /** Existing API retains its wire contract; the overload allows UI/server ID reconciliation. */
    public void sendMessage(String text) { sendMessage(text, UUID.randomUUID().toString(), null, null); }

    public synchronized void sendMessage(String text, String messageId, JsonObject metadata, JsonObject interactionContext) {
        sendMessage(text, messageId, metadata, interactionContext, null);
    }

    /** Attachment IDs are uploaded within the current Runtime session. */
    public synchronized void sendMessage(String text, String messageId, JsonObject metadata, JsonObject interactionContext, List<String> attachmentIds) {
        if (isBlank(messageId) || (isBlank(text) && (attachmentIds == null || attachmentIds.isEmpty())))
            throw new IllegalArgumentException("message or attachments, and messageId are required");
        if (attachmentIds != null && (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE || attachmentIds.stream().anyMatch(ArtemisSocketClient::isBlank)))
            throw new IllegalArgumentException("A message supports up to 5 valid attachment IDs");
        long staleMs = configuration.getStaleSessionRefreshMs();
        if (staleMs > 0 && lastActivityAt > 0 && System.currentTimeMillis() - lastActivityAt > staleMs) {
            WebSocket old = webSocket; webSocket = null; connected = false;
            if (old != null) old.cancel();
            connecting = false;
            beginConnect();
            throw new IllegalStateException("Refreshing the idle connection; retry when connected");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "chat_message"); frame.addProperty("text", text == null ? "" : text);
        if (attachmentIds != null && !attachmentIds.isEmpty()) frame.add("attachmentIds", GSON.toJsonTree(attachmentIds));
        frame.addProperty("messageId", messageId);
        if (!isBlank(sessionId)) frame.addProperty("sessionId", sessionId);
        if (metadata != null) frame.add("metadata", metadata.deepCopy());
        if (interactionContext != null) frame.add("interactionContext", interactionContext.deepCopy());
        sendFrame(frame);
    }

    public void submitAction(String actionId, String value, Map<String, String> formData, String renderId) {
        submitAction(actionId, value, formData, renderId, null, null);
    }

    public void submitAction(String actionId, String value, Map<String, String> formData, String renderId,
            String displayText, String messageId) {
        if (isBlank(actionId)) throw new IllegalArgumentException("actionId is required");
        if (formData != null) for (Map.Entry<String, String> e : formData.entrySet()) {
            if (isBlank(e.getKey()) || e.getValue() == null) throw new IllegalArgumentException("formData keys and values must not be null");
        }
        JsonObject frame = new JsonObject(); frame.addProperty("type", "action_submit");
        frame.addProperty("actionId", actionId.trim());
        if (value != null) frame.addProperty("value", value);
        if (formData != null) frame.add("formData", GSON.toJsonTree(new LinkedHashMap<>(formData)));
        if (renderId != null) frame.addProperty("renderId", renderId);
        if (!isBlank(displayText) && !isBlank(messageId)) {
            frame.addProperty("displayText", displayText);
            frame.addProperty("messageId", messageId);
        }
        sendFrame(frame);
    }

    /** Existing Runtime JIT authorization contract; no credentials or callback URL are sent. */
    public synchronized void respondToAuthorization(String expectedSessionId, String toolCallId, String status) {
        requireAuthorizationSession(expectedSessionId);
        if (isBlank(toolCallId) || toolCallId.length() > 8192 || !("completed".equals(status) || "cancelled".equals(status)))
            throw new IllegalArgumentException("A valid authorization identity and status are required");
        JsonObject frame = new JsonObject(); frame.addProperty("type", "auth_response");
        frame.addProperty("toolCallId", toolCallId); frame.addProperty("status", status);
        sendFrame(frame);
    }

    /** Ask Runtime to re-evaluate stored credentials; the client cannot grant authorization. */
    public synchronized void recheckAuthorization(String expectedSessionId, String authProfileRef, String requirementKey) {
        requireAuthorizationSession(expectedSessionId);
        if (isBlank(authProfileRef) || authProfileRef.length() > 8192 || isBlank(requirementKey) || requirementKey.length() > 8192)
            throw new IllegalArgumentException("A valid authorization requirement is required");
        JsonObject frame = new JsonObject(); frame.addProperty("type", "consent_satisfy");
        frame.addProperty("sessionId", expectedSessionId); frame.addProperty("authProfileRef", authProfileRef);
        frame.addProperty("requirementKey", requirementKey);
        sendFrame(frame);
    }

    private void requireAuthorizationSession(String expectedSessionId) {
        if (isBlank(expectedSessionId) || !expectedSessionId.equals(sessionId))
            throw new IllegalStateException("Authorization belongs to a different conversation");
        requireConnectedSocket();
    }

    public void sendPresence(boolean typing) {
        if (!connected) return;
        JsonObject frame = new JsonObject(); frame.addProperty("type", "user_presence");
        frame.addProperty("event", typing ? "typing" : "stop_typing");
        if (!isBlank(sessionId)) frame.addProperty("sessionId", sessionId);
        try { sendFrame(frame); } catch (IllegalStateException ignored) { }
    }

    private synchronized void sendFrame(JsonObject frame) {
        if (!requireConnectedSocket().send(GSON.toJson(frame))) throw new IllegalStateException("Artemis socket rejected the message");
        markActivity();
    }

    /**
     * Sends feedback and reports its server acknowledgement (10 second default
     * timeout).
     */
    public void submitFeedback(String messageId, String ratingType, int ratingValue,
                               String feedbackText, String actionRenderId,
                               ArtemisFeedbackCallback callback) {
        submitFeedback(messageId, ratingType, ratingValue, feedbackText, actionRenderId,
                DEFAULT_FEEDBACK_TIMEOUT_MS, callback);
    }

    /** Sends feedback with a caller-selected acknowledgement timeout in milliseconds. */
    public void submitFeedback(String messageId, String ratingType, int ratingValue,
                               String feedbackText, String actionRenderId, long timeoutMs,
                               ArtemisFeedbackCallback callback) {
        submitFeedback(messageId, ratingType, ratingValue, feedbackText, actionRenderId,
                null, null, false, timeoutMs, callback);
    }

    /** Existing feedback lane with optional resolved-survey answers; omitted fields preserve old frames. */
    public void submitFeedback(String messageId, String ratingType, int ratingValue,
                               String feedbackText, String actionRenderId, Integer followUpRating,
                               String followUpText, boolean amendsRating, long timeoutMs,
                               ArtemisFeedbackCallback callback) {
        if (followUpRating != null && followUpText != null) {
            throw new IllegalArgumentException("A follow-up answer must be rating or text, not both");
        }
        if (followUpRating != null && (followUpRating < 0 || followUpRating > 10)) {
            throw new IllegalArgumentException("Follow-up rating must be between 0 and 10");
        }
        if ((feedbackText != null && feedbackText.length() > 5000)
                || (followUpText != null && followUpText.length() > 5000)) {
            throw new IllegalArgumentException("Feedback text must not exceed 5000 characters");
        }
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new IllegalArgumentException("messageId must not be empty");
        }
        if (!"thumbs".equals(ratingType) && !"star".equals(ratingType) && !"scale".equals(ratingType) && !"text".equals(ratingType)) {
            throw new IllegalArgumentException("Unsupported ratingType");
        }
        if (("thumbs".equals(ratingType) && ratingValue != 0 && ratingValue != 1)
                || ("star".equals(ratingType) && (ratingValue < 1 || ratingValue > 10))
                || ("scale".equals(ratingType) && (ratingValue < 0 || ratingValue > 10))
                || ("text".equals(ratingType) && (ratingValue != 0 || isBlank(feedbackText)))) {
            throw new IllegalArgumentException("ratingValue is outside the supported range");
        }
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        WebSocket socket = requireConnectedSocket();

        String normalizedMessageId = messageId.trim();
        String key = feedbackKey(normalizedMessageId, actionRenderId);
        PendingFeedback pending = new PendingFeedback(callback);
        if (pendingFeedback.putIfAbsent(key, pending) != null) {
            throw new IllegalStateException("Feedback is already pending for this message");
        }
        try {
            synchronized (pending) {
                if (pendingFeedback.get(key) != pending) return;
                pending.timeout = () -> finishFeedback(
                        key, pending, null, "FEEDBACK_TIMEOUT",
                        "Feedback acknowledgement timed out");
                mainHandler.postDelayed(pending.timeout, timeoutMs);
                JsonObject feedback = new JsonObject();
                feedback.addProperty("type", "feedback.submit");
                feedback.addProperty("messageId", normalizedMessageId);
                feedback.addProperty("ratingType", ratingType);
                feedback.addProperty("ratingValue", ratingValue);
                if (feedbackText != null) {
                    feedback.addProperty("feedbackText", feedbackText);
                }
                if (actionRenderId != null) {
                    feedback.addProperty("actionRenderId", actionRenderId);
                }
                if (followUpRating != null) feedback.addProperty("followUpRating", followUpRating);
                if (followUpText != null) feedback.addProperty("followUpText", followUpText);
                if (amendsRating) feedback.addProperty("amendsRating", true);
                if (!socket.send(GSON.toJson(feedback))) {
                    finishFeedback(key, pending, null, "SEND_REJECTED",
                            "Artemis socket rejected the feedback");
                    return;
                }
                notifyLog("WS feedback submitted message_id=" + normalizedMessageId);
            }
        } catch (RuntimeException ignored) {
            finishFeedback(key, pending, null, "SEND_FAILED", "Feedback could not be sent");
        }
    }

    /** Dedicated contact-center survey lane; generic feedback acknowledgements cannot complete it. */
    public synchronized void submitAgentTransferSurvey(String artemisSessionId, String smartAssistSessionId,
            String surveyType, int score, String comments, ArtemisFeedbackCallback callback) {
        submitAgentTransferSurvey(artemisSessionId, smartAssistSessionId, surveyType, score, comments,
            DEFAULT_FEEDBACK_TIMEOUT_MS, callback);
    }

    public synchronized void submitAgentTransferSurvey(String artemisSessionId, String smartAssistSessionId,
            String surveyType, int score, String comments, long timeoutMs, ArtemisFeedbackCallback callback) {
        boolean valid = "nps".equals(surveyType) ? score >= 0 && score <= 10
            : "csat".equals(surveyType) ? score >= 1 && score <= 5
            : "likeDislike".equals(surveyType) && (score == 0 || score == 1);
        if (isBlank(artemisSessionId) || !valid || callback == null || timeoutMs <= 0)
            throw new IllegalArgumentException("Invalid agent-transfer survey response");
        requireConnectedSocket();
        String key = artemisSessionId.trim();
        PendingFeedback pending = new PendingFeedback(callback);
        if (pendingSurveys.putIfAbsent(key, pending) != null) throw new IllegalStateException("Survey already pending");
        try {
            synchronized (pending) {
                pending.timeout = () -> finishPending(pendingSurveys, key, pending, null,
                    "SURVEY_TIMEOUT", "Survey acknowledgement timed out. Please retry.");
                mainHandler.postDelayed(pending.timeout, timeoutMs);
                JsonObject frame = new JsonObject();
                frame.addProperty("type", "agent_transfer_survey.submit");
                frame.addProperty("artemisSessionId", key);
                if (!isBlank(smartAssistSessionId)) frame.addProperty("smartAssistSessionId", smartAssistSessionId.trim());
                frame.addProperty("surveyType", surveyType); frame.addProperty("score", score);
                if (comments != null) frame.addProperty("comments", comments);
                sendFrame(frame);
            }
        } catch (RuntimeException error) {
            finishPending(pendingSurveys, key, pending, null, "SEND_REJECTED", "Survey could not be sent. Please retry.");
        }
    }

    private void handleSurveyAcknowledgement(JsonObject payload) {
        String key = stringValue(payload, "artemisSessionId");
        if (isBlank(key)) return;
        PendingFeedback pending = pendingSurveys.get(key);
        JsonElement success = payload.get("success");
        if (pending == null || success == null || !success.isJsonPrimitive() || !success.getAsJsonPrimitive().isBoolean()) return;
        if (success.getAsBoolean()) {
            finishPending(pendingSurveys, key, pending, stringValue(payload, "feedbackId"), null, null);
        } else {
            JsonObject error = payload.has("error") && payload.get("error").isJsonObject() ? payload.getAsJsonObject("error") : null;
            String code = error == null ? null : stringValue(error, "code");
            // Provider payloads may contain private service diagnostics. Expose only a stable message.
            finishPending(pendingSurveys, key, pending, null, isBlank(code) ? "SURVEY_REJECTED" : code,
                "Survey could not be submitted. Please retry.");
        }
    }

    /** Backward-compatible convenience API, with bounded streaming and the default size limit. */
    public void uploadAttachment(String filename, String mimeType, ArtemisAttachmentSource source, ArtemisAttachmentCallback callback) {
        uploadAttachment(filename, mimeType, -1, MAX_ATTACHMENT_BYTES, source, callback);
    }

    /**
     * Streams one file on a separate worker. sizeBytes may be -1 for content providers with no size.
     * The SDK never replays an upload automatically and never bootstraps a session from a file worker.
     */
    public synchronized ArtemisAttachmentUpload uploadAttachment(String filename, String mimeType, long sizeBytes,
            long maxBytes, ArtemisAttachmentSource source, ArtemisAttachmentCallback callback) {
        if (isBlank(filename) || source == null || callback == null || sizeBytes < -1 || maxBytes < 1 || maxBytes > MAX_ATTACHMENT_BYTES)
            throw new IllegalArgumentException("File, size policy and callback are required");
        MediaType fileType = MediaType.parse(isBlank(mimeType) ? "application/octet-stream" : mimeType);
        if (fileType == null || fileType.type().equals("multipart")) throw new IllegalArgumentException("Invalid file MIME type");
        if (sizeBytes == 0 || sizeBytes > maxBytes) throw new IllegalArgumentException("File is empty or exceeds the configured size limit");
        requireConnectedSocket();
        final int epoch = generation;
        final String uploadSession = sessionId;
        final String safeName = filename.replaceAll("[\\r\\n\\\\/]", "_").replace('"', '_');
        AttachmentJob job = new AttachmentJob(epoch, uploadSession, callback::onFailure);
        attachmentJobs.add(job);
        job.future = attachmentExecutor.submit(() -> {
            try {
                job.checkCurrent();
                RequestBody file = new RequestBody() {
                    @Override public MediaType contentType() { return fileType; }
                    @Override public long contentLength() { return sizeBytes; }
                    @Override public boolean isOneShot() { return true; }
                    @Override public void writeTo(okio.BufferedSink sink) throws IOException {
                        long count = 0, reported = 0;
                        try (java.io.InputStream stream = source.open()) {
                            if (stream == null) throw new IOException("File unavailable");
                            job.stream = stream;
                            job.checkCurrent();
                            byte[] buffer = new byte[8192]; int read;
                            job.progress(callback, 0, sizeBytes);
                            while ((read = stream.read(buffer)) != -1) {
                                job.checkCurrent();
                                if (read == 0) continue;
                                count += read;
                                if (count > maxBytes || (sizeBytes >= 0 && count > sizeBytes)) throw new AttachmentFailure("FILE_TOO_LARGE");
                                sink.write(buffer, 0, read);
                                if (count - reported >= Math.max(65536, sizeBytes / 100)) {
                                    reported = count; job.progress(callback, count, sizeBytes);
                                }
                            }
                            if (count == 0 || (sizeBytes >= 0 && count != sizeBytes)) throw new AttachmentFailure("FILE_CHANGED");
                            job.progress(callback, count, count);
                        } finally { job.stream = null; }
                    }
                };
                RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", safeName, file).build();
                JsonObject response = job.execute(new Request.Builder().url(attachmentUrl(uploadSession, null))
                    .header("X-SDK-Token", attachmentToken()).post(multipart).build());
                String id = stringValue(response, "attachmentId");
                if (isBlank(id)) throw new IOException("Missing attachment ID");
                job.success(() -> callback.onSuccess(id, uploadSession));
            } catch (Exception error) { job.failure(attachmentErrorCode(error)); }
        });
        return job;
    }

    /** Resolve at click time with SDK auth, never persist the signed URL. */
    public synchronized void resolveAttachmentDownloadUrl(String attachmentId, ArtemisAttachmentUrlCallback callback) {
        if (isBlank(attachmentId) || callback == null) throw new IllegalArgumentException("Attachment and callback are required");
        requireConnectedSocket();
        AttachmentJob job = new AttachmentJob(generation, sessionId, callback::onFailure);
        attachmentJobs.add(job);
        job.future = attachmentExecutor.submit(() -> {
            try {
                job.checkCurrent();
                JsonObject response = job.execute(new Request.Builder().url(attachmentUrl(job.scopedSession, attachmentId))
                    .header("X-SDK-Token", attachmentToken()).build());
                String url = readAttachmentUrl(response);
                if (!isBlank(url) && url.regionMatches(true, 0, "file:", 0, 5)) {
                    // Dev storage may return a server-local file URI. The existing authenticated
                    // mint route serves the same scoped bytes over HTTP without exposing that path.
                    notifyLog("ATTACHMENT using authenticated delivery fallback for server-local storage");
                    HttpUrl original = attachmentUrl(job.scopedSession, attachmentId);
                    HttpUrl delivery = original.newBuilder().setPathSegment(original.pathSize() - 1, "delivery-url")
                        .removeAllQueryParameters("disposition").build();
                    try {
                        JsonObject minted = job.execute(new Request.Builder().url(delivery)
                            .header("X-SDK-Token", attachmentToken()).post(RequestBody.create("{}", JSON)).build());
                        JsonObject data = minted.has("data") && minted.get("data").isJsonObject() ? minted.getAsJsonObject("data") : null;
                        JsonObject download = data != null && data.has("download") && data.get("download").isJsonObject() ? data.getAsJsonObject("download") : null;
                        url = download != null && download.has("available") && download.get("available").isJsonPrimitive()
                            && download.get("available").getAsJsonPrimitive().isBoolean() && download.get("available").getAsBoolean()
                            ? stringValue(download, "url") : "";
                    } catch (HttpFailure error) {
                        if (error.status == 409) throw new AttachmentFailure("ATTACHMENT_NOT_READY");
                        if (error.status == 404 || error.status == 405 || error.status == 503) throw new AttachmentFailure("DOWNLOAD_LINK_UNAVAILABLE");
                        throw error;
                    }
                }
                notifyLog("ATTACHMENT download response urlPresent=" + !isBlank(url) + " relative=" + (!isBlank(url) && url.startsWith("/")));
                HttpUrl parsed = isBlank(url) ? null : Objects.requireNonNull(HttpUrl.parse(httpEndpoint())).resolve(url);
                if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) {
                    String scheme = "invalid";
                    try {
                        String candidate = new java.net.URI(url).getScheme();
                        if (candidate != null && candidate.matches("[A-Za-z][A-Za-z0-9+.-]{0,15}")) scheme = candidate;
                    } catch (Exception ignored) { }
                    notifyLog("ATTACHMENT invalid download URL scheme=" + scheme + " embeddedCredentials="
                        + (parsed != null && (!parsed.username().isEmpty() || !parsed.password().isEmpty())));
                    throw new AttachmentFailure("DOWNLOAD_LINK_UNAVAILABLE");
                }
                final String resolved = parsed.toString();
                job.success(() -> callback.onSuccess(resolved));
            } catch (Exception error) { job.failure(attachmentErrorCode(error)); }
        });
    }

    private static String readAttachmentUrl(JsonObject response) {
        String url = stringValue(response, "url");
        if (isBlank(url) && response.has("data") && response.get("data").isJsonObject()) url = stringValue(response.getAsJsonObject("data"), "url");
        return url;
    }

    private String attachmentToken() throws IOException {
        String token = sdkToken;
        if (isBlank(token) || tokenExpiresAt > 0 && System.currentTimeMillis() >= tokenExpiresAt)
            throw new AttachmentFailure("AUTH_REQUIRED");
        return token; // Scheduled refresh exclusively owns credential mutation.
    }

    private String attachmentErrorCode(Exception error) {
        // Diagnostics exclude provider bodies, signed URLs, filenames and credentials.
        notifyLog("ATTACHMENT failure class=" + error.getClass().getSimpleName()
            + (error instanceof HttpFailure ? " status=" + ((HttpFailure) error).status : ""));
        if (error instanceof AttachmentFailure) return error.getMessage();
        if (error instanceof HttpFailure) {
            int status = ((HttpFailure) error).status;
            if (status == 413) return "FILE_TOO_LARGE";
            if (status == 415) return "FILE_TYPE_REJECTED";
            if (status == 401 || status == 403) return "ATTACHMENT_FORBIDDEN";
            if (status == 404 || status == 405) return "ATTACHMENTS_UNAVAILABLE";
        }
        return "ATTACHMENT_FAILED";
    }

    private static String attachmentErrorMessage(String code) {
        switch (code) {
            case "CANCELLED": return "Attachment transfer cancelled.";
            case "FILE_TOO_LARGE": return "File exceeds the configured size limit.";
            case "FILE_TYPE_REJECTED": return "This file type is not allowed by the project.";
            case "FILE_CHANGED": return "File is empty or changed during upload. Please select it again.";
            case "AUTH_REQUIRED": return "Authentication expired. Retry when connected.";
            case "ATTACHMENT_FORBIDDEN": return "The project did not permit this attachment. Check its attachment settings.";
            case "ATTACHMENTS_UNAVAILABLE": return "Attachments are unavailable on this Runtime.";
            case "ATTACHMENT_NOT_READY": return "The attachment is still being prepared. Please try opening it again shortly.";
            case "DOWNLOAD_LINK_UNAVAILABLE": return "The attachment service did not provide a usable web download link. Please retry or contact support.";
            case "DISCONNECTED": return "Connection lost. Retry the attachment when connected.";
            case "SESSION_CHANGED": return "Conversation changed. Please select the attachment again.";
            default: return "Attachment transfer failed. Please retry.";
        }
    }

    private void cancelAttachmentJobs(String code) {
        for (AttachmentJob job : attachmentJobs) job.cancelWith(code);
    }

    private final class AttachmentJob implements ArtemisAttachmentUpload {
        final int epoch; final String scopedSession;
        final java.util.function.BiConsumer<String, String> onFailure;
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        volatile Call call; volatile Future<?> future; volatile java.io.InputStream stream;
        AttachmentJob(int epoch, String scopedSession, java.util.function.BiConsumer<String, String> onFailure) {
            this.epoch = epoch; this.scopedSession = scopedSession; this.onFailure = onFailure;
        }
        @Override public boolean isDone() { return done.get(); }
        @Override public void cancel() { cancelWith("CANCELLED"); }
        void cancelWith(String code) {
            if (done.get()) return;
            failure(code);
            Future<?> task = future; if (task != null) task.cancel(true);
            RESOURCE_CLEANUP.execute(() -> {
                Call active = call; if (active != null) active.cancel();
                java.io.InputStream opened = stream; if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            });
        }
        void checkCurrent() throws IOException {
            if (done.get() || Thread.currentThread().isInterrupted()) throw new AttachmentFailure("CANCELLED");
            if (!current(epoch) || !Objects.equals(scopedSession, sessionId) || endingSession) throw new AttachmentFailure("SESSION_CHANGED");
        }
        JsonObject execute(Request request) throws IOException {
            checkCurrent();
            call = attachmentHttpClient.newCall(request);
            checkCurrent();
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new HttpFailure(response.code(), "Attachment request failed");
                return parseObject(response.body() == null ? "" : response.body().string(), "Attachment");
            } finally { call = null; }
        }
        void progress(ArtemisAttachmentCallback callback, long sent, long total) {
            mainHandler.post(() -> {
                if (!done.get() && current(epoch) && Objects.equals(scopedSession, sessionId)) safely(() -> callback.onProgress(sent, total));
            });
        }
        void success(Runnable callback) {
            if (!done.compareAndSet(false, true)) return;
            attachmentJobs.remove(this);
            mainHandler.post(() -> safely(() -> {
                if (current(epoch) && Objects.equals(scopedSession, sessionId) && !endingSession) callback.run();
                else onFailure.accept("SESSION_CHANGED", attachmentErrorMessage("SESSION_CHANGED"));
            }));
        }
        void failure(String code) {
            if (!done.compareAndSet(false, true)) return;
            attachmentJobs.remove(this);
            mainHandler.post(() -> safely(() -> onFailure.accept(code, attachmentErrorMessage(code))));
        }
        void safely(Runnable callback) {
            try { callback.run(); } catch (RuntimeException ignored) { notifyLog("Attachment callback failed"); }
        }
    }
    private static final class AttachmentFailure extends IOException { AttachmentFailure(String code) { super(code); } }

    private HttpUrl attachmentUrl(String scopedSession, String attachmentId) {
        HttpUrl.Builder url = Objects.requireNonNull(HttpUrl.parse(httpEndpoint())).newBuilder()
            .encodedPath("/api/projects/" + encode(configuration.getProjectId()) + "/sessions/" + encode(scopedSession) + "/attachments"
                + (attachmentId == null ? "" : "/" + encode(attachmentId) + "/url"));
        if (attachmentId != null) url.addQueryParameter("disposition", "attachment");
        return url.build();
    }

    private WebSocket requireConnectedSocket() {
        WebSocket socket = webSocket;
        if (!connected || socket == null) {
            throw new IllegalStateException("Artemis socket is not connected");
        }
        return socket;
    }

    private static String feedbackKey(String messageId, String actionRenderId) {
        String renderId = actionRenderId == null ? "" : actionRenderId;
        return messageId.length() + ":" + messageId + renderId.length() + ":" + renderId;
    }

    private void handleFeedbackAcknowledgement(JsonObject payload) {
        String messageId = stringValue(payload, "messageId");
        if (isBlank(messageId)) return;
        JsonElement rawRenderId = payload.get("actionRenderId");
        String renderId = rawRenderId == null || rawRenderId.isJsonNull()
                ? null : rawRenderId.getAsString();
        String key = feedbackKey(messageId, renderId);
        PendingFeedback pending = pendingFeedback.get(key);
        if (pending == null) return;

        boolean success = payload.has("success") && payload.get("success").getAsBoolean();
        String feedbackId = stringValue(payload, "feedbackId");
        if (success && !isBlank(feedbackId)) {
            finishFeedback(key, pending, feedbackId, null, null);
            return;
        }

        JsonObject error = payload.has("error") && payload.get("error").isJsonObject()
                ? payload.getAsJsonObject("error") : null;
        String code = error == null ? "FEEDBACK_REJECTED" : stringValue(error, "code");
        String message = error == null ? "Feedback was rejected" : stringValue(error, "message");
        finishFeedback(key, pending, null,
                isBlank(code) ? "FEEDBACK_REJECTED" : code,
                isBlank(message) ? "Feedback was rejected" : message);
    }

    private void finishFeedback(String key, PendingFeedback pending,
                                String feedbackId, String errorCode,
                                String errorMessage) {
        finishPending(pendingFeedback, key, pending, feedbackId, errorCode, errorMessage);
    }

    private void finishPending(Map<String, PendingFeedback> registry, String key, PendingFeedback pending,
            String feedbackId, String errorCode, String errorMessage) {
        synchronized (pending) {
            if (!registry.remove(key, pending)) return;
            if (pending.timeout != null) mainHandler.removeCallbacks(pending.timeout);
        }
        mainHandler.post(() -> {
            try {
                if (errorCode == null) pending.callback.onSuccess(feedbackId);
                else pending.callback.onFailure(errorCode, errorMessage);
            } catch (RuntimeException callbackError) {
                notifyLog("FEEDBACK callback failed: " + callbackError.getClass().getSimpleName());
            }
        });
    }

    private void failPendingFeedback(String code, String message) {
        for (Map.Entry<String, PendingFeedback> entry : pendingSurveys.entrySet()) {
            finishPending(pendingSurveys, entry.getKey(), entry.getValue(), null, code, "Connection closed before survey acknowledgement");
        }
        for (Map.Entry<String, PendingFeedback> entry : pendingFeedback.entrySet()) {
            finishFeedback(entry.getKey(), entry.getValue(), null, code, message);
        }
    }

    private static final class PendingFeedback {
        final ArtemisFeedbackCallback callback;
        volatile Runnable timeout;

        PendingFeedback(ArtemisFeedbackCallback callback) {
            this.callback = callback;
        }
    }

    public synchronized void shutdown() {
        if (shutdown) return;
        shutdown = true;
        disconnect();
        executor.shutdownNow(); attachmentExecutor.shutdownNow(); scheduler.shutdownNow();
        // Cancelling calls and evicting idle HTTPS connections can write TLS close-notify
        // records. Hosts may close their handle on the UI thread; never do that I/O there.
        RESOURCE_CLEANUP.execute(() -> {
            try {
                httpClient.dispatcher().cancelAll();
                httpClient.connectionPool().evictAll();
            } finally {
                httpClient.dispatcher().executorService().shutdown();
            }
        });
    }

    private void startSession(int epoch) {
        try {
            String token = getToken();
            String ticket = requestWebSocketTicket(token);
            synchronized (this) {
                if (!current(epoch)) return;
                openWebSocket(ticket, epoch);
            }
        } catch (Exception error) {
            synchronized (this) {
                if (!current(epoch)) return;
                connecting = false;
                notifyError(error);
                if (isCapped(error)) sdkToken = null;
                scheduleReconnect(isCapped(error));
            }
        }
    }

    private synchronized boolean current(int epoch) { return epoch == generation && !disconnectRequested && !shutdown; }

    private String getToken() throws Exception {
        if (sdkToken == null || newSessionRequested) return requestSdkToken();
        // Older Runtime responses without expiresIn remain usable, without a busy refresh timer.
        if (tokenExpiresAt == 0 || System.currentTimeMillis() < tokenExpiresAt - 30000) return sdkToken;
        try { return refreshToken(); }
        catch (HttpFailure error) {
            if (error.status != 401 && error.status != 403) throw error;
            sdkToken = null;
            if (!isBlank(configuration.getApiKey()) || configuration.getBootstrapTokenProvider() != null) return requestSdkToken();
            throw error;
        }
    }

    private String requestSdkToken() throws Exception {
        long intentRevision = newSessionRevision;
        JsonObject body = new JsonObject();
        Request.Builder request = new Request.Builder().url(httpEndpoint() + "/api/v1/sdk/init")
            .header("Content-Type", "application/json");
        if (isBlank(configuration.getApiKey())) {
            String bootstrap = configuration.getBootstrapTokenProvider() == null ? configuration.getBootstrapToken()
                : configuration.getBootstrapTokenProvider().getToken();
            if (isBlank(bootstrap)) throw new IOException("Bootstrap provider returned an empty token");
            body.addProperty("bootstrapToken", bootstrap.trim());
        } else {
            request.header("X-Public-Key", configuration.getApiKey());
            if (!isBlank(configuration.getChannelId())) body.addProperty("channelId", configuration.getChannelId());
            else if (!isBlank(configuration.getChannelName())) body.addProperty("channelName", configuration.getChannelName());
            if (!isBlank(configuration.getDeploymentSlug())) body.addProperty("deploymentSlug", configuration.getDeploymentSlug());
            if (!isBlank(configuration.getClientSessionIdentifier())) body.addProperty("clientSessionIdentifier", configuration.getClientSessionIdentifier());
            if (configuration.getUserContext() != null) body.add("userContext", configuration.getUserContext());
        }
        if (configuration.getSessionMetadata() != null) body.add("sessionMetadata", configuration.getSessionMetadata());
        if (newSessionRequested) body.addProperty("sessionIntent", "new");
        JsonObject payload = executeJson(request.post(RequestBody.create(GSON.toJson(body), JSON)).build(), "SDK init");
        storeToken(payload, false);
        if (intentRevision == newSessionRevision) newSessionRequested = false;
        return sdkToken;
    }

    private String refreshToken() throws IOException {
        JsonObject payload = executeJson(new Request.Builder().url(httpEndpoint() + "/api/v1/sdk/refresh")
            .header("X-SDK-Token", sdkToken).post(RequestBody.create("{}", JSON)).build(), "SDK refresh");
        storeToken(payload, true);
        return sdkToken;
    }

    private void storeToken(JsonObject payload, boolean refresh) throws IOException {
        String token = stringValue(payload, "token");
        if (isBlank(token)) throw new IOException("SDK response did not contain a token");
        JsonObject scope = payload.has("scope") && payload.get("scope").isJsonObject() ? payload.getAsJsonObject("scope") : payload;
        if (refresh) for (String key : new String[]{"tenantId", "projectId", "channelId", "deploymentId"}) {
            String previous = stringValue(sessionScope, key), next = stringValue(scope, key);
            if (!isBlank(previous) && !previous.equals(next)) throw new HttpFailure(403, "Runtime changed SDK session scope");
        }
        sdkToken = token;
        JsonObject nextScope = new JsonObject();
        for (String key : new String[]{"tenantId", "projectId", "channelId", "deploymentId", "permissions", "showActivityUpdates", "activityVisibilityPolicy"}) {
            if (scope.has(key)) nextScope.add(key, scope.get(key).deepCopy());
            else if (refresh && sessionScope.has(key)) nextScope.add(key, sessionScope.get(key).deepCopy());
        }
        // Branding may be returned beside a nested scope. Preserve an omitted refresh
        // value, but honor explicit null/disabled configuration from the Runtime.
        if (scope.has("widgetConfig")) nextScope.add("widgetConfig", scope.get("widgetConfig").deepCopy());
        else if (payload.has("widgetConfig")) nextScope.add("widgetConfig", payload.get("widgetConfig").deepCopy());
        else if (payload.has("config")) nextScope.add("widgetConfig", payload.get("config").deepCopy());
        else if (refresh && sessionScope.has("widgetConfig")) nextScope.add("widgetConfig", sessionScope.get("widgetConfig").deepCopy());
        sessionScope = nextScope;
        notifySessionScopeChanged(nextScope);
        long expiry = 0;
        try { if (payload.has("expiresIn")) expiry = payload.get("expiresIn").getAsLong(); } catch (RuntimeException ignored) { }
        tokenExpiresAt = expiry > 0 ? System.currentTimeMillis() + Math.min(expiry, 31536000) * 1000 : 0;
        scheduleRefresh();
    }

    private synchronized void scheduleRefresh() {
        cancel(refreshTask);
        if (tokenExpiresAt == 0 || disconnectRequested || shutdown || endingSession) return;
        int epoch = generation;
        refreshTask = scheduler.schedule(() -> {
            if (!current(epoch)) return;
            executor.execute(() -> {
                if (!current(epoch)) return;
                try { refreshToken(); }
                catch (Exception error) {
                    synchronized (ArtemisSocketClient.this) {
                        if (!current(epoch)) return;
                        notifyError(error);
                        WebSocket old = webSocket; webSocket = null; connected = false; connecting = false;
                        ++generation;
                        if (old != null) old.cancel();
                        notifyDisconnected("Token refresh failed");
                        cancelAttachmentJobs("DISCONNECTED");
                        failPendingFeedback("DISCONNECTED", "Connection closed before feedback acknowledgement");
                        scheduleReconnect(isCapped(error));
                    }
                }
            });
        }, Math.max(1000, tokenExpiresAt - System.currentTimeMillis() - 30000), TimeUnit.MILLISECONDS);
    }

    private String requestWebSocketTicket(String token) throws IOException {
        JsonObject payload = executeJson(new Request.Builder().url(httpEndpoint() + "/api/v1/sdk/ws-ticket")
            .header("X-SDK-Token", token).post(RequestBody.create("{}", JSON)).build(), "WebSocket ticket");
        String ticket = stringValue(payload, "ticket");
        if (isBlank(ticket)) throw new IOException("WebSocket ticket response did not contain a ticket");
        return ticket;
    }

    private JsonObject executeJson(Request request, String operation) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new HttpFailure(response.code(), operation + " failed (" + response.code() + ")");
            return parseObject(response.body() == null ? "" : response.body().string(), operation);
        }
    }

    private void openWebSocket(String ticket, int epoch) {
        Request request = new Request.Builder().url(webSocketEndpoint() + "/ws/sdk")
            .header("Sec-WebSocket-Protocol", "sdk-ticket, " + ticket).build();
        readyTask = scheduler.schedule(() -> connectionLost(epoch, new IOException("Timed out waiting for session_start"), "Session handshake timed out", true, -1), 30, TimeUnit.SECONDS);
        webSocket = socketFactory.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { /* Runtime session_start is the readiness boundary. */ }
            @Override public void onMessage(WebSocket socket, String text) {
                synchronized (ArtemisSocketClient.this) { if (current(epoch)) handleMessage(text); }
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            @Override public void onClosed(WebSocket socket, int code, String reason) { connectionLost(epoch, null, reason, code != 4000, code); }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                connectionLost(epoch, error, "Connection lost", true, response == null ? -1 : response.code());
            }
        });
    }

    private synchronized void connectionLost(int epoch, Throwable error, String reason, boolean retry, int code) {
        if (!current(epoch)) return;
        WebSocket old = webSocket; webSocket = null;
        ++generation;
        connected = false; connecting = false;
        cancel(readyTask); cancel(refreshTask); cancel(idleTask);
        if (old != null) old.cancel();
        cancelAttachmentJobs("DISCONNECTED");
        failPendingFeedback("DISCONNECTED", "Connection closed before feedback acknowledgement");
        notifyDisconnected(reason);
        if (error != null) notifyError(error);
        if (endingSession) { sdkToken = null; sessionId = null; disconnectRequested = true; return; }
        if (!retry) { disconnectRequested = true; return; }
        boolean capped = code == 4001 || code == 4003 || code == 4010 || code == 4008 || code == 401 || code == 403;
        if (capped) sdkToken = null;
        scheduleReconnect(capped);
    }

    private void handleMessage(String text) {
        try {
            JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
            String type = stringValue(payload, "type");
            markActivity();
            if ("feedback.ack".equals(type)) handleFeedbackAcknowledgement(payload);
            if ("agent_transfer_survey.ack".equals(type)) handleSurveyAcknowledgement(payload);
            if ("session_start".equals(type)) {
                String nextSession = stringValue(payload, "sessionId");
                if (isBlank(nextSession)) throw new IllegalArgumentException("session_start requires sessionId");
                if (sessionId != null && !sessionId.equals(nextSession)) {
                    cancelAttachmentJobs("SESSION_CHANGED");
                    failPendingFeedback("SESSION_CHANGED", "Conversation changed before acknowledgement");
                }
                sessionId = nextSession; connected = true; connecting = false;
                reconnectAttempts = 0; cappedReconnectAttempts = 0;
                cancel(readyTask);
                JsonObject capabilities = payload.has("capabilities") && payload.get("capabilities").isJsonObject()
                    ? payload.getAsJsonObject("capabilities") : new JsonObject();
                liveUserInputRedaction = capabilities.has("liveUserInputRedaction") && capabilities.get("liveUserInputRedaction").isJsonPrimitive()
                    && capabilities.get("liveUserInputRedaction").getAsBoolean();
                // UI consumes session policy before receiving the connected callback.
                notifyMessage(text);
                notifyConnected(sessionId);
                scheduleRefresh();
                markActivity();
                return;
            }
            notifyMessage(text);
        } catch (RuntimeException error) { notifyLog("Ignored malformed Runtime frame"); }
    }

    private synchronized void scheduleReconnect(boolean capped) {
        if (disconnectRequested || shutdown || !configuration.isReconnectionEnabled() || reconnectTask != null && !reconnectTask.isDone()) return;
        boolean enforceCap = capped || !configuration.isUnlimitedTransientReconnect();
        if (enforceCap && cappedReconnectAttempts >= configuration.getMaxReconnectAttempts()) return;
        if (enforceCap) cappedReconnectAttempts++;
        long base = configuration.getReconnectBaseDelayMs();
        long factor = 1L << Math.min(reconnectAttempts++, 20);
        long delay = Math.min(configuration.getReconnectMaxDelayMs(), base > Long.MAX_VALUE / factor ? Long.MAX_VALUE : base * factor);
        if (configuration.isUnlimitedTransientReconnect()) delay = (long)(delay * (0.5 + Math.random() * 0.5));
        reconnectTask = scheduler.schedule(() -> {
            synchronized (ArtemisSocketClient.this) { reconnectTask = null; beginConnect(); }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static boolean isCapped(Throwable error) { return error instanceof HttpFailure && (((HttpFailure)error).status == 401 || ((HttpFailure)error).status == 403); }

    public synchronized void markActivity() {
        lastActivityAt = System.currentTimeMillis();
        cancel(idleTask);
        if (!connected || configuration.getIdleTimeoutMs() == 0 || shutdown) return;
        int epoch = generation;
        idleTask = scheduler.schedule(() -> {
            if (!current(epoch)) return;
            if (configuration.isEndSessionOnIdle()) endSession(); else disconnect();
        }, configuration.getIdleTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** Paginated Runtime history. An unsupported route leaves legacy chat usable. */
    public void loadHistory() {
        final int epoch = generation;
        final String historySession = sessionId;
        if (isBlank(historySession) || shutdown) return;
        executor.execute(() -> {
            try {
                JsonArray messages = new JsonArray(); String cursor = null;
                for (int page = 0; page < 20; page++) {
                    if (!current(epoch)) return;
                    HttpUrl.Builder url = Objects.requireNonNull(HttpUrl.parse(httpEndpoint())).newBuilder()
                        .encodedPath("/api/projects/" + encode(configuration.getProjectId()) + "/sessions/" + encode(historySession) + "/messages")
                        .addQueryParameter("direction", "asc").addQueryParameter("limit", "100");
                    if (cursor != null) url.addQueryParameter("cursor", cursor);
                    JsonObject payload = executeJson(new Request.Builder().url(url.build()).header("X-SDK-Token", getToken()).build(), "History");
                    if (payload.has("messages") && payload.get("messages").isJsonArray()) messages.addAll(payload.getAsJsonArray("messages"));
                    boolean more = payload.has("hasMore") && payload.get("hasMore").getAsBoolean();
                    String next = stringValue(payload, "nextCursor");
                    if (!more || isBlank(next) || next.equals(cursor)) break;
                    cursor = next;
                }
                if (current(epoch) && historySession.equals(sessionId)) {
                    String body = GSON.toJson(messages);
                    mainHandler.post(() -> { if (current(epoch) && historySession.equals(sessionId)) listener.onHistory(historySession, body); });
                }
            } catch (Exception error) { notifyLog("History unavailable; retaining the live transcript"); }
        });
    }

    private static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status, String message) { super(message); this.status = status; }
    }

    private static final class Dispatch {
        final Executor callbacks; final ScheduledExecutorService scheduler;
        final Map<Runnable, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
        Dispatch(Executor callbacks, ScheduledExecutorService scheduler) { this.callbacks = callbacks; this.scheduler = scheduler; }
        void post(Runnable task) { callbacks.execute(task); }
        void postDelayed(Runnable task, long delay) { timers.put(task, scheduler.schedule(() -> { timers.remove(task); post(task); }, delay, TimeUnit.MILLISECONDS)); }
        void removeCallbacks(Runnable task) { cancel(timers.remove(task)); }
    }

    private JsonObject parseObject(String body, String operation) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IOException(operation + " response was not an object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException(operation + " response was not valid JSON", error);
        }
    }

    private String httpEndpoint() {
        String endpoint = stripTrailingSlash(configuration.getEndpoint());
        if (endpoint.startsWith("wss://")) {
            return "https://" + endpoint.substring("wss://".length());
        }
        if (endpoint.startsWith("ws://")) {
            return "http://" + endpoint.substring("ws://".length());
        }
        return endpoint;
    }

    private String webSocketEndpoint() {
        String endpoint = httpEndpoint();
        if (endpoint.startsWith("https://")) {
            return "wss://" + endpoint.substring("https://".length());
        }
        return "ws://" + endpoint.substring("http://".length());
    }

    private static String stripTrailingSlash(String value) {
        String endpoint = value == null ? "" : value.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String redactSensitiveJson(String body) {
        if (isBlank(body)) {
            return "<empty>";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            redactSensitiveFields(parsed);
            return GSON.toJson(parsed);
        } catch (RuntimeException ignored) {
            return "<non-json response>";
        }
    }

    private static void redactSensitiveFields(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                redactSensitiveFields(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        for (String key : new String[]{"token", "ticket", "api_key", "apiKey", "sdkToken", "bootstrapToken"}) {
            if (element.getAsJsonObject().has(key)) {
                element.getAsJsonObject().addProperty(key, "<redacted>");
            }
        }
        for (JsonElement child : element.getAsJsonObject().asMap().values()) {
            redactSensitiveFields(child);
        }
    }

    private void notifyLog(String message) { mainHandler.post(() -> listener.onLog(message)); }
    private void notifyConnecting() { int epoch = generation; mainHandler.post(() -> { if (current(epoch)) listener.onConnecting(); }); }
    private void notifySessionScopeChanged(JsonObject scope) {
        int epoch = generation;
        JsonObject snapshot = scope.deepCopy();
        mainHandler.post(() -> { if (current(epoch)) listener.onSessionScopeChanged(snapshot); });
    }
    private void notifyConnected(String id) { int epoch = generation; mainHandler.post(() -> { if (current(epoch)) listener.onConnected(id); }); }
    private void notifyDisconnected(String reason) { int epoch = generation; mainHandler.post(() -> { if (epoch == generation) listener.onDisconnected(reason); }); }
    private void notifyMessage(String payload) { int epoch = generation; mainHandler.post(() -> { if (current(epoch)) listener.onMessage(payload); }); }
    private void notifyError(Throwable error) { int epoch = generation; mainHandler.post(() -> { if (epoch == generation) listener.onError(error); }); }
}
