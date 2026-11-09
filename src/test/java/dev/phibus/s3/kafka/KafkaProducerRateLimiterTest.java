package dev.phibus.s3.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KafkaProducerRateLimiterTest {
    @Test
    void targetRateIsGlobalAndDoesNotDependOnWorkerCount() {
        KafkaProducerRunService.GlobalRateLimiter limiter =
                new KafkaProducerRunService.GlobalRateLimiter(100);

        assertEquals(10_000_000L, limiter.intervalNanos());
    }

    @Test
    void zeroTargetDisablesPacing() {
        assertEquals(0, new KafkaProducerRunService.GlobalRateLimiter(0).intervalNanos());
    }
}
