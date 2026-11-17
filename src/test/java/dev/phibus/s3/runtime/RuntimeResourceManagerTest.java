package dev.phibus.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.test.TestType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeResourceManagerTest {
    @Test
    void rejectsRunsAndWorkersBeyondGlobalLimitsAndExposesTelemetry() throws Exception {
        try (RuntimeResourceManager resources = new RuntimeResourceManager(1, 2, 60,
                     new SimpleMeterRegistry());
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            resources.submit(executor, "S3", UUID.randomUUID(), () -> { }, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> resources.submit(executor, "KAFKA", UUID.randomUUID(),
                    () -> { }, () -> { }))
                    .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                    .hasMessageContaining("Run capacity exceeded");

            try (RuntimeResourceManager.WorkerPermit ignored =
                         resources.acquireWorkers(2, "S3", UUID.randomUUID())) {
                assertThatThrownBy(() -> resources.acquireWorkers(1, "CLICKHOUSE", UUID.randomUUID()))
                        .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                        .hasMessageContaining("Worker capacity exceeded");
                assertThat(resources.telemetryTags())
                        .containsEntry("runtime.activeRuns", "1")
                        .containsEntry("runtime.activeWorkers", "2")
                        .containsEntry("runtime.availableWorkers", "0")
                        .containsEntry("runtime.maxWorkersPerRun", "2")
                        .containsEntry("runtime.scope", "LOCAL")
                        .containsEntry("runtime.scope.activeWorkers", "2");
            }
            release.countDown();
        }
    }

    @Test
    void deadlineCancelsAndInterruptsRunningTask() throws Exception {
        try (RuntimeResourceManager resources = new RuntimeResourceManager(1, 1, 1,
                     new SimpleMeterRegistry());
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch deadline = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            resources.submit(executor, "CLICKHOUSE", UUID.randomUUID(), deadline::countDown, () -> {
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                } catch (InterruptedException error) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(deadline.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void deadlineReleasesRunThatNeverLeavesExecutorQueue() throws Exception {
        try (RuntimeResourceManager resources = new RuntimeResourceManager(1, 1, 1,
                     new SimpleMeterRegistry())) {
            CountDownLatch deadline = new CountDownLatch(1);
            resources.submit(ignored -> { }, "S3", UUID.randomUUID(), deadline::countDown, () -> { });

            assertThat(deadline.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(resources.telemetryTags())
                    .containsEntry("runtime.activeRuns", "0")
                    .containsEntry("runtime.availableRuns", "1");

            // A leaked permit would reject this second submission.
            resources.submit(Runnable::run, "S3", UUID.randomUUID(), () -> { }, () -> { });
        }
    }

    @Test
    void enforcesEngineQuotaAndRollsBackTheGlobalReservation() throws Exception {
        RuntimeResourceManager.Limits limits = limits(2, 4, 4, 60,
                Map.of(TestType.S3, 1, TestType.CLICKHOUSE, 2, TestType.KAFKA, 2),
                Map.of(RunScope.LOCAL, 2, RunScope.DISTRIBUTED, 2),
                allTypes(4), allScopes(4), 1_000, 1_000, 1_000, 1_000);
        try (RuntimeResourceManager resources = new RuntimeResourceManager(
                     limits, RunScope.LOCAL, new SimpleMeterRegistry());
             ExecutorService executor = Executors.newCachedThreadPool()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            resources.submit(executor, "S3", UUID.randomUUID(), () -> { }, () -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> resources.submit(executor, "S3", UUID.randomUUID(),
                    () -> { }, () -> { }))
                    .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                    .hasMessageContaining("constraint=test_type");

            resources.submit(Runnable::run, "CLICKHOUSE", UUID.randomUUID(), () -> { }, () -> { });
            assertThat(resources.capacity().availableRuns()).isEqualTo(1);
            assertThat(resources.capacity().byTestType().get(TestType.CLICKHOUSE).availableRuns()).isEqualTo(2);
            release.countDown();
        }
    }

    @Test
    void enforcesDistributedScopeQuotaAndRollsBackTypeReservation() throws Exception {
        RuntimeResourceManager.Limits limits = limits(2, 4, 4, 60, allTypes(2),
                Map.of(RunScope.LOCAL, 2, RunScope.DISTRIBUTED, 1),
                allTypes(4), allScopes(4), 1_000, 1_000, 1_000, 1_000);
        try (RuntimeResourceManager resources = new RuntimeResourceManager(
                     limits, RunScope.DISTRIBUTED, new SimpleMeterRegistry());
             ExecutorService executor = Executors.newCachedThreadPool()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            resources.submit(executor, "S3", UUID.randomUUID(), () -> { }, () -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> resources.submit(executor, "KAFKA", UUID.randomUUID(),
                    () -> { }, () -> { }))
                    .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                    .hasMessageContaining("constraint=scope");
            assertThat(resources.capacity().byTestType().get(TestType.KAFKA).availableRuns()).isEqualTo(2);
            assertThat(resources.telemetryTags()).containsEntry("runtime.scope", "DISTRIBUTED");
            release.countDown();
        }
    }

    @Test
    void workerScopeFailureRollsBackGlobalAndEnginePermits() {
        RuntimeResourceManager.Limits limits = limits(2, 4, 4, 60, allTypes(2), allScopes(2),
                Map.of(TestType.S3, 2, TestType.CLICKHOUSE, 4, TestType.KAFKA, 4),
                Map.of(RunScope.LOCAL, 3, RunScope.DISTRIBUTED, 4),
                1_000, 1_000, 1_000, 1_000);
        try (RuntimeResourceManager resources = new RuntimeResourceManager(
                limits, RunScope.LOCAL, new SimpleMeterRegistry());
             RuntimeResourceManager.WorkerPermit ignored =
                     resources.acquireWorkers(2, "S3", UUID.randomUUID())) {
            assertThatThrownBy(() -> resources.acquireWorkers(2, "CLICKHOUSE", UUID.randomUUID()))
                    .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                    .hasMessageContaining("constraint=scope");
            assertThat(resources.capacity().availableWorkers()).isEqualTo(2);
            assertThat(resources.capacity().byTestType().get(TestType.CLICKHOUSE).availableWorkers()).isEqualTo(4);
        }
    }

    @Test
    void enforcesTheWorkerCeilingAcrossMultipleReservationsOfOneRun() {
        RuntimeResourceManager.Limits limits = limits(2, 6, 3, 60, allTypes(2), allScopes(2),
                allTypes(6), allScopes(6), 1_000, 1_000, 1_000, 1_000);
        UUID runId = UUID.randomUUID();
        try (RuntimeResourceManager resources = new RuntimeResourceManager(
                limits, RunScope.LOCAL, new SimpleMeterRegistry())) {
            try (RuntimeResourceManager.WorkerPermit first = resources.acquireWorkers(2, "S3", runId)) {
                assertThatThrownBy(() -> resources.acquireWorkers(2, "S3", runId))
                        .isInstanceOf(RuntimeResourceManager.RunCapacityExceededException.class)
                        .hasMessageContaining("constraint=per_run");
                try (RuntimeResourceManager.WorkerPermit otherRun =
                             resources.acquireWorkers(3, "S3", UUID.randomUUID())) {
                    assertThat(resources.activeWorkers()).isEqualTo(5);
                }
            }
            try (RuntimeResourceManager.WorkerPermit ignored = resources.acquireWorkers(3, "S3", runId)) {
                assertThat(resources.activeWorkers()).isEqualTo(3);
            }
        }
    }

    @Test
    void rejectsDemandsAbovePerRunDurationAndWorkloadLimits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeResourceManager.Limits limits = limits(2, 4, 2, 10, allTypes(2), allScopes(2),
                allTypes(4), allScopes(4), 100, 200, 5, 20);
        try (RuntimeResourceManager resources = new RuntimeResourceManager(limits, RunScope.LOCAL, registry)) {
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.s3(3, 0, 1)))
                    .isInstanceOf(RuntimeResourceManager.RunLimitExceededException.class)
                    .hasMessageContaining("workers_per_run");
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.s3(1, 11, 1)))
                    .hasMessageContaining("constraint=duration");
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.s3(1, 0, 101)))
                    .hasMessageContaining("requested=101 bytes");
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.clickHouse(1, 0, 201, 1)))
                    .hasMessageContaining("requested=201 rows");
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.clickHouse(1, 0, 1, 6)))
                    .hasMessageContaining("requested=6 queries");
            assertThatThrownBy(() -> resources.validateDemand(
                    RuntimeResourceManager.RunDemand.kafka(1, 21)))
                    .hasMessageContaining("requested=21 messages");
            assertThat(registry.get("s3perf.runtime.limit.rejections").counters())
                    .hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void normalizesCategoryLimitsToGlobalCapacityAndRegistersTaggedGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuntimeResourceManager.Limits limits = limits(2, 4, 10, 60,
                allTypes(10), allScopes(10), allTypes(10), allScopes(10),
                1_000, 1_000, 1_000, 1_000);
        try (RuntimeResourceManager resources = new RuntimeResourceManager(limits, RunScope.LOCAL, registry)) {
            RuntimeResourceManager.CapacitySnapshot capacity = resources.capacity();
            assertThat(capacity.maxWorkersPerRun()).isEqualTo(4);
            assertThat(capacity.byTestType().get(TestType.S3).maxRuns()).isEqualTo(2);
            assertThat(capacity.byTestType().get(TestType.KAFKA).maxWorkers()).isEqualTo(4);
            assertThat(capacity.byScope().get(RunScope.LOCAL).maxRuns()).isEqualTo(2);
            assertThat(registry.find("s3perf.runtime.type.runs.active")
                    .tag("test_type", "s3").gauge()).isNotNull();
            assertThat(registry.find("s3perf.runtime.scope.workers.available")
                    .tag("scope", "local").gauge()).isNotNull();
        }
    }

    private static RuntimeResourceManager.Limits limits(
            int runs, int workers, int workersPerRun, long duration,
            Map<TestType, Integer> runsByType, Map<RunScope, Integer> runsByScope,
            Map<TestType, Integer> workersByType, Map<RunScope, Integer> workersByScope,
            long s3Bytes, long clickHouseRows, long clickHouseQueries, long kafkaMessages) {
        return new RuntimeResourceManager.Limits(runs, workers, workersPerRun, duration,
                runsByType, runsByScope, workersByType, workersByScope,
                s3Bytes, clickHouseRows, clickHouseQueries, kafkaMessages);
    }

    private static Map<TestType, Integer> allTypes(int value) {
        return Map.of(TestType.S3, value, TestType.CLICKHOUSE, value, TestType.KAFKA, value);
    }

    private static Map<RunScope, Integer> allScopes(int value) {
        return Map.of(RunScope.LOCAL, value, RunScope.DISTRIBUTED, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
