package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("MULTIPART_PART", snapshot.latencyScope());
        assertEquals(2, snapshot.latencySampleCount());
    }

    @Test
    void keepsApiAndObjectLatencySeparateFromRecordedSteps() {
        TestRun run = new TestRun(request());
        run.start(1);
        run.apiRequestCompleted(10);
        run.apiRequestCompleted(20);
        run.objectOperationCompleted(50);
        run.partCompleted(new PartResult(1, 1, 1024, 20, 1, "etag", "SUCCESS", null));

        TestRun.Snapshot snapshot = run.snapshot();

        assertEquals(2, snapshot.apiRequestCount());
        assertEquals(20, snapshot.apiRequestP95LatencyMs(), 0.01);
        assertEquals(1, snapshot.objectOperationCount());
        assertEquals(50, snapshot.objectP95LatencyMs(), 0.01);
        assertEquals(1, snapshot.latencySampleCount());
    }

    @Test
    void derivesLatencyScopeFromTheMeasuredS3Operation() {
        TestRequest head = new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 100, 10, 4, 1, false, "HEAD");
        TestRequest lifecycle = new TestRequest("http://localhost:9000", "bucket", "us-east-1", "key", "secret",
                true, "test.bin", 100, 10, 4, 1, false, "LIFECYCLE");

        assertEquals(S3LatencyScope.OBJECT_REQUEST, S3LatencyScope.forRequest(head));
        assertEquals(S3LatencyScope.RECORDED_STEP, S3LatencyScope.forRequest(lifecycle));
        assertEquals(S3LatencyScope.MULTIPART_PART, S3LatencyScope.forRequest(request()));
    }

    @Test
    void supportsCancellation() {
        TestRun run = new TestRun(request());
        run.cancel();
        run.start(1);
        run.partCompleted(new PartResult(1, 1, 1024, 10, 1, "late", "SUCCESS", null));
        assertTrue(run.isCancelled());
        assertEquals(TestStatus.CANCELLED, run.snapshot().status());
        assertEquals(0, run.snapshot().completedParts());
    }

    @Test
    void completedRunCannotBeChangedToCancelled() {
        TestRun run = new TestRun(request());
        run.start(0);
        run.complete();

        run.cancel();
        run.partCompleted(new PartResult(1, 1, 1024, 10, 1, "late", "SUCCESS", null));

        assertEquals(TestStatus.COMPLETED, run.snapshot().status());
        assertEquals(0, run.snapshot().completedParts());
    }

    @Test
    void recordsTerminalFailureEvenWhenNoOperationCompleted() {
        TestRun run = new TestRun(request());
        run.start(10);

        run.fail("S3 request failed");

        TestRun.Snapshot snapshot = run.snapshot();
        assertEquals(TestStatus.FAILED, snapshot.status());
        assertEquals(1, snapshot.completedParts());
        assertEquals(0, snapshot.successfulParts());
        assertEquals(1, snapshot.failedParts());
        assertEquals("FAILED", snapshot.parts().getFirst().status());
        assertEquals("S3 request failed", snapshot.parts().getFirst().error());
    }

    @Test
    void boundsDetailedResultsWithoutLosingAggregateMetrics() {
        TestRun run = new TestRun(request(), 3);
        run.start(5);
        for (int part = 1; part <= 5; part++) {
            run.partCompleted(new PartResult(1, part, 1024, part * 10L, 1,
                    "etag-" + part, "SUCCESS", null));
        }

        TestRun.Snapshot snapshot = run.snapshot();
        assertEquals(5, snapshot.completedParts());
        assertEquals(5, snapshot.successfulParts());
        assertEquals(3, snapshot.parts().size());
        assertEquals(3, snapshot.parts().getFirst().partNumber());
        assertTrue(snapshot.detailsTruncated());
        assertEquals(30.0, snapshot.p50LatencyMs(), 1.0);
        assertFalse(snapshot.parts().isEmpty());
        assertTrue(run.summary().parts().isEmpty());
        assertEquals(5, run.summary().successfulParts());
    }
}
