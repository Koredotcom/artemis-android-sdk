package com.artemis.uisdk;

import org.junit.Test;
import static org.junit.Assert.*;

/** Compile old Java entry points as part of the regression gate. */
public class LegacyApiCompatibilityTest {
    @Test public void existingConfigurationConstructorsNeedNoNewArguments() {
        ArtemisFeatureConfig features = new ArtemisFeatureConfig(true, true, true, true, true, true, true);
        ArtemisConnectionConfig connection = new ArtemisConnectionConfig("https://runtime.example.com", "project", "pk", null, "mobile");
        ArtemisUIConfig config = new ArtemisUIConfig(connection, new ArtemisReconnectionConfig(), features,
            new ArtemisThemeConfig(), new ArtemisSessionConfig());
        assertFalse(config.getFeatures().getAttachments());
        assertFalse(config.getFeatures().getContactCenter());
        assertEquals(5, config.getReconnection().getMaxAttempts());
    }
}
