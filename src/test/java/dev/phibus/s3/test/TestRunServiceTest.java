package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.phibus.s3.history.HistoryRequestMetadataUpdater;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.runtime.RuntimeResourceManager;
import dev.phibus.s3.settings.S3ProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TestRunServiceTest {
    @Test
    void callerProvidedRunIdMakesRepeatedCreationIdempotentInMemory() {
        AtomicInteger executions = new AtomicInteger();
        LoadTestEngine engine = new LoadTestEngine() {
            @Override public TestType type() { return TestType.S3; }
            @Override public void execute(TestRun run) {
                executions.incrementAndGet();
                run.complete();
            }
        };
        UUID runId = UUID.randomUUID();
        try (RuntimeResourceManager resources = new RuntimeResourceManager(2, 8, 60,
                new SimpleMeterRegistry())) {
            TestRunService service = new TestRunService(new LoadTestEngineRegistry(List.of(engine)),
                    mock(S3LoadTestEngine.class), Runnable::run, mock(TestHistoryStore.class),
                    mock(HistoryRequestMetadataUpdater.class), mock(S3ProfileService.class), resources, "AGENT", 10);

            TestRun first = service.create(runId, request());
            TestRun replay = service.create(runId, request());

            assertEquals(runId, first.id());
            assertEquals(first, replay);
            assertEquals(1, executions.get());
        }
    }

    @Test
    void rejectedExecutionProducesTerminalFailedRun() {
        LoadTestEngine engine = new LoadTestEngine() {
            @Override public TestType type() { return TestType.S3; }
            @Override public void execute(TestRun run) { run.complete(); }
        };
        Executor rejecting = command -> { throw new RejectedExecutionException("test queue is full"); };
        try (RuntimeResourceManager resources = new RuntimeResourceManager(2, 8, 60,
                new SimpleMeterRegistry())) {
            TestRunService service = new TestRunService(new LoadTestEngineRegistry(List.of(engine)),
                    mock(S3LoadTestEngine.class), rejecting, mock(TestHistoryStore.class),
                    mock(HistoryRequestMetadataUpdater.class), mock(S3ProfileService.class), resources, "AGENT", 10);

            TestRun.Snapshot snapshot = service.create(request()).snapshot();

            assertEquals(TestStatus.FAILED, snapshot.status());
            assertEquals(1, snapshot.failedParts());
            assertTrue(snapshot.message().contains("test queue is full"));
        }
    }

    @Test
    void validatesS3DemandBeforeSubmittingTheRun() {
        RuntimeResourceManager resources = mock(RuntimeResourceManager.class);
        LoadTestEngine engine = new LoadTestEngine() {
            @Override public TestType type() { return TestType.S3; }
            @Override public void execute(TestRun run) { run.complete(); }
        };
        TestRunService service = new TestRunService(new LoadTestEngineRegistry(List.of(engine)),
                mock(S3LoadTestEngine.class), command -> { }, mock(TestHistoryStore.class),
                mock(HistoryRequestMetadataUpdater.class), mock(S3ProfileService.class), resources, "AGENT", 10);

        service.create(request());

        ArgumentCaptor<RuntimeResourceManager.RunDemand> demand =
                ArgumentCaptor.forClass(RuntimeResourceManager.RunDemand.class);
        verify(resources).validateDemand(demand.capture());
        assertEquals(TestType.S3, demand.getValue().testType());
        assertEquals(4, demand.getValue().workerTasks());
        assertEquals(32L * 1024L * 1024L, demand.getValue().bytes());
        verify(resources).submit(any(), any(), any(), any(), any());
    }

    private static TestRequest request() {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "object.bin", 32, 8, 4, 1, false, "UPLOAD");
    }
}
