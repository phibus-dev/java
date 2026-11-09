package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaM3ExceptionHandlerTest {
    private final KafkaM3ExceptionHandler handler = new KafkaM3ExceptionHandler();

    @Test
    void exposesRootCauseForKafkaM3StartFailure() {
        var response = handler.unavailable(new IllegalStateException("wrapper",
                new RuntimeException("kafka_m3_run is unavailable")));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).containsEntry("message", "kafka_m3_run is unavailable");
    }

    @Test
    void returnsBadRequestForInvalidKafkaM3Input() {
        var response = handler.badRequest(new IllegalArgumentException("Kafka topic is required"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "Kafka topic is required");
    }
}
