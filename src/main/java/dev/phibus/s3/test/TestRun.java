package dev.phibus.s3.test;

import dev.phibus.s3.lifecycle.RunScope;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.ConcurrentHistogram;

public final class TestRun {
    static final int DEFAULT_MAX_RETAINED_DETAILS = 10_000;
    private static final int LATENCY_WINDOW_SIZE = 100;
    private static final long THROUGHPUT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long MAX_TRACKABLE_LATENCY_MS = TimeUnit.DAYS.toMillis(7);

    private final UUID id = UUID.randomUUID();
    private final TestRequest request;
    private final TestType testType;
    private final RunScope scope;
    private final Instant createdAt = Instant.now();
    private final int maxRetainedDetails;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong bytesTransferred = new AtomicLong();
    private final AtomicLong completedParts = new AtomicLong();
    private final AtomicLong successfulParts = new AtomicLong();
    private final AtomicLong failedParts = new AtomicLong();
    private final AtomicLong apiRequests = new AtomicLong();
    private final AtomicLong objectOperations = new AtomicLong();
    private final Deque<PartResult> parts = new ArrayDeque<>();
    private final Deque<Long> recentLatenciesMs = new ArrayDeque<>();
    private final Deque<TransferSample> transferWindow = new ArrayDeque<>();
    private final ConcurrentHistogram latencyHistogram =
            new ConcurrentHistogram(1, MAX_TRACKABLE_LATENCY_MS, 3);
    private final ConcurrentHistogram apiRequestLatencyHistogram =
            new ConcurrentHistogram(1, MAX_TRACKABLE_LATENCY_MS, 3);
    private final ConcurrentHistogram objectOperationLatencyHistogram =
            new ConcurrentHistogram(1, MAX_TRACKABLE_LATENCY_MS, 3);
    private volatile TestStatus status = TestStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant measurementStartedAt;
    private volatile Instant finishedAt;
    private volatile long startedNanos;
    private volatile long measurementStartedNanos;
    private volatile long finishedNanos;
    private volatile long totalParts;
    private volatile String message = "Queued";
    private volatile boolean cleanupSuccessful;
    private volatile boolean detailsTruncated;
    private volatile String stopReason = "NORMAL";

    public TestRun(TestRequest request) {
        this(TestType.S3, RunScope.LOCAL, request, DEFAULT_MAX_RETAINED_DETAILS);
    }

    TestRun(TestRequest request, int maxRetainedDetails) {
        this(TestType.S3, RunScope.LOCAL, request, maxRetainedDetails);
    }

    TestRun(TestType testType, RunScope scope, TestRequest request) {
        this(testType, scope, request, DEFAULT_MAX_RETAINED_DETAILS);
    }

    TestRun(TestType testType, RunScope scope, TestRequest request, int maxRetainedDetails) {
        this.testType = testType;
        this.scope = scope;
        this.request = request;
        this.maxRetainedDetails = Math.max(1, maxRetainedDetails);
    }

    public UUID id() { return id; }
    public TestRequest request() { return request; }
    public boolean isCancelled() { return cancelled.get(); }

    public synchronized void cancel() {
        if (status == TestStatus.COMPLETED || status == TestStatus.FAILED || status == TestStatus.CANCELLED) return;
        cancelled.set(true);
        status = TestStatus.CANCELLED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        stopReason = "USER_CANCELLED";
        message = "Cancellation requested";
    }

    public synchronized void deadlineExceeded() {
        if (terminal()) return;
        cancelled.set(true);
        status = TestStatus.CANCELLED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        stopReason = "DEADLINE_EXCEEDED";
        message = "Runtime deadline exceeded";
    }

    public synchronized void start(int requestedParts) {
        if (cancelled.get()) return;
        if (startedAt == null) {
            startedAt = Instant.now();
            startedNanos = System.nanoTime();
            measurementStartedAt = startedAt.plusSeconds(request.effectiveWarmupSeconds());
            measurementStartedNanos = startedNanos + TimeUnit.SECONDS.toNanos(request.effectiveWarmupSeconds());
            status = TestStatus.RUNNING;
            message = request.effectiveWarmupSeconds() > 0
                    ? "Warm-up started" : request.normalizedOperation() + " started";
        }
        if (request.durationMode()) totalParts += Math.max(0, requestedParts);
        else totalParts = requestedParts;
    }

    public boolean durationExpired() {
        if (!request.durationMode() || startedAt == null) return false;
        long durationNanos = TimeUnit.SECONDS.toNanos(
                request.effectiveWarmupSeconds() + request.effectiveDurationSeconds());
        return System.nanoTime() - startedNanos >= durationNanos;
    }

