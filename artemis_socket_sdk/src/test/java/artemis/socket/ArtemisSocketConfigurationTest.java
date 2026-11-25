package artemis.socket;
import org.junit.Test;
import static org.junit.Assert.*;
public class ArtemisSocketConfigurationTest {
    @Test public void legacyPublicKeyConfigurationKeepsDefaults() {
        ArtemisSocketConfiguration c = ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com")
            .projectId("project").apiKey("public-key").channelId("web").build();
        assertEquals("public-key", c.getApiKey());
        assertEquals(5, c.getMaxReconnectAttempts());
        assertEquals(1000, c.getReconnectBaseDelayMs());
        assertEquals(30000, c.getReconnectMaxDelayMs());
        assertFalse(c.isUnlimitedTransientReconnect());
        assertEquals(0, c.getIdleTimeoutMs());
    }
    @Test public void channelNameStillSupported() {
        assertEquals("mobile", ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com")
            .projectId("p").apiKey("pk").channelName("mobile").build().getChannelName());
    }
    @Test(expected=IllegalArgumentException.class) public void conflictingCredentialsRejected() {
        ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com").projectId("p")
            .apiKey("pk").bootstrapToken("bt").channelName("mobile").build();
    }
    @Test public void hostedBootstrapNeedsNoClientChannel() {
        assertEquals("bt", ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com")
            .projectId("p").bootstrapToken("bt").build().getBootstrapToken());
    }
    @Test public void initialBootstrapTokenMayPairWithProvider() {
        ArtemisSocketConfiguration c = ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com")
            .projectId("p").bootstrapToken("bt").bootstrapTokenProvider(() -> "fresh").build();
        assertEquals("bt", c.getBootstrapToken());
        assertNotNull(c.getBootstrapTokenProvider());
    }
    @Test(expected=IllegalArgumentException.class) public void apiKeyCannotPairWithProvider() {
        ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com").projectId("p")
            .apiKey("pk").bootstrapTokenProvider(() -> "fresh").channelName("mobile").build();
    }
    @Test(expected=IllegalArgumentException.class) public void someCredentialIsRequired() {
        ArtemisSocketConfiguration.builder().endpoint("https://runtime.example.com").projectId("p").build();
    }
}
