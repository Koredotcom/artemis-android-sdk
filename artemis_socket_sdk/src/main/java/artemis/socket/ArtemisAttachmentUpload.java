package artemis.socket;

/** Cancels one upload without disconnecting chat or cancelling token refresh. */
public interface ArtemisAttachmentUpload extends AutoCloseable {
    void cancel();
    boolean isDone();
    @Override default void close() { cancel(); }
}
