package dev.phibus.s3.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.S3ProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import dev.phibus.s3.test.TestRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfiguredCredentialProviderTest {
    @Test
    void agentUsesItsLocalConfigurationInsteadOfCoordinatorProfileId() {
        SettingsService settings = mock(SettingsService.class);
        BootstrapSecretCodec codec = mock(BootstrapSecretCodec.class);
        S3ProfileService profiles = mock(S3ProfileService.class);
        BootstrapSettings.S3ProfileSettings local = new BootstrapSettings.S3ProfileSettings(
                "agent", "http://local-s3", "us-east-1", "bucket", true,
                "MANUAL", "", "accessKey", "secretKey", "encrypted-access", "encrypted-secret");
        when(settings.load()).thenReturn(new BootstrapSettings(
                BootstrapSettings.PostgreSqlSettings.empty(), BootstrapSettings.VaultSettings.empty(), local));
        when(codec.decrypt("encrypted-access")).thenReturn("agent-access");
        when(codec.decrypt("encrypted-secret")).thenReturn("agent-secret");
        ConfiguredCredentialProvider provider = new ConfiguredCredentialProvider(settings, codec,
                new ObjectMapper(), mock(VaultAuthService.class), profiles, "AGENT");
        TestRequest request = new TestRequest("http://coordinator-s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 32, 8, 4, 1, false, "UPLOAD",
                "OBJECT_COUNT", 60, 0, "CUSTOM", java.util.Map.of(), 0, java.util.Map.of(), UUID.randomUUID());

        S3Credentials credentials = provider.resolve(request);

        assertEquals("agent-access", credentials.accessKey());
        assertEquals("agent-secret", credentials.secretKey());
        verifyNoInteractions(profiles);
    }
}
