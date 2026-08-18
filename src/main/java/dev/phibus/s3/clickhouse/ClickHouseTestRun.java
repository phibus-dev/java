package dev.phibus.s3.clickhouse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClickHouseTestRun {
    private final UUID id = UUID.randomUUID();
    private final ClickHouseTestRequest request;
    private final String endpoint;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong rows = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final List<Long> latenciesMs = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> errorMessages = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile Status status = Status.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant measurementStartedAt;
    private volatile Instant finishedAt;
    private volatile String message = "Queued";

    public ClickHouseTestRun(ClickHouseTestRequest request, String endpoint) {
        this.request = request;
        this.endpoint = endpoint;
    }

    public UUID id() { return id; }
    public ClickHouseTestRequest request() { return request; }
    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); status = Status.CANCELLED; finishedAt = Instant.now(); message = "Cancellation requested"; }

    public synchronized void start() {
        if (startedAt != null) return;
        startedAt = Instant.now();
        measurementStartedAt = startedAt.plusSeconds(request.warmupSeconds());
        status = Status.RUNNING;
        message = request.warmupSeconds() > 0 ? "Warm-up started" : "ClickHouse test started";
    }

    public boolean warmingUp() {
        return measurementStartedAt != null && Instant.now().isBefore(measurementStartedAt);
    }

    public boolean durationExpired() {
        return request.durationMode() && startedAt != null
                && !Instant.now().isBefore(startedAt.plusSeconds(request.warmupSeconds() + request.durationSeconds()));
    }

    public void operationCompleted(long affectedRows, long transferredBytes, long latencyMs) {
        if (warmingUp()) return;
        rows.addAndGet(Math.max(0, affectedRows));
        bytes.addAndGet(Math.max(0, transferredBytes));
        queries.incrementAndGet();
        latenciesMs.add(Math.max(0, latencyMs));
        message = "Operations completed: " + queries.get();
    }

    public void operationFailed() { operationFailed("ClickHouse operation failed"); }

    public void operationFailed(String error) {
        errors.incrementAndGet();
        rememberError(error);
    }

    public void complete() {
        if (!cancelled.get()) {
            status = Status.COMPLETED;
            finishedAt = Instant.now();
            message = "Test completed";
        }
    }

    public void fail(String error) {
        if (!cancelled.get()) {
            if (errors.get() == 0) errors.incrementAndGet();
            rememberError(error);
            status = Status.FAILED;
            finishedAt = Instant.now();
            message = joinedErrors();
        }
    }

    public Snapshot snapshot() {
        Instant now = finishedAt == null ? Instant.now() : finishedAt;
        Instant measurementStart = measurementStartedAt == null ? now : measurementStartedAt;
        long elapsedMs = startedAt == null ? 0 : Math.max(0, Duration.between(startedAt, now).toMillis());
        long measuredMs = now.isBefore(measurementStart) ? 0 : Math.max(0, Duration.between(measurementStart, now).toMillis());
        long rowCount = rows.get();
        long byteCount = bytes.get();
        long queryCount = queries.get();
        double seconds = measuredMs / 1000.0;
        double rowsPerSecond = seconds <= 0 ? 0 : rowCount / seconds;
        double mibPerSecond = seconds <= 0 ? 0 : (byteCount / 1024.0 / 1024.0) / seconds;
        double queriesPerSecond = seconds <= 0 ? 0 : queryCount / seconds;
        List<Long> copy;
        synchronized (latenciesMs) { copy = List.copyOf(latenciesMs); }
        double progress = request.durationMode()
                ? (request.durationSeconds() == 0 ? 0 : Math.min(100.0, measuredMs * 100.0 / (request.durationSeconds() * 1000.0)))
                : Math.min(100.0, rowCount * 100.0 / Math.max(1, request.rowCount()));
        return new Snapshot(id, status, createdAt, startedAt, finishedAt, endpoint, request.normalizedTable(),
                request.normalizedOperation(), elapsedMs, progress, rowCount, byteCount, queryCount, errors.get(),
                rowsPerSecond, mibPerSecond, queriesPerSecond, percentile(copy, 50), percentile(copy, 95),
                percentile(copy, 99), message);
    }

    private void rememberError(String error) {
        String normalized = error == null || error.isBlank() ? "Unknown ClickHouse error" : error.trim();
        synchronized (errorMessages) {
            if (!errorMessages.contains(normalized)) errorMessages.add(normalized);
        }
    }

    private String joinedErrors() {
        synchronized (errorMessages) {
            return errorMessages.isEmpty() ? "ClickHouse test failed" : String.join("\n", errorMessages);
        }
    }

    private static double percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public record Snapshot(UUID id, Status status, Instant createdAt, Instant startedAt, Instant finishedAt,
                           String endpoint, String table, String operation, long elapsedMillis, double percent,
                           long rows, long bytes, long queries, long errors, double rowsPerSecond,
                           double mibPerSecond, double queriesPerSecond, double p50LatencyMs,
                           double p95LatencyMs, double p99LatencyMs, String message) { }
}
