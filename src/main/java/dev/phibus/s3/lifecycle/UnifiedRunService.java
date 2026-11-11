package dev.phibus.s3.lifecycle;

import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class UnifiedRunService {
    private static final int MAX_LIMIT = 500;
    private static final Comparator<RunLifecycleView> NEWEST_FIRST = Comparator.comparing(
            RunLifecycleView::createdAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final TestRunService s3Runs;
    private final ClickHouseTestRunService clickHouseRuns;
    private final KafkaProducerRunService kafkaRuns;
    private final DistributedTestService distributedRuns;

    public UnifiedRunService(TestRunService s3Runs, ClickHouseTestRunService clickHouseRuns,
                             KafkaProducerRunService kafkaRuns, DistributedTestService distributedRuns) {
        this.s3Runs = s3Runs;
        this.clickHouseRuns = clickHouseRuns;
        this.kafkaRuns = kafkaRuns;
        this.distributedRuns = distributedRuns;
    }

    public List<RunLifecycleView> list(RunScope scope, TestType testType, RunStatus status,
                                       String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(run -> scope == null || run.scope() == scope)
                .filter(run -> testType == null || run.testType() == testType)
                .filter(run -> status == null || run.status() == status)
                .filter(run -> normalizedQuery.isEmpty() || matches(run, normalizedQuery))
                .sorted(NEWEST_FIRST)
                .limit(safeLimit)
                .toList();
    }

    public RunLifecycleView get(UUID id) {
        if (id == null) throw new IllegalArgumentException("Run id is required");
        return all().stream().filter(run -> run.id().equals(id)).findFirst()
                .orElseThrow(() -> new RunNotFoundException(id));
    }

    private List<RunLifecycleView> all() {
        Map<UUID, RunLifecycleView> result = new LinkedHashMap<>();
        s3Runs.summaries().stream().map(UnifiedRunService::fromS3)
                .forEach(run -> result.putIfAbsent(run.id(), run));
        clickHouseRuns.list().stream().map(UnifiedRunService::fromClickHouse)
                .forEach(run -> result.putIfAbsent(run.id(), run));
        kafkaRuns.recent(MAX_LIMIT).stream().map(UnifiedRunService::fromKafka)
                .forEach(run -> result.putIfAbsent(run.id(), run));
        distributedRuns.list().stream().map(UnifiedRunService::fromDistributed)
                .forEach(run -> result.putIfAbsent(run.id(), run));
        return new ArrayList<>(result.values());
    }

    private static RunLifecycleView fromS3(TestRun.Snapshot run) {
        RunStatus status = RunStatus.parse(run.status().name());
        String bucket = run.bucket() == null || run.bucket().isBlank() ? "" : " " + run.bucket();
        return new RunLifecycleView(run.id(), "S3 " + run.operation() + bucket, run.testType(), run.scope(),
                status, run.createdAt(), run.startedAt(), run.updatedAt(), run.finishedAt(),
                terminalReason(status, run.stopReason(), run.message()), run.message(), run.completedParts(), 0,
                run.bytesTransferred(), run.operationsPerSecond(), run.averageSpeedMiBps(), run.p50LatencyMs(),
                run.p95LatencyMs(), run.p99LatencyMs(), run.failedParts(), 1, run.latencyScope(),
                "/api/tests/" + run.id());
    }

    private static RunLifecycleView fromClickHouse(ClickHouseTestRun.Snapshot run) {
        RunStatus status = RunStatus.parse(run.status().name());
        return new RunLifecycleView(run.id(), "ClickHouse " + run.operation() + " " + run.table(),
                TestType.CLICKHOUSE, RunScope.LOCAL, status, run.createdAt(), run.startedAt(),
                latest(run.finishedAt(), run.startedAt(), run.createdAt()), run.finishedAt(),
                terminalReason(status, null, run.message()), run.message(), run.queries(), run.rows(), run.bytes(),
                run.queriesPerSecond(), run.mibPerSecond(), run.p50LatencyMs(), run.p95LatencyMs(),
                run.p99LatencyMs(), run.errors(), 1, "CLICKHOUSE_QUERY", "/api/clickhouse/tests/" + run.id());
    }

    private static RunLifecycleView fromKafka(KafkaProducerRunService.Snapshot run) {
        RunStatus status = RunStatus.parse(run.status());
        return new RunLifecycleView(run.id(), "Kafka " + run.topic(), TestType.KAFKA, RunScope.LOCAL, status,
                run.startedAt(), run.startedAt(), latest(run.finishedAt(), run.startedAt()), run.finishedAt(),
                terminalReason(status, null, run.errorMessage()), run.errorMessage(), run.sentMessages(), 0,
                run.sentBytes(), run.messagesPerSec(), run.mibPerSec(), 0, run.latencyP95Ms(),
                run.latencyP99Ms(), run.errorCount(), 1, "KAFKA_PRODUCE_ACK",
                "/api/kafka/producer-tests/" + run.id());
    }

    private static RunLifecycleView fromDistributed(DistributedTestService.DistributedRunView run) {
        RunStatus status = RunStatus.parse(run.status());
        return new RunLifecycleView(run.id(), run.name(), run.testType(), RunScope.DISTRIBUTED, status,
                run.createdAt(), run.startedAt(), run.updatedAt(), run.finishedAt(),
                terminalReason(status, run.stopReason(), run.message()), run.message(), run.completedOperations(),
                run.rowsProcessed(), run.bytesTransferred(), run.operationsPerSecond(), run.throughputMiBps(),
                run.p50LatencyMs(), run.p95LatencyMs(), run.p99LatencyMs(), run.errors(), run.agents().size(),
                run.latencyScope(), "/api/distributed-tests/" + run.id());
    }

    private static RunStopReason terminalReason(RunStatus status, String explicitReason, String message) {
        if (!status.terminal()) return null;
        RunStopReason explicit = RunStopReason.parseNullable(explicitReason);
        if (explicit != null) return explicit;
        return switch (status) {
            case FAILED -> RunStopReason.ERROR;
            case CANCELLED -> message != null && message.toLowerCase(Locale.ROOT).contains("deadline")
                    ? RunStopReason.DEADLINE_EXCEEDED : RunStopReason.USER_CANCELLED;
            default -> RunStopReason.NORMAL;
        };
    }

    private static Instant latest(Instant... values) {
        for (Instant value : values) if (value != null) return value;
        return null;
    }

    private static boolean matches(RunLifecycleView run, String query) {
        return run.id().toString().toLowerCase(Locale.ROOT).contains(query)
                || contains(run.name(), query) || contains(run.message(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(UUID id) { super("Run not found: " + id); }
    }
}
