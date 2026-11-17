package dev.phibus.s3.quality;

import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.test.TestType;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class QualityGatePolicyCatalog {
    private static final List<TestType> ALL_TYPES = List.of(TestType.S3, TestType.CLICKHOUSE, TestType.KAFKA);
    private static final List<RunScope> ALL_SCOPES = List.of(RunScope.LOCAL, RunScope.DISTRIBUTED);
    private static final List<Policy> POLICIES = List.of(
            new Policy("SMOKE", "Smoke",
                    "Проверяет успешное завершение, наличие операций, участников и отсутствие ошибок.",
                    false, ALL_TYPES, ALL_SCOPES, List.of(RunStatus.COMPLETED),
                    new QualityGateThresholds(null, null, null, null, 0.0,
                            null, null, null, null, 1L, 1)),
            new Policy("LATENCY_GUARD", "Latency guard",
                    "Стартовый абсолютный SLO: p95 <= 5 s, p99 <= 10 s, error rate <= 1%. "
                            + "Пороговые значения следует адаптировать к операции через overrides.",
                    false, ALL_TYPES, ALL_SCOPES,
                    List.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS),
                    new QualityGateThresholds(null, null, 5_000.0, 10_000.0, 1.0,
                            null, null, null, null, 1L, 1)),
            new Policy("BASELINE_TOLERANT", "Baseline tolerant",
                    "Допускает падение throughput до 15%, рост p95 до 20%, p99 до 25% и error rate до 1%.",
                    true, ALL_TYPES, ALL_SCOPES,
                    List.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS),
                    new QualityGateThresholds(null, null, null, null, 1.0,
                            15.0, 15.0, 20.0, 25.0, 1L, 1)),
            new Policy("BASELINE_STRICT", "Baseline strict",
                    "Требует COMPLETED без ошибок; throughput не хуже baseline более чем на 5%, "
                            + "p95 и p99 — не хуже более чем на 10%.",
                    true, ALL_TYPES, ALL_SCOPES, List.of(RunStatus.COMPLETED),
                    new QualityGateThresholds(null, null, null, null, 0.0,
                            5.0, 5.0, 10.0, 10.0, 1L, 1))
    );

    public List<Policy> list() {
        return POLICIES;
    }

    public Policy get(String suppliedId) {
        String id = suppliedId == null || suppliedId.isBlank()
                ? "SMOKE" : suppliedId.trim().toUpperCase(Locale.ROOT);
        return POLICIES.stream().filter(policy -> policy.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown quality gate policy: " + suppliedId));
    }

    public record Policy(String id, String name, String description, boolean baselineRequired,
                         List<TestType> supportedTestTypes, List<RunScope> supportedScopes,
                         List<RunStatus> acceptedStatuses, QualityGateThresholds thresholds) { }
}
