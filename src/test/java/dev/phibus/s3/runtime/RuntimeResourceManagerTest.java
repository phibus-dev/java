package dev.phibus.s3.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
                        .containsEntry("runtime.availableWorkers", "0");
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
}
