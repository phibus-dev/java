package dev.phibus.s3.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.phibus.s3.clickhouse.ClickHouseTestRequest;
import dev.phibus.s3.clickhouse.ClickHouseTestRun;
import dev.phibus.s3.clickhouse.ClickHouseTestRunService;
import dev.phibus.s3.distributed.DistributedTestService;
import dev.phibus.s3.kafka.KafkaProducerRunService;
import dev.phibus.s3.test.PartResult;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnifiedRunServiceTest {
    private final TestRunService s3Runs = mock(TestRunService.class);
    private final ClickHouseTestRunService clickHouseRuns = mock(ClickHouseTestRunService.class);
    private final KafkaProducerRunService kafkaRuns = mock(KafkaProducerRunService.class);
    private final DistributedTestService distributedRuns = mock(DistributedTestService.class);
    private final UnifiedRunService service = new UnifiedRunService(
            s3Runs, clickHouseRuns, kafkaRuns, distributedRuns);

    private TestRun.Snapshot s3;
    private ClickHouseTestRun.Snapshot clickHouse;
    private KafkaProducerRunService.Snapshot kafka;
    private DistributedTestService.DistributedRunView distributed;

    @BeforeEach
    void setUp() {
        TestRun s3Run = new TestRun(new TestRequest("http://s3:9000", "bucket", "us-east-1", null, null,
                true, "load.bin", 5, 5, 1, 1, false, "UPLOAD"));
        s3Run.start(1);
        s3Run.partCompleted(new PartResult(1, 1, 5 * 1024 * 1024L, 25, 200,
                "etag", "SUCCESS", null));
        s3Run.complete();
        s3 = s3Run.snapshot();

        ClickHouseTestRun clickHouseRun = new ClickHouseTestRun(new ClickHouseTestRequest(
                UUID.randomUUID(), null, "events", "SELECT", 1, 100, 1, 0, 0, 128, 1L, true),
                "http://clickhouse:8123");
        clickHouseRun.start();
        clickHouseRun.operationCompleted(100, 12_800, 12);
        clickHouseRun.complete();
        clickHouse = clickHouseRun.snapshot();

        Instant kafkaStarted = Instant.now().plusSeconds(10);
        kafka = new KafkaProducerRunService.Snapshot(UUID.randomUUID(), UUID.randomUUID(), "events", "FAILED",
                kafkaStarted, kafkaStarted.plusSeconds(2), 2_000, 100, 80, 8_000, 2, 100,
                40, 0.004, 5, 8, 11, 12, 20, 1, "broker unavailable");

        Instant distributedCreated = Instant.now().plusSeconds(20);
        distributed = new DistributedTestService.DistributedRunView(UUID.randomUUID(), "distributed upload",
                TestType.S3, "CANCELLED", distributedCreated, distributedCreated.plusSeconds(3), null, null, null,
                10, 0, 10_000, 5, 0, 1.5, 10, 20, 30, 1, List.of(), "MULTIPART_PART",
                "MERGED_HISTOGRAM", distributedCreated, distributedCreated.plusSeconds(3), "RECOVERY_TIMEOUT",
                "Recovery timeout exceeded");

        when(s3Runs.summaries()).thenReturn(List.of(s3));
        when(clickHouseRuns.list()).thenReturn(List.of(clickHouse));
        when(kafkaRuns.recent(500)).thenReturn(List.of(kafka));
        when(distributedRuns.list()).thenReturn(List.of(distributed));
    }

    @Test
    void normalizesAllRuntimeTypesAndOrdersNewestFirst() {
        List<RunLifecycleView> runs = service.list(null, null, null, null, 200);

        assertThat(runs).hasSize(4);
        assertThat(runs.getFirst().id()).isEqualTo(distributed.id());
        assertThat(runs).extracting(RunLifecycleView::testType)
                .containsExactlyInAnyOrder(TestType.S3, TestType.CLICKHOUSE, TestType.KAFKA, TestType.S3);
        assertThat(runs).filteredOn(run -> run.id().equals(kafka.id())).singleElement()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.stopReason()).isEqualTo(RunStopReason.ERROR);
                    assertThat(run.scope()).isEqualTo(RunScope.LOCAL);
                    assertThat(run.detailsUrl()).isEqualTo("/api/kafka/producer-tests/" + kafka.id());
                });
        assertThat(runs).filteredOn(run -> run.id().equals(distributed.id())).singleElement()
                .satisfies(run -> {
                    assertThat(run.scope()).isEqualTo(RunScope.DISTRIBUTED);
                    assertThat(run.stopReason()).isEqualTo(RunStopReason.RECOVERY_TIMEOUT);
                });
    }

    @Test
    void filtersByScopeTypeStatusAndTextQuery() {
        assertThat(service.list(RunScope.DISTRIBUTED, TestType.S3, RunStatus.CANCELLED, "recovery", 10))
                .extracting(RunLifecycleView::id)
                .containsExactly(distributed.id());
        assertThat(service.list(RunScope.LOCAL, TestType.CLICKHOUSE, RunStatus.COMPLETED, "events", 10))
                .extracting(RunLifecycleView::id)
                .containsExactly(clickHouse.id());
        assertThat(service.list(null, null, null, kafka.id().toString(), 10))
                .extracting(RunLifecycleView::id)
                .containsExactly(kafka.id());
    }

    @Test
    void returnsOneRunAndReportsUnknownIdentifiers() {
        assertThat(service.get(s3.id()).testType()).isEqualTo(TestType.S3);
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(missing))
                .isInstanceOf(UnifiedRunService.RunNotFoundException.class)
                .hasMessageContaining(missing.toString());
    }
}
