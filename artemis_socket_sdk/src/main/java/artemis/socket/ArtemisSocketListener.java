package artemis.socket;

/** Receives lifecycle, message, and error events from the Artemis socket. */
public interface ArtemisSocketListener {
    void onLog(String message);

    void onConnecting();

    void onConnected(String sessionId);

    void onDisconnected(String reason);

    void onMessage(String payload);

    void onError(Throwable error);

    /** Sanitized Runtime policy/branding from SDK init or refresh; never contains the SDK token. */
    default void onSessionScopeChanged(com.google.gson.JsonObject scope) { }

    /** Persisted history delivered only for the currently attached session. */
    default void onHistory(String sessionId, String messagesJson) { }
}
