package dev.phibus.s3.test;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TestRequest(
        @NotBlank String endpoint,
        @NotBlank String bucket,
        @NotBlank String region,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        @NotBlank String objectKey,
        @Min(1) @Max(1024 * 1024) long objectSizeMiB,
        @Min(5) @Max(5120) long partSizeMiB,
        @Min(1) @Max(32) int parallelism,
        @Min(1) @Max(1000) int objectCount,
        boolean deleteAfterTest,
        @Pattern(regexp = "UPLOAD|DOWNLOAD|HEAD|LIST|DELETE|LIFECYCLE", message = "Unsupported operation") String operation,
        @Pattern(regexp = "OBJECT_COUNT|TIME_DURATION", message = "Unsupported execution mode") String executionMode,
        @Min(1) @Max(604800) long durationSeconds,
        @Min(0) @Max(86400) long warmupSeconds) {

    public TestRequest(String endpoint, String bucket, String region, String accessKey, String secretKey,
                       boolean pathStyleAccess, String objectKey, long objectSizeMiB, long partSizeMiB,
                       int parallelism, int objectCount, boolean deleteAfterTest, String operation) {
        this(endpoint, bucket, region, accessKey, secretKey, pathStyleAccess, objectKey, objectSizeMiB,
                partSizeMiB, parallelism, objectCount, deleteAfterTest, operation,
                "OBJECT_COUNT", 60, 0);
    }

    public long objectSizeBytes() { return Math.multiplyExact(objectSizeMiB, 1024L * 1024L); }
    public long partSizeBytes() { return Math.multiplyExact(partSizeMiB, 1024L * 1024L); }
    public String normalizedOperation() { return operation == null || operation.isBlank() ? "UPLOAD" : operation; }
    public String normalizedExecutionMode() {
        return executionMode == null || executionMode.isBlank() ? "OBJECT_COUNT" : executionMode;
    }
    public boolean durationMode() { return "TIME_DURATION".equals(normalizedExecutionMode()); }
    public long effectiveDurationSeconds() { return durationMode() ? Math.max(1, durationSeconds) : 0; }
    public long effectiveWarmupSeconds() { return durationMode() ? Math.max(0, warmupSeconds) : 0; }

    public long totalBytes() {
        if (durationMode()) return 0;
        return switch (normalizedOperation()) {
            case "UPLOAD", "DOWNLOAD" -> Math.multiplyExact(objectSizeBytes(), objectCount);
            case "LIFECYCLE" -> Math.multiplyExact(Math.multiplyExact(objectSizeBytes(), objectCount), 2L);
            default -> 0L;
        };
    }
}
