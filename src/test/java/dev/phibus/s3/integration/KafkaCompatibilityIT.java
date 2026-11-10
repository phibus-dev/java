package dev.phibus.s3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.phibus.s3.kafka.KafkaConnectionService;
import dev.phibus.s3.kafka.KafkaProfileService;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.settings.VaultAuthService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
class KafkaCompatibilityIT {
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.9.1");

    @Test
    void applicationPropertiesDiscoverBrokerAndRoundTripMessage() throws Exception {
        KafkaProfileService profiles = mock(KafkaProfileService.class);
        KafkaProfileService.Profile profile = new KafkaProfileService.Profile(UUID.randomUUID(), "integration",
                KAFKA.getBootstrapServers(), "PLAINTEXT", "", "", "NONE", "", "", "", false,
                "", "rc18-events", "rc18-it", 10_000, Map.of(), true, Instant.now(), Instant.now());
        when(profiles.get(profile.id())).thenReturn(profile);
        KafkaConnectionService connections = new KafkaConnectionService(
                profiles, mock(SettingsService.class), mock(VaultAuthService.class));
        Properties common = connections.clientProperties(profile);

        try (AdminClient admin = AdminClient.create(common)) {
            admin.createTopics(List.of(new NewTopic(profile.defaultTopic(), 1, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }

        KafkaConnectionService.ClusterInfo cluster = connections.check(profile.id());
        assertThat(cluster.clusterId()).isNotBlank();
        assertThat(cluster.nodes()).isNotEmpty();
        assertThat(cluster.topics()).contains(profile.defaultTopic());

        Properties producerProperties = new Properties();
        producerProperties.putAll(common);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProperties)) {
            producer.send(new ProducerRecord<>(profile.defaultTopic(), "key".getBytes(StandardCharsets.UTF_8),
                    "rc18".getBytes(StandardCharsets.UTF_8))).get(20, TimeUnit.SECONDS);
        }

        Properties consumerProperties = new Properties();
        consumerProperties.putAll(common);
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "rc18-it-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(profile.defaultTopic()));
            String payload = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (payload == null && System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) payload = new String(records.iterator().next().value(), StandardCharsets.UTF_8);
            }
            assertThat(payload).isEqualTo("rc18");
        }
    }
}
