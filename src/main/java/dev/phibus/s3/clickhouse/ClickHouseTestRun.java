package dev.phibus.s3.clickhouse;

import dev.phibus.s3.test.LatencyHistogramCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.ConcurrentHistogram;

public final class ClickHouseTestRun {
    private static final int MAX_ERROR_MESSAGES = 20;
    private static final long MAX_TRACKABLE_LATENCY_MS = TimeUnit.DAYS.toMillis(7);

    private final UUID id = UUID.randomUUID();
    private final ClickHouseTestRequest request;
    private final String endpoint;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong rows = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong selectQueriesStartedAt = new AtomicLong(-1);
    private final ConcurrentHistogram latencyHistogram =
            new ConcurrentHistogram(1, MAX_TRACKABLE_LATENCY_MS, 3);
    private final List<String> errorMessages = new ArrayList<>();
    private volatile Status status = Status.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant measurementStartedAt;
    private volatile Instant finishedAt;
    private volatile long startedNanos;
    private volatile long measurementStartedNanos;
    private volatile long finishedNanos;
    private volatile String message = "Queued";

    public ClickHouseTestRun(ClickHouseTestRequest request, String endpoint) {
        this.request = request;
        this.endpoint = endpoint;
    }

    public UUID id() { return id; }
    public ClickHouseTestRequest request() { return request; }
    public boolean isCancelled() { return cancelled.get(); }
    public synchronized String encodedLatencyHistogram() { return LatencyHistogramCodec.encode(latencyHistogram); }

    public synchronized void cancel() {
        if (status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED) return;
        cancelled.set(true);
        status = Status.CANCELLED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        message = "Cancellation requested";
    }

    public synchronized void deadlineExceeded() {
        if (terminal()) return;
        cancelled.set(true);
        status = Status.CANCELLED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        message = "Runtime deadline exceeded";
    }

    public synchronized void start() {
        if (startedAt != null || cancelled.get()) return;
        startedAt = Instant.now();
        startedNanos = System.nanoTime();
        measurementStartedAt = startedAt.plusSeconds(request.warmupSeconds());
        measurementStartedNanos = startedNanos + TimeUnit.SECONDS.toNanos(request.warmupSeconds());
        status = Status.RUNNING;
        message = request.warmupSeconds() > 0 ? "Warm-up started" : "ClickHouse test started";
    }

    public boolean warmingUp() {
        return measurementStartedAt != null && System.nanoTime() < measurementStartedNanos;
    }

    public boolean durationExpired() {
        if (!request.durationMode() || startedAt == null) return false;
        long configuredNanos = TimeUnit.SECONDS.toNanos(request.warmupSeconds() + request.durationSeconds());
        return System.nanoTime() - startedNanos >= configuredNanos;
    }

    public synchronized void operationCompleted(long affectedRows, long transferredBytes, long latencyMs) {
        if (terminal() || warmingUp()) return;
        rows.addAndGet(Math.max(0, affectedRows));
        bytes.addAndGet(Math.max(0, transferredBytes));
        long completed = queries.incrementAndGet();
        latencyHistogram.recordValue(Math.max(1, Math.min(MAX_TRACKABLE_LATENCY_MS, latencyMs)));
        message = "Operations completed: " + completed;
    }

    public void operationFailed() { operationFailed("ClickHouse operation failed"); }

    public void beginSelectPhase() {
        selectQueriesStartedAt.compareAndSet(-1, queries.get());
    }

    public synchronized void operationFailed(String error) {
        if (terminal()) return;
        errors.incrementAndGet();
        rememberError(error);
    }

    public synchronized void complete() {
        if (terminal()) return;
        status = Status.COMPLETED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        message = "Test completed";
    }

    public synchronized void fail(String error) {
        if (terminal()) return;
        if (errors.get() == 0) errors.incrementAndGet();
        rememberError(error);
        status = Status.FAILED;
        finishedAt = Instant.now();
        finishedNanos = System.nanoTime();
        message = joinedErrors();
    }

    private boolean terminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }

    public Snapshot snapshot() {
        long nowNanos = finishedNanos == 0 ? System.nanoTime() : finishedNanos;
        long elapsedNanos = startedAt == null ? 0 : Math.max(0, nowNanos - startedNanos);
        long measuredNanos = measurementStartedAt == null || nowNanos < measurementStartedNanos
                ? 0 : Math.max(0, nowNanos - measurementStartedNanos);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        long measuredMs = TimeUnit.NANOSECONDS.toMillis(measuredNanos);
        long rowCount = rows.get();
        long byteCount = bytes.get();
        long completedQueries = queries.get();
        double seconds = measuredNanos / 1_000_000_000.0;
        double rowsPerSecond = seconds <= 0 ? 0 : rowCount / seconds;
        double mibPerSecond = seconds <= 0 ? 0 : (byteCount / 1024.0 / 1024.0) / seconds;
        double queriesPerSecond = seconds <= 0 ? 0 : completedQueries / seconds;
        double progress = progress(measuredMs, rowCount, completedQueries);
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, endpoint, request.normalizedTable(),
                request.normalizedOperation(), elapsedMs, progress, rowCount, byteCount, completedQueries, errors.get(),
                rowsPerSecond, mibPerSecond, queriesPerSecond, percentile(50), percentile(95),
                percentile(99), message);
    }

    private double progress(long measuredMs, long rowCount, long completedQueries) {
        if (request.durationMode()) {
            return Math.min(100.0, measuredMs * 100.0 / (request.durationSeconds() * 1000.0));
        }
        return switch (request.normalizedOperation()) {
            case "SELECT" -> Math.min(100.0, completedQueries * 100.0 / request.effectiveQueryCount());
            case "INSERT_SELECT" -> {
                long baseline = selectQueriesStartedAt.get();
                long selectQueries = baseline < 0 ? 0 : Math.max(0, completedQueries - baseline);
                yield Math.min(50.0, rowCount * 50.0 / request.rowCount())
                        + Math.min(50.0, selectQueries * 50.0 / request.effectiveQueryCount());
            }
            default -> Math.min(100.0, rowCount * 100.0 / request.rowCount());
        };
    }

    private void rememberError(String error) {
        String normalized = error == null || error.isBlank() ? "Unknown ClickHouse error" : error.trim();
        synchronized (errorMessages) {
            if (errorMessages.contains(normalized)) return;
            if (errorMessages.size() == MAX_ERROR_MESSAGES) errorMessages.removeFirst();
            errorMessages.add(normalized);
        }
    }

    private String joinedErrors() {
        synchronized (errorMessages) {
            return errorMessages.isEmpty() ? "ClickHouse test failed" : String.join("\n", errorMessages);
        }
    }

    private double percentile(double value) {
        return latencyHistogram.getTotalCount() == 0 ? 0 : latencyHistogram.getValueAtPercentile(value);
    }

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Snapshot(UUID id, Status status, Instant createdAt, Instant startedAt, Instant finishedAt,
                           String endpoint, String table, String operation, long elapsedMillis, double percent,
                           long rows, long bytes, long queries, long errors, double rowsPerSecond,
                           double mibPerSecond, double queriesPerSecond, double p50LatencyMs,
                           double p95LatencyMs, double p99LatencyMs, String message) { }
}
