package dev.phibus.s3.api;

import dev.phibus.s3.test.TestRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public record S3RunRequest(
        @NotNull UUID profileId,
        String objectKey,
        @Min(1) @Max(1024 * 1024) Long objectSizeMiB,
        @Min(5) @Max(5120) Long partSizeMiB,
        @Min(1) @Max(32) Integer parallelism,
        @Min(1) @Max(1000) Integer objectCount,
        Boolean deleteAfterTest,
        @Pattern(regexp = "UPLOAD|DOWNLOAD|HEAD|LIST|DELETE|LIFECYCLE|MIXED") String operation,
        @Pattern(regexp = "OBJECT_COUNT|TIME_DURATION") String executionMode,
        @Min(1) @Max(604800) Long durationSeconds,
        @Min(0) @Max(86400) Long warmupSeconds,
        String workloadProfile,
        Map<String, Integer> workloadWeights,
        @Min(0) @Max(100000) Integer targetOperationsPerSecond,
        Map<String, Integer> operationThreads) {

    private static final String DEFAULT_OBJECT_KEY = "performance-test/random-upload.bin";
    private static final Set<String> OPERATIONS = Set.of(
            "UPLOAD", "DOWNLOAD", "HEAD", "LIST", "DELETE", "LIFECYCLE", "MIXED");
    private static final Set<String> MIXED_OPERATIONS = Set.of(
            "UPLOAD", "DOWNLOAD", "HEAD", "LIST", "DELETE");
    private static final Set<String> WORKLOAD_PROFILES = Set.of(
            "CUSTOM", "INGESTION", "ANALYTICS", "ETL", "BACKUP", "ARCHIVE");

    public S3RunRequest normalized() {
        if (profileId == null) throw new IllegalArgumentException("profileId is required");
        String effectiveObjectKey = defaultText(objectKey, DEFAULT_OBJECT_KEY);
        long effectiveObjectSize = defaultLong(objectSizeMiB, 32);
        long effectivePartSize = defaultLong(partSizeMiB, 8);
        int effectiveParallelism = defaultInt(parallelism, 4);
        int effectiveObjectCount = defaultInt(objectCount, 1);
        String effectiveOperation = defaultText(operation, "UPLOAD").toUpperCase(Locale.ROOT);
        String effectiveExecutionMode = defaultText(executionMode, "OBJECT_COUNT").toUpperCase(Locale.ROOT);
        long effectiveDuration = defaultLong(durationSeconds, 60);
        long effectiveWarmup = defaultLong(warmupSeconds, 0);
        String effectiveWorkloadProfile = defaultText(workloadProfile, "CUSTOM").toUpperCase(Locale.ROOT);
        int effectiveTargetOps = defaultInt(targetOperationsPerSecond, 0);

        requireRange("objectSizeMiB", effectiveObjectSize, 1, 1024L * 1024L);
        requireRange("partSizeMiB", effectivePartSize, 5, 5120);
        requireRange("parallelism", effectiveParallelism, 1, 32);
        requireRange("objectCount", effectiveObjectCount, 1, 1000);
        requireRange("durationSeconds", effectiveDuration, 1, 604800);
        requireRange("warmupSeconds", effectiveWarmup, 0, 86400);
        requireRange("targetOperationsPerSecond", effectiveTargetOps, 0, 100000);
        if (!OPERATIONS.contains(effectiveOperation)) {
            throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
        if (!Set.of("OBJECT_COUNT", "TIME_DURATION").contains(effectiveExecutionMode)) {
            throw new IllegalArgumentException("Unsupported executionMode: " + executionMode);
        }
        if (!WORKLOAD_PROFILES.contains(effectiveWorkloadProfile)) {
            throw new IllegalArgumentException("Unsupported workloadProfile: " + workloadProfile);
        }

        Map<String, Integer> effectiveWeights = normalizedMap(
                "workloadWeights", workloadWeights, MIXED_OPERATIONS);
        Map<String, Integer> effectiveThreads = normalizedMap(
                "operationThreads", operationThreads, MIXED_OPERATIONS);
        if ("MIXED".equals(effectiveOperation)) {
            if (!"TIME_DURATION".equals(effectiveExecutionMode)) {
                throw new IllegalArgumentException("MIXED workload requires TIME_DURATION executionMode");
            }
            if (effectiveWeights.isEmpty() && "CUSTOM".equals(effectiveWorkloadProfile)) {
                throw new IllegalArgumentException(
                        "CUSTOM mixed workload requires workloadWeights totaling 100");
            }
            if (!effectiveWeights.isEmpty()) {
                long totalWeight = effectiveWeights.values().stream().mapToLong(Integer::longValue).sum();
                if (totalWeight != 100) {
                    throw new IllegalArgumentException(
                            "workloadWeights must total 100, actual: " + totalWeight);
                }
            }
        }

        return new S3RunRequest(profileId, effectiveObjectKey, effectiveObjectSize, effectivePartSize,
                effectiveParallelism, effectiveObjectCount, deleteAfterTest == null || deleteAfterTest,
                effectiveOperation, effectiveExecutionMode, effectiveDuration, effectiveWarmup,
                effectiveWorkloadProfile, effectiveWeights, effectiveTargetOps, effectiveThreads);
    }

    public TestRequest toTestRequest() {
        S3RunRequest request = normalized();
        return new TestRequest("", "", "", null, null, false, request.objectKey,
                request.objectSizeMiB, request.partSizeMiB, request.parallelism, request.objectCount,
                request.deleteAfterTest, request.operation, request.executionMode, request.durationSeconds,
                request.warmupSeconds, request.workloadProfile, request.workloadWeights,
                request.targetOperationsPerSecond, request.operationThreads, request.profileId);
    }

    private static Map<String, Integer> normalizedMap(
            String name, Map<String, Integer> values, Set<String> allowedKeys) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Integer> result = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException(name + " contains a blank key");
            if (value == null || value < 0) throw new IllegalArgumentException(name + " contains a negative value");
            String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
            if (!allowedKeys.contains(normalizedKey)) {
                throw new IllegalArgumentException(name + " contains unsupported operation: " + key);
            }
            if (result.put(normalizedKey, value) != null) {
                throw new IllegalArgumentException(name + " contains duplicate operation: " + key);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long defaultLong(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void requireRange(String name, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
