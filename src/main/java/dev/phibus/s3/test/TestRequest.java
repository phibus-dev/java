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
        @Pattern(regexp = "UPLOAD", message = "Only UPLOAD is supported") String operation) {

    public long objectSizeBytes() {
        return Math.multiplyExact(objectSizeMiB, 1024L * 1024L);
    }

    public long partSizeBytes() {
        return Math.multiplyExact(partSizeMiB, 1024L * 1024L);
    }

    public long totalBytes() {
        return Math.multiplyExact(objectSizeBytes(), objectCount);
    }
}
