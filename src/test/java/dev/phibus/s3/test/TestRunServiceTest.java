package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.phibus.s3.history.HistoryRequestMetadataUpdater;
import dev.phibus.s3.history.TestHistoryStore;
import dev.phibus.s3.settings.S3ProfileService;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class TestRunServiceTest {
    @Test
    void rejectedExecutionProducesTerminalFailedRun() {
        LoadTestEngine engine = new LoadTestEngine() {
            @Override public TestType type() { return TestType.S3; }
            @Override public void execute(TestRun run) { run.complete(); }
        };
        Executor rejecting = command -> { throw new RejectedExecutionException("test queue is full"); };
        TestRunService service = new TestRunService(new LoadTestEngineRegistry(List.of(engine)),
                mock(S3LoadTestEngine.class), rejecting, mock(TestHistoryStore.class),
                mock(HistoryRequestMetadataUpdater.class), mock(S3ProfileService.class), "AGENT", 10);

        TestRun.Snapshot snapshot = service.create(request()).snapshot();

        assertEquals(TestStatus.FAILED, snapshot.status());
        assertEquals(1, snapshot.failedParts());
        assertTrue(snapshot.message().contains("test queue is full"));
    }

    private static TestRequest request() {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "object.bin", 32, 8, 4, 1, false, "UPLOAD");
    }
}
