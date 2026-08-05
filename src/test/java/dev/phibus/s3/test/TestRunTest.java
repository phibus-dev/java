package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestRunTest {
    private static TestRequest request() {
        return new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 100, 10, "UPLOAD");
    }

    @Test
    void reportsProgressWithoutExposingCredentials() {
        TestRun run = new TestRun(request());
        run.start(10);
        run.progress(50L * 1024 * 1024, 5, 120.5, 110.0);

        TestRun.Snapshot snapshot = run.snapshot();
        assertEquals(TestStatus.RUNNING, snapshot.status());
        assertEquals(50.0, snapshot.percent(), 0.01);
        assertEquals(5, snapshot.completedParts());
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
