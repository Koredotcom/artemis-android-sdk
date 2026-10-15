package artemis.socket;

/** Scoped upload result. Callbacks run on the client's callback executor (Android main thread). */
public interface ArtemisAttachmentCallback {
    /** File bytes sent, excluding multipart framing; totalBytes is -1 when unknown. */
    default void onProgress(long uploadedBytes, long totalBytes) { }
    void onSuccess(String attachmentId, String sessionId);
    void onFailure(String code, String message);
}
