package artemis.socket;

import com.google.gson.JsonObject;
import java.net.URI;

/** Configuration for the Artemis API-key socket flow. */
public final class ArtemisSocketConfiguration {
    private final String endpoint;
    private final String projectId;
    private final String apiKey;
    private final String channelId;
    private final String channelName;
    private final boolean reconnectionEnabled;
    private final int maxReconnectAttempts;
    private final long reconnectBaseDelayMs;
    private final long reconnectMaxDelayMs;
    private final String bootstrapToken;
    private final BootstrapTokenProvider bootstrapTokenProvider;
    private final String deploymentSlug;
    private final String clientSessionIdentifier;
    private final JsonObject userContext;
    private final JsonObject sessionMetadata;
    private final boolean unlimitedTransientReconnect;
    private final boolean newSessionOnConnect;
    private final long idleTimeoutMs;
    private final boolean endSessionOnIdle;
    private final long staleSessionRefreshMs;

    public interface BootstrapTokenProvider { String getToken() throws Exception; }

    private ArtemisSocketConfiguration(Builder builder) {
        endpoint = builder.endpoint;
        projectId = builder.projectId;
        apiKey = builder.apiKey;
        channelId = builder.channelId;
        channelName = builder.channelName;
        reconnectionEnabled = builder.reconnectionEnabled;
        maxReconnectAttempts = builder.maxReconnectAttempts;
        reconnectBaseDelayMs = builder.reconnectBaseDelayMs;
        reconnectMaxDelayMs = builder.reconnectMaxDelayMs;
        bootstrapToken = builder.bootstrapToken;
        bootstrapTokenProvider = builder.bootstrapTokenProvider;
        deploymentSlug = builder.deploymentSlug;
        clientSessionIdentifier = builder.clientSessionIdentifier;
        userContext = copy(builder.userContext);
        sessionMetadata = copy(builder.sessionMetadata);
        unlimitedTransientReconnect = builder.unlimitedTransientReconnect;
        newSessionOnConnect = builder.newSessionOnConnect;
        idleTimeoutMs = builder.idleTimeoutMs;
        endSessionOnIdle = builder.endSessionOnIdle;
        staleSessionRefreshMs = builder.staleSessionRefreshMs;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public boolean isReconnectionEnabled() {
        return reconnectionEnabled;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public long getReconnectBaseDelayMs() {
        return reconnectBaseDelayMs;
    }

    public long getReconnectMaxDelayMs() {
        return reconnectMaxDelayMs;
    }

    public String getBootstrapToken() { return bootstrapToken; }
    public BootstrapTokenProvider getBootstrapTokenProvider() { return bootstrapTokenProvider; }
    public String getDeploymentSlug() { return deploymentSlug; }
    public String getClientSessionIdentifier() { return clientSessionIdentifier; }
    public JsonObject getUserContext() { return copy(userContext); }
    public JsonObject getSessionMetadata() { return copy(sessionMetadata); }
    public boolean isNewSessionOnConnect() { return newSessionOnConnect; }
    public boolean isUnlimitedTransientReconnect() { return unlimitedTransientReconnect; }
    public long getIdleTimeoutMs() { return idleTimeoutMs; }
    public boolean isEndSessionOnIdle() { return endSessionOnIdle; }
    public long getStaleSessionRefreshMs() { return staleSessionRefreshMs; }
    private static JsonObject copy(JsonObject value) { return value == null ? null : value.deepCopy(); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String endpoint = "";
        private String projectId = "";
        private String apiKey = "";
        private String channelId = "";
        private String channelName = "";
        private boolean reconnectionEnabled = true;
        private int maxReconnectAttempts = 5;
        private long reconnectBaseDelayMs = 1000L;
        private long reconnectMaxDelayMs = 30000L;
        private String bootstrapToken;
        private BootstrapTokenProvider bootstrapTokenProvider;
        private String deploymentSlug;
        private String clientSessionIdentifier;
        private JsonObject userContext;
        private JsonObject sessionMetadata;
        private boolean unlimitedTransientReconnect;
        private boolean newSessionOnConnect;
        private long idleTimeoutMs;
        private boolean endSessionOnIdle;
        private long staleSessionRefreshMs;

        public Builder bootstrapToken(String value) { bootstrapToken = value; return this; }
        public Builder bootstrapTokenProvider(BootstrapTokenProvider value) { bootstrapTokenProvider = value; return this; }
        public Builder deploymentSlug(String value) { deploymentSlug = value; return this; }
        public Builder clientSessionIdentifier(String value) { clientSessionIdentifier = value; return this; }
        public Builder userContext(JsonObject value) { userContext = copy(value); return this; }
        public Builder sessionMetadata(JsonObject value) { sessionMetadata = copy(value); return this; }
        /** Opt in to Web retry policy; the legacy attempt limit remains the default. */
        public Builder newSessionOnConnect(boolean value) { newSessionOnConnect = value; return this; }
        public Builder unlimitedTransientReconnect(boolean value) { unlimitedTransientReconnect = value; return this; }
        public Builder idleDisconnect(long timeoutMs, boolean endSession) {
            idleTimeoutMs = timeoutMs; endSessionOnIdle = endSession; return this;
        }
        public Builder staleSessionRefresh(long timeoutMs) { staleSessionRefreshMs = timeoutMs; return this; }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public Builder channelName(String channelName) {
            this.channelName = channelName;
            return this;
        }

        public Builder reconnection(boolean enabled, int maxAttempts, long baseDelayMs, long maxDelayMs) {
            reconnectionEnabled = enabled;
            maxReconnectAttempts = maxAttempts;
            reconnectBaseDelayMs = baseDelayMs;
            reconnectMaxDelayMs = maxDelayMs;
            return this;
        }

        public ArtemisSocketConfiguration build() {
            require(endpoint, "endpoint");
            require(projectId, "project_id");
            int credentials = (isBlank(apiKey) ? 0 : 1) + (isBlank(bootstrapToken) ? 0 : 1) + (bootstrapTokenProvider == null ? 0 : 1);
            if (credentials != 1) throw new IllegalArgumentException("Exactly one API key, bootstrap token or provider is required");
            if (!isBlank(apiKey) && isBlank(channelId) && isBlank(channelName) && isBlank(deploymentSlug)) {
                throw new IllegalArgumentException("channel_id or channel_name is required");
            }
            if (isBlank(apiKey) && (!isBlank(channelId) || !isBlank(channelName) || !isBlank(deploymentSlug)
                    || userContext != null || !isBlank(clientSessionIdentifier))) {
                throw new IllegalArgumentException("Bootstrap token owns the channel and user context");
            }
            URI uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())
                    || "ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) or WS(S) URL without credentials, query or fragment");
            }
            if (idleTimeoutMs < 0 || staleSessionRefreshMs < 0) throw new IllegalArgumentException("Timeouts must be non-negative");
            if (maxReconnectAttempts < 0 || reconnectBaseDelayMs < 0 || reconnectMaxDelayMs < reconnectBaseDelayMs) {
                throw new IllegalArgumentException("Invalid reconnection configuration");
            }
            validateUserContext(userContext);
            return new ArtemisSocketConfiguration(this);
        }

