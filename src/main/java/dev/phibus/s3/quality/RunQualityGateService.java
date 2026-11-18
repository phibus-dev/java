package dev.phibus.s3.quality;

import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.quality.QualityGatePolicyCatalog.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class RunQualityGateService {
    private final UnifiedRunHistoryStore history;
    private final QualityGatePolicyCatalog policies;

    public RunQualityGateService(UnifiedRunHistoryStore history, QualityGatePolicyCatalog policies) {
        this.history = history;
        this.policies = policies;
    }

    public QualityGateResult evaluate(UUID runId, QualityGateRequest suppliedRequest) {
        if (runId == null) throw new IllegalArgumentException("Run id is required");
        QualityGateRequest request = suppliedRequest == null
                ? new QualityGateRequest(null, null, null) : suppliedRequest;
        UnifiedRunHistoryStore.RunHistoryRow current = requireRun(runId);
        Policy policy = policies.get(request.policyId());
        requireSupported(current, policy);

        QualityGateThresholds thresholds = policy.thresholds()
                .mergedWith(request.overrides()).validated();
        boolean relativeThresholds = hasRelativeThresholds(thresholds);
        UUID baselineId = request.baselineRunId();
        if (baselineId == null && (policy.baselineRequired() || relativeThresholds)) {
            throw new IllegalArgumentException("Policy " + policy.id() + " requires baselineRunId");
        }
        if (runId.equals(baselineId)) {
            throw new IllegalArgumentException("A run cannot be compared with itself");
        }

        UnifiedRunHistoryStore.RunHistoryRow baseline = baselineId == null ? null : requireRun(baselineId);
        if (baseline != null) requireComparable(current, baseline);

        List<Criterion> criteria = new ArrayList<>();
        criteria.add(textCriterion("status", policy.acceptedStatuses().contains(current.status()),
                current.status().name(), "IN", policy.acceptedStatuses().toString(),
                "Статус запуска должен соответствовать политике"));

        addMinimum(criteria, "completedOperations", current.completedOperations(),
                thresholds.minCompletedOperations(), "operations", "Минимальное число завершённых операций");
        addMinimum(criteria, "participants", current.participants(),
                thresholds.minParticipants(), "participants", "Минимальное число участников");
        addMinimum(criteria, "operationsPerSecond", current.operationsPerSecond(),
                thresholds.minOperationsPerSecond(), "ops/s", "Минимальная интенсивность операций");
        addMinimum(criteria, "throughputMiBps", current.throughputMiBps(),
                thresholds.minThroughputMiBps(), "MiB/s", "Минимальная пропускная способность");
        addMaximum(criteria, "p95LatencyMs", current.p95LatencyMs(),
                thresholds.maxP95LatencyMs(), "ms", "Максимальная задержка p95");
        addMaximum(criteria, "p99LatencyMs", current.p99LatencyMs(),
                thresholds.maxP99LatencyMs(), "ms", "Максимальная задержка p99");

        double errorRate = errorRate(current);
        addMaximum(criteria, "errorRatePercent", errorRate,
                thresholds.maxErrorRatePercent(), "%", "Максимальная доля ошибок");

        if (baseline != null) {
            addBaselineMinimum(criteria, "operationsPerSecondVsBaseline", current.operationsPerSecond(),
                    baseline.operationsPerSecond(), thresholds.maxOperationsDropPercent(), "ops/s",
                    "Допустимое снижение operations/sec относительно baseline");
            addBaselineMinimum(criteria, "throughputMiBpsVsBaseline", current.throughputMiBps(),
                    baseline.throughputMiBps(), thresholds.maxThroughputDropPercent(), "MiB/s",
                    "Допустимое снижение MiB/s относительно baseline");
            addBaselineMaximum(criteria, "p95LatencyMsVsBaseline", current.p95LatencyMs(),
                    baseline.p95LatencyMs(), thresholds.maxP95IncreasePercent(), "ms",
                    "Допустимый рост p95 относительно baseline");
            addBaselineMaximum(criteria, "p99LatencyMsVsBaseline", current.p99LatencyMs(),
                    baseline.p99LatencyMs(), thresholds.maxP99IncreasePercent(), "ms",
                    "Допустимый рост p99 относительно baseline");
        }

        boolean passed = criteria.stream().allMatch(Criterion::passed);
        return new QualityGateResult(current.id(), baselineId, policy.id(),
                passed ? "PASS" : "FAIL", Instant.now(), current.testType(), current.scope(),
                current.status(), errorRate, thresholds, List.copyOf(criteria));
    }

    private UnifiedRunHistoryStore.RunHistoryRow requireRun(UUID id) {
        UnifiedRunHistoryStore.RunHistoryRow run = history.get(id);
        if (run == null) throw new UnifiedRunRerunService.RunHistoryNotFoundException(id);
        return run;
    }

    private static void requireSupported(UnifiedRunHistoryStore.RunHistoryRow run, Policy policy) {
        if (!policy.supportedTestTypes().contains(run.testType())
                || !policy.supportedScopes().contains(run.scope())) {
            throw new IllegalArgumentException("Policy " + policy.id() + " does not support "
                    + run.testType() + " / " + run.scope());
        }
    }

    private static void requireComparable(UnifiedRunHistoryStore.RunHistoryRow current,
                                          UnifiedRunHistoryStore.RunHistoryRow baseline) {
        if (!baseline.status().terminal()) {
            throw new IllegalArgumentException("Baseline run must be terminal: " + baseline.id());
        }
        if (current.testType() != baseline.testType() || current.scope() != baseline.scope()
                || !Objects.equals(normalize(current.operation()), normalize(baseline.operation()))) {
            throw new IllegalArgumentException(
                    "Baseline must have the same testType, scope and operation as the evaluated run");
        }
    }

    private static boolean hasRelativeThresholds(QualityGateThresholds thresholds) {
        return thresholds.maxOperationsDropPercent() != null
                || thresholds.maxThroughputDropPercent() != null
                || thresholds.maxP95IncreasePercent() != null
                || thresholds.maxP99IncreasePercent() != null;
    }

    private static double errorRate(UnifiedRunHistoryStore.RunHistoryRow run) {
        double total = (double) run.completedOperations() + run.errors();
        return total <= 0 ? 0 : run.errors() * 100.0 / total;
    }

    private static void addMinimum(List<Criterion> result, String id, double actual,
                                   Number minimum, String unit, String detail) {
        if (minimum == null) return;
        double threshold = minimum.doubleValue();
        result.add(numberCriterion(id, actual >= threshold, actual, ">=", threshold, unit, detail));
    }

    private static void addMaximum(List<Criterion> result, String id, double actual,
                                   Number maximum, String unit, String detail) {
        if (maximum == null) return;
        double threshold = maximum.doubleValue();
        result.add(numberCriterion(id, actual <= threshold, actual, "<=", threshold, unit, detail));
    }

    private static void addBaselineMinimum(List<Criterion> result, String id, double actual,
                                           double baseline, Double allowedDropPercent,
                                           String unit, String detail) {
        if (allowedDropPercent == null) return;
        double threshold = baseline * (1.0 - allowedDropPercent / 100.0);
        result.add(numberCriterion(id, actual >= threshold, actual, ">=", threshold, unit,
                detail + "; baseline=" + number(baseline)));
    }

    private static void addBaselineMaximum(List<Criterion> result, String id, double actual,
                                           double baseline, Double allowedIncreasePercent,
                                           String unit, String detail) {
        if (allowedIncreasePercent == null) return;
        double threshold = baseline * (1.0 + allowedIncreasePercent / 100.0);
        result.add(numberCriterion(id, actual <= threshold, actual, "<=", threshold, unit,
                detail + "; baseline=" + number(baseline)));
    }

    private static Criterion numberCriterion(String id, boolean passed, double actual,
                                              String operator, double expected, String unit, String detail) {
        return new Criterion(id, passed, number(actual), operator, number(expected), unit, detail);
    }

    private static Criterion textCriterion(String id, boolean passed, String actual,
                                            String operator, String expected, String detail) {
        return new Criterion(id, passed, actual, operator, expected, "", detail);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record QualityGateRequest(String policyId, UUID baselineRunId,
                                     QualityGateThresholds overrides) { }

    public record QualityGateResult(UUID runId, UUID baselineRunId, String policyId, String verdict,
                                    Instant evaluatedAt, dev.phibus.s3.test.TestType testType,
                                    dev.phibus.s3.lifecycle.RunScope scope, RunStatus runStatus,
                                    double errorRatePercent, QualityGateThresholds effectiveThresholds,
                                    List<Criterion> criteria) {
        public boolean passed() {
            return "PASS".equals(verdict);
        }

        public boolean isPassed() {
            return passed();
        }
    }

    public record Criterion(String id, boolean passed, String actual, String operator,
                            String expected, String unit, String detail) { }
}