    public boolean warmingUp() {
        return measurementStartedAt != null && System.nanoTime() < measurementStartedNanos;
    }

    public synchronized void partCompleted(PartResult result) {
        if (terminal()) return;
        if (warmingUp()) {
            message = "Warm-up: operation completed";
            return;
        }
        record(result);
    }

    public synchronized void operationFailed(int objectNumber, int partNumber, String operation,
                                             long durationMillis, String error) {
        if (terminal()) return;
        record(new PartResult(objectNumber, partNumber, 0, Math.max(0, durationMillis), 0,
                operation, "FAILED", error));
    }

    public synchronized void apiRequestCompleted(long durationMillis) {
        if (terminal() || warmingUp()) return;
        apiRequests.incrementAndGet();
        apiRequestLatencyHistogram.recordValue(normalizeLatency(durationMillis));
    }

    public synchronized void objectOperationCompleted(long durationMillis) {
        if (terminal() || warmingUp()) return;
        objectOperations.incrementAndGet();
        objectOperationLatencyHistogram.recordValue(normalizeLatency(durationMillis));
    }

    public synchronized String encodedLatencyHistogram() {
        return LatencyHistogramCodec.encode(latencyHistogram);
    }

    private void record(PartResult result) {
        boolean success = "SUCCESS".equals(result.status());
        long latency = normalizeLatency(result.durationMillis());
        latencyHistogram.recordValue(latency);
        synchronized (recentLatenciesMs) {
            if (recentLatenciesMs.size() == LATENCY_WINDOW_SIZE) recentLatenciesMs.removeFirst();
            recentLatenciesMs.addLast(latency);
        }
        synchronized (parts) {
            if (parts.size() == maxRetainedDetails) {
                parts.removeFirst();
                detailsTruncated = true;
            }
            parts.addLast(result);
        }
        if (success) {
            successfulParts.incrementAndGet();
            bytesTransferred.addAndGet(Math.max(0, result.bytes()));
            if (result.bytes() > 0) rememberTransfer(result.bytes());
        } else {
            failedParts.incrementAndGet();
        }
        long done = completedParts.incrementAndGet();
        message = request.durationMode()
                ? "Operation " + done + " completed"
                : "Operation " + done + " of " + totalParts + " completed";
    }

    private void rememberTransfer(long bytes) {
        long now = System.nanoTime();
        synchronized (transferWindow) {
            transferWindow.addLast(new TransferSample(now, bytes));
            trimTransferWindow(now);
        }
    }

    private void trimTransferWindow(long now) {
        long cutoff = now - THROUGHPUT_WINDOW_NANOS;
        while (!transferWindow.isEmpty() && transferWindow.getFirst().completedAtNanos() < cutoff) {
            transferWindow.removeFirst();
        }
    }

    public void cleanupSuccessful() { cleanupSuccessful = true; }

    public synchronized void complete() {
        if (terminal()) return;
        status = TestStatus.COMPLETED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        stopReason = request.durationMode() ? "TIMEOUT" : "NORMAL";
        message = "Test completed";
    }

    public synchronized void fail(String error) {
        if (terminal()) return;
        if (failedParts.get() == 0) {
            operationFailed(0, 0, request.normalizedOperation(), 0, error);
        }
        status = TestStatus.FAILED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        stopReason = "ERROR";
        message = error;
    }

    private boolean terminal() {
        return status == TestStatus.COMPLETED || status == TestStatus.FAILED || status == TestStatus.CANCELLED;
    }

    public Snapshot snapshot() {
        return snapshot(true);
    }

    Snapshot summary() {
        return snapshot(false);
    }

