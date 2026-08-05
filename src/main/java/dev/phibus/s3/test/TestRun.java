package dev.phibus.s3.test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestRun {
    private final UUID id = UUID.randomUUID();
    private final TestRequest request;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile TestStatus status = TestStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile long bytesTransferred;
    private volatile int completedParts;
    private volatile int totalParts;
    private volatile double currentSpeedMiBps;
    private volatile double averageSpeedMiBps;
    private volatile String message = "Queued";

    public TestRun(TestRequest request) {
        this.request = request;
    }

    public UUID id() { return id; }
    public TestRequest request() { return request; }
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); status = TestStatus.CANCELLED; message = "Cancellation requested"; }

    public void start(int totalParts) {
        this.totalParts = totalParts;
        this.startedAt = Instant.now();
        this.status = TestStatus.RUNNING;
        this.message = "Upload started";
    }

    public void progress(long bytesTransferred, int completedParts, double currentSpeedMiBps, double averageSpeedMiBps) {
        this.bytesTransferred = bytesTransferred;
        this.completedParts = completedParts;
        this.currentSpeedMiBps = currentSpeedMiBps;
        this.averageSpeedMiBps = averageSpeedMiBps;
        this.message = "Part " + completedParts + " of " + totalParts + " completed";
    }

    public void complete() { status = TestStatus.COMPLETED; finishedAt = Instant.now(); message = "Test completed"; }
    public void fail(String error) { status = TestStatus.FAILED; finishedAt = Instant.now(); message = error; }

    public Snapshot snapshot() {
        long total = request.objectSizeBytes();
        double percent = total == 0 ? 0 : Math.min(100.0, bytesTransferred * 100.0 / total);
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, request.endpoint(), request.bucket(),
                request.region(), request.objectKey(), total, bytesTransferred, completedParts, totalParts,
                percent, currentSpeedMiBps, averageSpeedMiBps, message);
    }

    public record Snapshot(UUID id, TestStatus status, Instant createdAt, Instant startedAt, Instant finishedAt,
                           String endpoint, String bucket, String region, String objectKey, long totalBytes,
                           long bytesTransferred, int completedParts, int totalParts, double percent,
                           double currentSpeedMiBps, double averageSpeedMiBps, String message) { }
}
