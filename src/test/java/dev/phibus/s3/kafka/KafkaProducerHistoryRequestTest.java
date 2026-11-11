package dev.phibus.s3.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class KafkaProducerHistoryRequestTest {
    @Test
    void restoresProducerConfigurationFromHistory() {
        UUID profileId = UUID.randomUUID();

        KafkaProducerRunService.ProducerRequest request = KafkaProducerRunService.requestFromHistory(
                profileId, "events", 2048, 50_000, 8,
                "acks=1;compression=zstd;batchSize=32768;lingerMs=12;idempotence=false;"
                        + "targetMessagesPerSec=9000;maxInFlightMessages=1234");

        assertThat(request.profileId()).isEqualTo(profileId);
        assertThat(request.topic()).isEqualTo("events");
        assertThat(request.messageSizeBytes()).isEqualTo(2048);
        assertThat(request.messageCount()).isEqualTo(50_000);
        assertThat(request.producerThreads()).isEqualTo(8);
        assertThat(request.acks()).isEqualTo("1");
        assertThat(request.compressionType()).isEqualTo("zstd");
        assertThat(request.batchSize()).isEqualTo(32_768);
        assertThat(request.lingerMs()).isEqualTo(12);
        assertThat(request.enableIdempotence()).isFalse();
        assertThat(request.targetMessagesPerSec()).isEqualTo(9_000);
        assertThat(request.maxInFlightMessages()).isEqualTo(1_234);
    }

    @Test
    void usesSafeDefaultsForLegacyOrMalformedConfiguration() {
        KafkaProducerRunService.ProducerRequest request = KafkaProducerRunService.requestFromHistory(
                UUID.randomUUID(), "legacy", 100, 10, 1,
                "batchSize=invalid;lingerMs=invalid;idempotence=invalid");

        assertThat(request.acks()).isEqualTo("all");
        assertThat(request.compressionType()).isEqualTo("none");
        assertThat(request.batchSize()).isEqualTo(16_384);
        assertThat(request.lingerMs()).isEqualTo(5);
        assertThat(request.enableIdempotence()).isTrue();
        assertThat(request.targetMessagesPerSec()).isZero();
        assertThat(request.maxInFlightMessages()).isEqualTo(10_000);
    }
}
