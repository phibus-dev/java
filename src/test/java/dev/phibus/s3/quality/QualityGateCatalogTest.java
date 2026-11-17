package dev.phibus.s3.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.phibus.s3.test.TestType;
import org.junit.jupiter.api.Test;

class QualityGateCatalogTest {
    @Test
    void catalogsCoverEveryEngineAndDefineStablePolicies() {
        var policies = new QualityGatePolicyCatalog();
        var scenarios = new BenchmarkScenarioCatalog();

        assertThat(policies.list()).extracting(QualityGatePolicyCatalog.Policy::id)
                .containsExactly("SMOKE", "LATENCY_GUARD", "BASELINE_TOLERANT", "BASELINE_STRICT");
        assertThat(scenarios.list(TestType.S3)).hasSize(2);
        assertThat(scenarios.list(TestType.CLICKHOUSE)).hasSize(2);
        assertThat(scenarios.list(TestType.KAFKA)).hasSize(2);
        assertThat(scenarios.get("s3_upload_throughput").recommendedPolicyId())
                .isEqualTo("BASELINE_TOLERANT");
        assertThat(scenarios.get("S3_MIXED_STEADY").parameters())
                .containsEntry("workloadProfile", "ETL");
    }

    @Test
    void validatesThresholdRangesAndMergesOnlyExplicitOverrides() {
        var defaults = new QualityGateThresholds(null, null, 5_000.0, 10_000.0, 1.0,
                null, null, null, null, 1L, 1);
        var overrides = new QualityGateThresholds(null, 50.0, 2_000.0, null, null,
                null, null, null, null, null, null);

        var merged = defaults.mergedWith(overrides).validated();

        assertThat(merged.minThroughputMiBps()).isEqualTo(50.0);
        assertThat(merged.maxP95LatencyMs()).isEqualTo(2_000.0);
        assertThat(merged.maxP99LatencyMs()).isEqualTo(10_000.0);
        assertThatThrownBy(() -> new QualityGateThresholds(null, null, null, null, 101.0,
                null, null, null, null, null, null).validated())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }
}
