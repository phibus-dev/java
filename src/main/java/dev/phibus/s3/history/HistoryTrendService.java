package dev.phibus.s3.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HistoryTrendService {
    private final AdvancedHistoryStore historyStore;

    public HistoryTrendService(AdvancedHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    public TrendReport build(List<UUID> requestedIds) {
        if (requestedIds == null) throw new IllegalArgumentException("Run identifiers are required");
        Set<UUID> unique = new LinkedHashSet<>(requestedIds);
        if (unique.size() < 2 || unique.size() > 20) {
            throw new IllegalArgumentException("Select from 2 to 20 unique runs");
        }

        List<TrendPoint> points = new ArrayList<>();
        for (UUID id : unique) {
            AdvancedHistoryStore.Detail detail = historyStore.get(id);
            if (detail == null) throw new IllegalArgumentException("History item not found: " + id);
            AdvancedHistoryStore.RunRow run = detail.run();
            points.add(new TrendPoint(run.id(), run.createdAt(), run.endpoint(), run.bucket(), run.operation(),
                    run.status(), run.averageSpeedMiBps(), operationsPerSecond(run), run.p50LatencyMs(),
                    run.p95LatencyMs(), run.p99LatencyMs(), run.failedParts(), durationMs(run)));
        }
        points.sort(Comparator.comparing(TrendPoint::createdAt));

        TrendPoint first = points.getFirst();
        TrendPoint last = points.getLast();
        return new TrendReport(points, group(points),
                change(last.throughputMiBps(), first.throughputMiBps()),
                change(last.operationsPerSecond(), first.operationsPerSecond()),
                change(last.p95LatencyMs(), first.p95LatencyMs()),
                change(last.p99LatencyMs(), first.p99LatencyMs()),
                last.errors() - first.errors());
    }

    private static Grouping group(List<TrendPoint> points) {
        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> buckets = new LinkedHashSet<>();
        Set<String> operations = new LinkedHashSet<>();
        for (TrendPoint point : points) {
            endpoints.add(point.endpoint());
            buckets.add(point.bucket());
            operations.add(point.operation());
        }
        return new Grouping(List.copyOf(endpoints), List.copyOf(buckets), List.copyOf(operations),
                endpoints.size() == 1 && buckets.size() == 1 && operations.size() == 1);
    }

    private static double operationsPerSecond(AdvancedHistoryStore.RunRow run) {
        long duration = durationMs(run);
        return duration <= 0 ? 0 : run.successfulParts() * 1000.0 / duration;
    }

    private static long durationMs(AdvancedHistoryStore.RunRow run) {
        return run.startedAt() == null || run.finishedAt() == null
                ? 0 : java.time.Duration.between(run.startedAt(), run.finishedAt()).toMillis();
    }

    private static double change(double current, double previous) {
        if (previous == 0) {
            if (current == 0) return 0;
            return current > 0 ? 100.0 : -100.0;
        }
        return (current - previous) * 100.0 / previous;
    }

    public record TrendReport(List<TrendPoint> points, Grouping grouping,
                              double throughputChangePercent, double operationsChangePercent,
                              double p95ChangePercent, double p99ChangePercent, int errorDifference) { }

    public record TrendPoint(UUID id, Instant createdAt, String endpoint, String bucket, String operation,
                             String status, double throughputMiBps, double operationsPerSecond,
                             double p50LatencyMs, double p95LatencyMs, double p99LatencyMs,
                             int errors, long durationMs) { }

    public record Grouping(List<String> endpoints, List<String> buckets, List<String> operations,
                           boolean homogeneous) { }
}
