package dev.phibus.s3.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.phibus.s3.clickhouse.ClickHouseHistoryStore;
import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UnifiedRunRerunServiceTest {
    private final UnifiedRunHistoryStore history = mock(UnifiedRunHistoryStore.class);
    private final AdvancedHistoryStore s3History = mock(AdvancedHistoryStore.class);
    private final TestRunService s3Runs = mock(TestRunService.class);
    private final ClickHouseHistoryStore clickHouseHistory = mock(ClickHouseHistoryStore.class);
    private final ClickHouseTestRunService clickHouseRuns = mock(ClickHouseTestRunService.class);
    private final KafkaProducerRunService kafkaRuns = mock(KafkaProducerRunService.class);
    private final DistributedTestService distributedRuns = mock(DistributedTestService.class);
    private final UnifiedRunRerunService service = new UnifiedRunRerunService(history, s3History, s3Runs,
            clickHouseHistory, clickHouseRuns, kafkaRuns, distributedRuns);

    @Test
    void repeatsLocalS3RunFromItsPersistedRequest() {
        UUID id = UUID.randomUUID();
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "object.bin", 10, 5, 1, 1, false, "UPLOAD");
        AdvancedHistoryStore.Detail detail = mock(AdvancedHistoryStore.Detail.class);
        when(detail.repeatRequest()).thenReturn(request);
        when(history.get(id)).thenReturn(row(id, TestType.S3, RunScope.LOCAL, true));
        when(s3History.get(id)).thenReturn(detail);
        when(s3Runs.create(request)).thenReturn(new TestRun(request));

        UnifiedRunRerunService.RerunResult result = service.rerun(id, "operator");

        assertThat(result.sourceRunId()).isEqualTo(id);
        assertThat(result.testType()).isEqualTo(TestType.S3);
        assertThat(result.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(result.monitorUrl()).isEqualTo("/tasks");
    }

    @Test
    void reconstructsAndRepeatsLocalClickHouseRun() {
        UUID id = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        ClickHouseHistoryStore.HistoryRow persisted = mock(ClickHouseHistoryStore.HistoryRow.class);
        when(persisted.profileId()).thenReturn(profileId);
        when(persisted.endpoint()).thenReturn("http://clickhouse:8123");
        when(persisted.table()).thenReturn("events");
        when(persisted.operation()).thenReturn("SELECT");
        when(persisted.concurrency()).thenReturn(4);
        when(persisted.batchSize()).thenReturn(1000);
        when(persisted.requestedRows()).thenReturn(10_000L);
        when(persisted.requestedQueries()).thenReturn(20L);
        when(persisted.durationSeconds()).thenReturn(0L);
        when(persisted.warmupSeconds()).thenReturn(0L);
        when(persisted.payloadBytes()).thenReturn(128);
        when(persisted.autoCreateTable()).thenReturn(true);
        when(history.get(id)).thenReturn(row(id, TestType.CLICKHOUSE, RunScope.LOCAL, true));
        when(clickHouseHistory.get(id)).thenReturn(persisted);
        when(clickHouseRuns.create(any())).thenAnswer(invocation -> new ClickHouseTestRun(
                invocation.getArgument(0, ClickHouseTestRequest.class), "http://clickhouse:8123"));

        UnifiedRunRerunService.RerunResult result = service.rerun(id, "operator");

        ArgumentCaptor<ClickHouseTestRequest> request = ArgumentCaptor.forClass(ClickHouseTestRequest.class);
        verify(clickHouseRuns).create(request.capture());
        assertThat(request.getValue().profileId()).isEqualTo(profileId);
        assertThat(request.getValue().queryCount()).isEqualTo(20);
        assertThat(result.monitorUrl()).isEqualTo("/clickhouse");
    }

    @Test
    void repeatsLocalKafkaAndDistributedRunsThroughTheirOwningServices() {
        UUID kafkaSource = UUID.randomUUID();
        UUID kafkaRun = UUID.randomUUID();
        UUID distributedSource = UUID.randomUUID();
        UUID distributedRun = UUID.randomUUID();
        KafkaProducerRunService.Snapshot kafkaSnapshot = mock(KafkaProducerRunService.Snapshot.class);
        when(kafkaSnapshot.id()).thenReturn(kafkaRun);
        when(kafkaSnapshot.status()).thenReturn("RUNNING");
        DistributedTestService.DistributedRunView distributedView = mock(
                DistributedTestService.DistributedRunView.class);
        when(distributedView.id()).thenReturn(distributedRun);
        when(distributedView.status()).thenReturn("RUNNING");
        when(history.get(kafkaSource)).thenReturn(row(kafkaSource, TestType.KAFKA, RunScope.LOCAL, true));
        when(history.get(distributedSource)).thenReturn(
                row(distributedSource, TestType.S3, RunScope.DISTRIBUTED, true));
        when(kafkaRuns.rerun(kafkaSource, "operator")).thenReturn(kafkaSnapshot);
        when(distributedRuns.rerun(distributedSource)).thenReturn(distributedView);

        UnifiedRunRerunService.RerunResult kafka = service.rerun(kafkaSource, "operator");
        UnifiedRunRerunService.RerunResult distributed = service.rerun(distributedSource, "operator");

        assertThat(kafka.runId()).isEqualTo(kafkaRun);
        assertThat(kafka.monitorUrl()).isEqualTo("/kafka");
        assertThat(distributed.runId()).isEqualTo(distributedRun);
        assertThat(distributed.monitorUrl()).isEqualTo("/distributed-tests");
    }

    @Test
    void rejectsAnActiveOrUnknownSourceRun() {
        UUID active = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(history.get(active)).thenReturn(row(active, TestType.KAFKA, RunScope.LOCAL, false));

        assertThatThrownBy(() -> service.rerun(active, "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed");
        assertThatThrownBy(() -> service.rerun(missing, "operator"))
                .isInstanceOf(UnifiedRunRerunService.RunHistoryNotFoundException.class);
    }

    private static UnifiedRunHistoryStore.RunHistoryRow row(UUID id, TestType type, RunScope scope,
                                                             boolean rerunnable) {
        Instant now = Instant.now();
        return new UnifiedRunHistoryStore.RunHistoryRow(id, type, scope,
                rerunnable ? RunStatus.COMPLETED : RunStatus.RUNNING, now, now,
                rerunnable ? now : null, "run", "operation", "target", 1, 0, 1,
                1, 1, 1, 1, 1, 0, rerunnable ? RunStopReason.NORMAL : null,
                "message", 1, "scope", null, rerunnable);
    }
}
