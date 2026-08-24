package dev.phibus.s3.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;

class KafkaConnectionServiceTest {
    @Test
    void plaintextProfileBuildsBasicKafkaProperties() {
        KafkaProfileService profiles = mock(KafkaProfileService.class);
        KafkaConnectionService service = new KafkaConnectionService(profiles, mock(SettingsService.class), mock(VaultAuthService.class));
        KafkaProfileService.Profile profile = new KafkaProfileService.Profile(UUID.randomUUID(), "test",
                "broker-1:9092,broker-2:9092", "PLAINTEXT", null, null, "NONE", null,
                "password", null, false, null, "load-test", "evo-snt", true, Instant.now(), Instant.now());

        Properties properties = service.clientProperties(profile);

        assertEquals("broker-1:9092,broker-2:9092", properties.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("PLAINTEXT", properties.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("evo-snt-admin", properties.get(AdminClientConfig.CLIENT_ID_CONFIG));
        assertFalse(properties.containsKey(SaslConfigs.SASL_JAAS_CONFIG));
    }
}
