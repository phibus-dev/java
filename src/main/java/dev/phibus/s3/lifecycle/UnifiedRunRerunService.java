package dev.phibus.s3.lifecycle;

import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class UnifiedRunRerunService {
    private final UnifiedRunHistoryStore history;
    private final AdvancedHistoryStore s3History;
    private final TestRunService s3Runs;
    private final ClickHouseHistoryStore clickHouseHistory;
    private final ClickHouseTestRunService clickHouseRuns;
    private final KafkaProducerRunService kafkaRuns;
    private final DistributedTestService distributedRuns;

    public UnifiedRunRerunService(UnifiedRunHistoryStore history, AdvancedHistoryStore s3History,
                                  TestRunService s3Runs, ClickHouseHistoryStore clickHouseHistory,
                                  ClickHouseTestRunService clickHouseRuns, KafkaProducerRunService kafkaRuns,
                                  DistributedTestService distributedRuns) {
        this.history = history;
        this.s3History = s3History;
        this.s3Runs = s3Runs;
        this.clickHouseHistory = clickHouseHistory;
        this.clickHouseRuns = clickHouseRuns;
        this.kafkaRuns = kafkaRuns;
        this.distributedRuns = distributedRuns;
    }

    public RerunResult rerun(UUID id, String initiator) {
        UnifiedRunHistoryStore.RunHistoryRow source = history.get(id);
        if (source == null) throw new RunHistoryNotFoundException(id);
        if (!source.rerunnable()) {
            throw new IllegalStateException("Only a completed run can be repeated");
        }
        if (source.scope() == RunScope.DISTRIBUTED) return rerunDistributed(source);
        return switch (source.testType()) {
            case S3 -> rerunS3(source);
            case CLICKHOUSE -> rerunClickHouse(source);
            case KAFKA -> rerunKafka(source, initiator);
        };
    }

    private RerunResult rerunS3(UnifiedRunHistoryStore.RunHistoryRow source) {
        AdvancedHistoryStore.Detail detail = s3History.get(source.id());
        if (detail == null) throw new RunHistoryNotFoundException(source.id());
        TestRun.Snapshot run = s3Runs.create(detail.repeatRequest()).snapshot();
        return result(source, run.id(), RunStatus.parse(run.status().name()), "/tasks");
    }

    private RerunResult rerunClickHouse(UnifiedRunHistoryStore.RunHistoryRow source) {
        ClickHouseHistoryStore.HistoryRow historyRow = clickHouseHistory.get(source.id());
        ClickHouseTestRequest request = new ClickHouseTestRequest(historyRow.profileId(), historyRow.endpoint(),
                historyRow.table(), historyRow.operation(), historyRow.concurrency(), historyRow.batchSize(),
                historyRow.requestedRows(), historyRow.durationSeconds(), historyRow.warmupSeconds(),
                historyRow.payloadBytes(), historyRow.requestedQueries(), historyRow.autoCreateTable());
        ClickHouseTestRun.Snapshot run = clickHouseRuns.create(request).snapshot();
        return result(source, run.id(), RunStatus.parse(run.status().name()), "/clickhouse");
    }

    private RerunResult rerunKafka(UnifiedRunHistoryStore.RunHistoryRow source, String initiator) {
        KafkaProducerRunService.Snapshot run = kafkaRuns.rerun(source.id(), normalizeInitiator(initiator));
        return result(source, run.id(), RunStatus.parse(run.status()), "/kafka");
    }

    private RerunResult rerunDistributed(UnifiedRunHistoryStore.RunHistoryRow source) {
        DistributedTestService.DistributedRunView run = distributedRuns.rerun(source.id());
        return result(source, run.id(), RunStatus.parse(run.status()), "/distributed-tests");
    }

    private static RerunResult result(UnifiedRunHistoryStore.RunHistoryRow source, UUID runId,
                                      RunStatus status, String monitorUrl) {
        return new RerunResult(source.id(), runId, source.testType(), source.scope(), status, monitorUrl);
    }

    private static String normalizeInitiator(String initiator) {
        return initiator == null || initiator.isBlank() ? "local" : initiator;
    }

    public record RerunResult(UUID sourceRunId, UUID runId, TestType testType, RunScope scope,
                              RunStatus status, String monitorUrl) { }

    public static final class RunHistoryNotFoundException extends RuntimeException {
        public RunHistoryNotFoundException(UUID id) { super("Run history entry not found: " + id); }
    }
}
