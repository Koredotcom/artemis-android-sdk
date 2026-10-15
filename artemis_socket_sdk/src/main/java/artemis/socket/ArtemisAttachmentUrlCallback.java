package artemis.socket;

/** Receives a fresh download URL; SDK credentials are never supplied to the browser. */
public interface ArtemisAttachmentUrlCallback {
    void onSuccess(String url);
    void onFailure(String code, String message);
}
