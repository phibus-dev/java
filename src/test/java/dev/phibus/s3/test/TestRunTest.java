package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestRunTest {
    private static TestRequest request() {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 100, 10, 4, 1, true, "UPLOAD");
    }

    @Test
    void reportsProgressPercentilesWithoutExposingCredentials() {
        TestRun run = new TestRun(request());
        run.start(10);
        run.partCompleted(new PartResult(1, 1, 10L * 1024 * 1024, 100, 100, "etag1", "SUCCESS", null));
        run.partCompleted(new PartResult(1, 2, 10L * 1024 * 1024, 200, 50, "etag2", "SUCCESS", null));

        TestRun.Snapshot snapshot = run.snapshot();
        assertEquals(TestStatus.RUNNING, snapshot.status());
        assertEquals(20.0, snapshot.percent(), 0.01);
        assertEquals(2, snapshot.completedParts());
        assertEquals(100.0, snapshot.p50LatencyMs(), 0.01);
        assertEquals(200.0, snapshot.p95LatencyMs(), 0.01);
        assertEquals("bucket", snapshot.bucket());
    }

    @Test
    void supportsCancellation() {
        TestRun run = new TestRun(request());
        run.cancel();
        assertTrue(run.isCancelled());
        assertEquals(TestStatus.CANCELLED, run.snapshot().status());
    }
}
