package dev.phibus.s3.test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TestRun {
    private final UUID id = UUID.randomUUID();
    private final TestRequest request;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong bytesTransferred = new AtomicLong();
    private final AtomicInteger completedParts = new AtomicInteger();
    private final List<PartResult> parts = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile TestStatus status = TestStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile int totalParts;
    private volatile double currentSpeedMiBps;
    private volatile String message = "Queued";
    private volatile boolean cleanupSuccessful;

    public TestRun(TestRequest request) { this.request = request; }
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

    public void partCompleted(PartResult result) {
        parts.add(result);
        bytesTransferred.addAndGet(result.bytes());
        int done = completedParts.incrementAndGet();
        currentSpeedMiBps = result.speedMiBps();
        message = "Part " + done + " of " + totalParts + " completed";
    }

    public void cleanupSuccessful() { cleanupSuccessful = true; }
    public void complete() { status = TestStatus.COMPLETED; finishedAt = Instant.now(); message = "Test completed"; }
    public void fail(String error) { status = TestStatus.FAILED; finishedAt = Instant.now(); message = error; }

    public Snapshot snapshot() {
        long total = request.totalBytes();
        long transferred = bytesTransferred.get();
        double percent = total == 0 ? 0 : Math.min(100.0, transferred * 100.0 / total);
        long durationMillis = startedAt == null ? 0 : Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt).toMillis();
        double average = durationMillis == 0 ? 0 : (transferred / 1024.0 / 1024.0) / (durationMillis / 1000.0);
        List<PartResult> copy;
        synchronized (parts) { copy = List.copyOf(parts); }
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, request.endpoint(), request.bucket(),
                request.region(), request.objectKey(), total, transferred, completedParts.get(), totalParts,
                percent, currentSpeedMiBps, average, percentile(copy, 50), percentile(copy, 95), percentile(copy, 99),
                copy.size(), (int) copy.stream().filter(p -> !"SUCCESS".equals(p.status())).count(),
                request.deleteAfterTest(), cleanupSuccessful, message, copy);
    }

    private static double percentile(List<PartResult> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().map(PartResult::durationMillis).sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public record Snapshot(UUID id, TestStatus status, Instant createdAt, Instant startedAt, Instant finishedAt,
                           String endpoint, String bucket, String region, String objectKey, long totalBytes,
                           long bytesTransferred, int completedParts, int totalParts, double percent,
                           double currentSpeedMiBps, double averageSpeedMiBps, double p50LatencyMs,
                           double p95LatencyMs, double p99LatencyMs, int successfulParts, int failedParts,
                           boolean deleteAfterTest, boolean cleanupSuccessful, String message,
                           List<PartResult> parts) { }
}
