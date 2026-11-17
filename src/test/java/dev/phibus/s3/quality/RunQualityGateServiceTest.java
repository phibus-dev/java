package dev.phibus.s3.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.RunStopReason;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.quality.RunQualityGateService.QualityGateRequest;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunQualityGateServiceTest {
    private final UnifiedRunHistoryStore history = mock(UnifiedRunHistoryStore.class);
    private final RunQualityGateService service =
            new RunQualityGateService(history, new QualityGatePolicyCatalog());

    @Test
    void passesTolerantComparisonWithinEveryBoundary() {
        UUID baselineId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(history.get(baselineId)).thenReturn(row(baselineId, TestType.S3, RunScope.LOCAL,
                RunStatus.COMPLETED, "UPLOAD", 100, 100, 100, 200, 0, 1));
        when(history.get(runId)).thenReturn(row(runId, TestType.S3, RunScope.LOCAL,
                RunStatus.COMPLETED, "upload", 86, 85, 119, 249, 0, 1));

        var result = service.evaluate(runId,
                new QualityGateRequest("baseline_tolerant", baselineId, null));

        assertThat(result.passed()).isTrue();
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.criteria()).allMatch(RunQualityGateService.Criterion::passed);
    }

    @Test
    void reportsAllRegressionsInsteadOfFailingFast() {
        UUID baselineId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(history.get(baselineId)).thenReturn(row(baselineId, TestType.KAFKA, RunScope.DISTRIBUTED,
                RunStatus.COMPLETED, "PRODUCE", 100, 100, 100, 100, 0, 2));
        when(history.get(runId)).thenReturn(row(runId, TestType.KAFKA, RunScope.DISTRIBUTED,
                RunStatus.COMPLETED_WITH_ERRORS, "PRODUCE", 70, 70, 140, 150, 5, 2));

        var result = service.evaluate(runId,
                new QualityGateRequest("BASELINE_TOLERANT", baselineId, null));

        assertThat(result.passed()).isFalse();
        assertThat(result.criteria().stream().filter(item -> !item.passed()).map(item -> item.id()))
                .contains("errorRatePercent", "operationsPerSecondVsBaseline",
                        "throughputMiBpsVsBaseline", "p99LatencyMsVsBaseline");
    }

    @Test
    void appliesAbsoluteOverridesWithoutABaseline() {
        UUID runId = UUID.randomUUID();
        when(history.get(runId)).thenReturn(row(runId, TestType.CLICKHOUSE, RunScope.LOCAL,
                RunStatus.COMPLETED, "SELECT", 50, 40, 90, 120, 0, 1));
        var overrides = new QualityGateThresholds(49.0, 39.0, 100.0, 150.0, 0.0,
                null, null, null, null, 10L, 1);

        var result = service.evaluate(runId, new QualityGateRequest("SMOKE", null, overrides));

        assertThat(result.passed()).isTrue();
        assertThat(result.criteria()).extracting(RunQualityGateService.Criterion::id)
                .contains("operationsPerSecond", "throughputMiBps", "p95LatencyMs", "p99LatencyMs");
    }

    @Test
    void requiresACompatibleBaselineForRelativePolicies() {
        UUID runId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        when(history.get(runId)).thenReturn(row(runId, TestType.S3, RunScope.LOCAL,
                RunStatus.COMPLETED, "UPLOAD", 10, 10, 10, 10, 0, 1));
        when(history.get(baselineId)).thenReturn(row(baselineId, TestType.S3, RunScope.LOCAL,
                RunStatus.COMPLETED, "DOWNLOAD", 10, 10, 10, 10, 0, 1));

        assertThatThrownBy(() -> service.evaluate(runId,
                new QualityGateRequest("BASELINE_STRICT", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baselineRunId");
        assertThatThrownBy(() -> service.evaluate(runId,
                new QualityGateRequest("BASELINE_STRICT", baselineId, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same testType, scope and operation");
    }

    private static UnifiedRunHistoryStore.RunHistoryRow row(
            UUID id, TestType type, RunScope scope, RunStatus status, String operation,
            double operationsPerSecond, double throughputMiBps, double p95, double p99,
            long errors, int participants) {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        return new UnifiedRunHistoryStore.RunHistoryRow(id, type, scope, status, now, now, now,
                "benchmark", operation, "target", "ci", 100, 0, 1024,
                operationsPerSecond, throughputMiBps, p95 / 2, p95, p99, errors,
                RunStopReason.NORMAL, "done", participants, "operation", null, true);
    }
}