    private Snapshot snapshot(boolean includeDetails) {
        long total = request.totalBytes();
        long transferred = bytesTransferred.get();
        long completed = completedParts.get();
        long nowNanos = finishedNanos == 0 ? System.nanoTime() : finishedNanos;
        long elapsedNanos = startedAt == null ? 0 : Math.max(0, nowNanos - startedNanos);
        long measurementNanos = measurementStartedAt == null || nowNanos < measurementStartedNanos
                ? 0 : Math.max(0, nowNanos - measurementStartedNanos);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        long measurementMillis = TimeUnit.NANOSECONDS.toMillis(measurementNanos);
        long configuredMillis = request.durationMode() ? request.effectiveDurationSeconds() * 1000L : 0;
        long remainingMillis = request.durationMode() ? Math.max(0, configuredMillis - measurementMillis) : 0;
        double percent = request.durationMode()
                ? Math.min(100.0, configuredMillis == 0 ? 0 : measurementMillis * 100.0 / configuredMillis)
                : total > 0 ? Math.min(100.0, transferred * 100.0 / total)
                : totalParts == 0 ? 0 : Math.min(100.0, completed * 100.0 / totalParts);
        double measurementSeconds = measurementNanos / 1_000_000_000.0;
        double average = measurementSeconds <= 0 ? 0 : (transferred / 1024.0 / 1024.0) / measurementSeconds;
        double operationsPerSecond = measurementSeconds <= 0 ? 0 : completed / measurementSeconds;
        List<PartResult> copy;
        synchronized (parts) { copy = includeDetails ? List.copyOf(parts) : List.of(); }
        List<Long> window;
        synchronized (recentLatenciesMs) { window = new ArrayList<>(recentLatenciesMs); }
        window.sort(Comparator.naturalOrder());
        Instant updatedAt = finishedAt != null ? finishedAt : startedAt != null ? Instant.now() : createdAt;
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, request.endpoint(), request.bucket(),
                request.region(), request.objectKey(), request.normalizedOperation(), request.normalizedExecutionMode(),
                request.effectiveDurationSeconds(), request.effectiveWarmupSeconds(), elapsedMillis, remainingMillis,
                warmingUp(), stopReason, total, transferred, completed, totalParts, percent, rollingSpeed(nowNanos), average,
                operationsPerSecond, histogramPercentile(50), histogramPercentile(95), histogramPercentile(99),
                percentile(window, 50), percentile(window, 95), percentile(window, 99),
                successfulParts.get(), failedParts.get(), request.deleteAfterTest(), cleanupSuccessful,
                detailsTruncated, message, copy, S3LatencyScope.forRequest(request).name(),
                latencyHistogram.getTotalCount(),
                apiRequests.get(), histogramPercentile(apiRequestLatencyHistogram, 50),
                histogramPercentile(apiRequestLatencyHistogram, 95),
                histogramPercentile(apiRequestLatencyHistogram, 99), objectOperations.get(),
                histogramPercentile(objectOperationLatencyHistogram, 50),
                histogramPercentile(objectOperationLatencyHistogram, 95),
                histogramPercentile(objectOperationLatencyHistogram, 99), testType, scope, updatedAt);
    }

    private double rollingSpeed(long nowNanos) {
        if (measurementStartedAt == null || nowNanos <= measurementStartedNanos) return 0;
        synchronized (transferWindow) {
            trimTransferWindow(nowNanos);
            long bytes = transferWindow.stream().mapToLong(TransferSample::bytes).sum();
            long windowStart = Math.max(measurementStartedNanos, nowNanos - THROUGHPUT_WINDOW_NANOS);
            double seconds = (nowNanos - windowStart) / 1_000_000_000.0;
            return seconds <= 0 ? 0 : bytes / 1024.0 / 1024.0 / seconds;
        }
    }

    private double histogramPercentile(double percentile) {
        return histogramPercentile(latencyHistogram, percentile);
    }

    private static double histogramPercentile(ConcurrentHistogram histogram, double percentile) {
        return histogram.getTotalCount() == 0 ? 0 : histogram.getValueAtPercentile(percentile);
    }

    private static long normalizeLatency(long durationMillis) {
        return Math.max(1, Math.min(MAX_TRACKABLE_LATENCY_MS, durationMillis));
    }

    private static double percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private record TransferSample(long completedAtNanos, long bytes) { }

    public record Snapshot(UUID id, TestStatus status, Instant createdAt, Instant startedAt, Instant finishedAt,
                           String endpoint, String bucket, String region, String objectKey, String operation,
                           String executionMode, long configuredDurationSeconds, long warmupSeconds,
                           long elapsedMillis, long remainingMillis, boolean warmingUp, String stopReason,
                           long totalBytes, long bytesTransferred, long completedParts, long totalParts, double percent,
                           double currentSpeedMiBps, double averageSpeedMiBps, double operationsPerSecond,
                           double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
                           double windowP50LatencyMs, double windowP95LatencyMs, double windowP99LatencyMs,
                           long successfulParts, long failedParts, boolean deleteAfterTest,
                           boolean cleanupSuccessful, boolean detailsTruncated,
                           String message, List<PartResult> parts, String latencyScope, long latencySampleCount,
                           long apiRequestCount, double apiRequestP50LatencyMs, double apiRequestP95LatencyMs,
                           double apiRequestP99LatencyMs, long objectOperationCount,
                           double objectP50LatencyMs, double objectP95LatencyMs,
                           double objectP99LatencyMs, TestType testType, RunScope scope,
                           Instant updatedAt) { }
}
