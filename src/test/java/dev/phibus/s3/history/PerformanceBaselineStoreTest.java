package dev.phibus.s3.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PerformanceBaselineStoreTest {
    @Test
    void detectsSpeedRegression() {
        var baseline = metrics(100, 100, 120, 0);
        var current = metrics(85, 105, 125, 0);
        var report = PerformanceBaselineStore.RegressionReport.compare(current, baseline);
        assertEquals("REGRESSION", report.verdict());
        assertTrue(report.speedChangePercent() < -10);
    }

    @Test
    void detectsStableResultInsideThresholds() {
        var baseline = metrics(100, 100, 120, 0);
        var current = metrics(96, 108, 130, 0);
        assertEquals("STABLE", PerformanceBaselineStore.RegressionReport.compare(current, baseline).verdict());
    }

    private static PerformanceBaselineStore.RunMetrics metrics(double speed, double p95, double p99, int errors) {
        return new PerformanceBaselineStore.RunMetrics(UUID.randomUUID(), "http://s3", "bucket", "UPLOAD",
                Instant.now(), speed, p95, p99, errors, 10, 1024, 1000, false);
    }
}
