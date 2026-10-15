package artemis.socket;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import okio.Buffer;
import okio.ByteString;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** An intercepted Runtime behind the production socket client. No request reaches the network. */
public final class UiSocketFixture {
    public final List<String> frames = new CopyOnWriteArrayList<>();
    public volatile ArtemisSocketClient client;
    public volatile WebSocketListener socketListener;
    public volatile WebSocket socket;
    public volatile int uploadStatus = 200;
    public volatile boolean uniqueUploadIds = false;
    private final java.util.concurrent.atomic.AtomicInteger uploadSequence = new java.util.concurrent.atomic.AtomicInteger();
    public volatile String uploadResponse = "{\"attachmentId\":\"uploaded-file\"}";
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public ArtemisSocketClient create(ArtemisSocketConfiguration config, ArtemisSocketListener listener) {
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request(); String path = request.url().encodedPath();
            if (request.body() != null) request.body().writeTo(new Buffer());
            requests.add(path);
            String body = path.endsWith("/init") || path.endsWith("/refresh") ? "{\"token\":\"token\",\"expiresIn\":3600,\"projectId\":\"project\",\"channelId\":\"mobile\",\"permissions\":[\"attachment:write\",\"attachment:read\"],\"widgetConfig\":{\"theme\":{\"attachmentButtonEnabled\":true}}}"
                : path.endsWith("/ws-ticket") ? "{\"ticket\":\"ticket\"}"
                : path.endsWith("/attachments") ? (uniqueUploadIds ? "{\"attachmentId\":\"uploaded-file-" + uploadSequence.incrementAndGet() + "\"}" : uploadResponse)
                : path.endsWith("/url") ? "{\"url\":\"https://files.example.com/download\"}"
                : "{\"messages\":[],\"hasMore\":false}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(path.endsWith("/attachments") ? uploadStatus : 200).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        Handler main = new Handler(Looper.getMainLooper());
        client = new ArtemisSocketClient(config, listener, http, (request, wireListener) -> {
            WebSocket fake = new WebSocket() {
                public Request request() { return request; }
                public long queueSize() { return 0; }
                public boolean send(String text) { frames.add(text); return true; }
                public boolean send(ByteString bytes) { return true; }
                public boolean close(int code, String reason) { return true; }
                public void cancel() { }
            };
            socket = fake; socketListener = wireListener; return fake;
        }, task -> main.post(task), Executors.newSingleThreadScheduledExecutor());
        return client;
    }
    public void receive(String frame) { socketListener.onMessage(socket, frame); }
    public JsonObject lastFrame() { return JsonParser.parseString(frames.get(frames.size() - 1)).getAsJsonObject(); }
}
