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
    private volatile Instant measurementStartedAt;
    private volatile Instant finishedAt;
    private volatile int totalParts;
    private volatile double currentSpeedMiBps;
    private volatile String message = "Queued";
    private volatile boolean cleanupSuccessful;
    private volatile String stopReason = "NORMAL";

    public TestRun(TestRequest request) { this.request = request; }
    public UUID id() { return id; }
    public TestRequest request() { return request; }
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); status = TestStatus.CANCELLED; stopReason = "USER_CANCELLED"; message = "Cancellation requested"; }

    public synchronized void start(int requestedParts) {
        if (startedAt == null) {
            startedAt = Instant.now();
            measurementStartedAt = startedAt.plusSeconds(request.effectiveWarmupSeconds());
            status = TestStatus.RUNNING;
            message = request.effectiveWarmupSeconds() > 0 ? "Warm-up started" : request.normalizedOperation() + " started";
        }
        if (request.durationMode()) totalParts += Math.max(0, requestedParts);
        else totalParts = requestedParts;
    }

    public boolean durationExpired() {
        return request.durationMode() && startedAt != null
                && !Instant.now().isBefore(startedAt.plusSeconds(request.effectiveWarmupSeconds() + request.effectiveDurationSeconds()));
    }

    public boolean warmingUp() {
        return measurementStartedAt != null && Instant.now().isBefore(measurementStartedAt);
    }

    public void partCompleted(PartResult result) {
        if (warmingUp()) {
            message = "Warm-up: operation completed";
            return;
        }
        parts.add(result);
        bytesTransferred.addAndGet(result.bytes());
        int done = completedParts.incrementAndGet();
        currentSpeedMiBps = result.speedMiBps();
        message = request.durationMode() ? "Operation " + done + " completed" : "Operation " + done + " of " + totalParts + " completed";
    }

    public void cleanupSuccessful() { cleanupSuccessful = true; }
    public void complete() { status = TestStatus.COMPLETED; finishedAt = Instant.now(); stopReason = request.durationMode() ? "TIMEOUT" : "NORMAL"; message = "Test completed"; }
    public void fail(String error) { status = TestStatus.FAILED; finishedAt = Instant.now(); stopReason = "ERROR"; message = error; }

    public Snapshot snapshot() {
        long total = request.totalBytes();
        long transferred = bytesTransferred.get();
        int completed = completedParts.get();
        Instant now = finishedAt == null ? Instant.now() : finishedAt;
        long elapsedMillis = startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, now).toMillis());
        long measurementMillis = measurementStartedAt == null || now.isBefore(measurementStartedAt)
                ? 0 : Math.max(0, Duration.between(measurementStartedAt, now).toMillis());
        long configuredMillis = request.durationMode() ? request.effectiveDurationSeconds() * 1000L : 0;
        long remainingMillis = request.durationMode() ? Math.max(0, configuredMillis - measurementMillis) : 0;
        double percent = request.durationMode()
                ? Math.min(100.0, configuredMillis == 0 ? 0 : measurementMillis * 100.0 / configuredMillis)
                : total > 0 ? Math.min(100.0, transferred * 100.0 / total)
                : totalParts == 0 ? 0 : Math.min(100.0, completed * 100.0 / totalParts);
        double average = measurementMillis == 0 ? 0 : (transferred / 1024.0 / 1024.0) / (measurementMillis / 1000.0);
        double operationsPerSecond = measurementMillis == 0 ? 0 : completed / (measurementMillis / 1000.0);
        List<PartResult> copy;
        synchronized (parts) { copy = List.copyOf(parts); }
        List<PartResult> window = copy.size() <= 100 ? copy : copy.subList(copy.size() - 100, copy.size());
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, request.endpoint(), request.bucket(),
                request.region(), request.objectKey(), request.normalizedOperation(), request.normalizedExecutionMode(),
                request.effectiveDurationSeconds(), request.effectiveWarmupSeconds(), elapsedMillis, remainingMillis,
                warmingUp(), stopReason, total, transferred, completed, totalParts, percent, currentSpeedMiBps, average,
                operationsPerSecond, percentile(copy, 50), percentile(copy, 95), percentile(copy, 99),
                percentile(window, 50), percentile(window, 95), percentile(window, 99),
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
                           String endpoint, String bucket, String region, String objectKey, String operation,
                           String executionMode, long configuredDurationSeconds, long warmupSeconds,
                           long elapsedMillis, long remainingMillis, boolean warmingUp, String stopReason,
                           long totalBytes, long bytesTransferred, int completedParts, int totalParts, double percent,
                           double currentSpeedMiBps, double averageSpeedMiBps, double operationsPerSecond,
                           double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
                           double windowP50LatencyMs, double windowP95LatencyMs, double windowP99LatencyMs,
                           int successfulParts, int failedParts, boolean deleteAfterTest,
                           boolean cleanupSuccessful, String message, List<PartResult> parts) { }
}
