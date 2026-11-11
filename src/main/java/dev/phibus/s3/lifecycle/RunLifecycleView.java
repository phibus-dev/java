package dev.phibus.s3.lifecycle;

import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.UUID;

public record RunLifecycleView(UUID id, String name, TestType testType, RunScope scope, RunStatus status,
                               Instant createdAt, Instant startedAt, Instant updatedAt, Instant finishedAt,
                               RunStopReason stopReason, String message, long completedOperations,
                               long rowsProcessed, long bytesTransferred, double operationsPerSecond,
                               double throughputMiBps, double p50LatencyMs, double p95LatencyMs,
                               double p99LatencyMs, long errors, int participants, String latencyScope,
                               String detailsUrl) { }
