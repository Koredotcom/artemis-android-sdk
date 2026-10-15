package artemis.socket;

import java.io.IOException;
import java.io.InputStream;

/** Opened and closed on the SDK worker, so content providers never block the UI. */
public interface ArtemisAttachmentSource {
    InputStream open() throws IOException;
}