        private static void validateUserContext(JsonObject context) {
            if (context == null) return;
            if (context.has("userId") && !context.get("userId").isJsonNull()) {
                if (!context.get("userId").isJsonPrimitive() || !context.get("userId").getAsJsonPrimitive().isString()
                        || context.get("userId").getAsString().trim().length() > 200) throw new IllegalArgumentException("Invalid userContext.userId");
            }
            com.google.gson.JsonElement attributes = context.get("customAttributes");
            if (attributes != null && !attributes.isJsonNull()) {
                if (!attributes.isJsonObject() || attributes.getAsJsonObject().size() > 32) throw new IllegalArgumentException("Invalid userContext.customAttributes");
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : attributes.getAsJsonObject().entrySet()) {
                    if (entry.getKey().isEmpty() || entry.getKey().length() > 128) throw new IllegalArgumentException("Invalid user context attribute key");
                    com.google.gson.JsonElement value = entry.getValue();
                    if (value.isJsonArray()) {
                        if (value.getAsJsonArray().size() > 16) throw new IllegalArgumentException("User attribute arrays must contain at most 16 items");
                        for (com.google.gson.JsonElement item : value.getAsJsonArray()) validatePrimitive(item);
                    } else validatePrimitive(value);
                }
            }
            if (context.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("User context exceeds 4096 bytes");
        }

        private static void validatePrimitive(com.google.gson.JsonElement value) {
            if (value.isJsonNull()) return;
            if (!value.isJsonPrimitive()) throw new IllegalArgumentException("User attributes must be primitives or primitive arrays");
            com.google.gson.JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() && primitive.getAsString().length() > 512 || primitive.isNumber() && !Double.isFinite(primitive.getAsDouble()))
                throw new IllegalArgumentException("Invalid user attribute value");
        }

        private static void require(String value, String name) {
            if (isBlank(value)) {
                throw new IllegalArgumentException(name + " is required");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
