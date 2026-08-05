package dev.phibus.s3.test;

public record PartResult(
        int objectNumber,
        int partNumber,
        long bytes,
        long durationMillis,
        double speedMiBps,
        String eTag,
        String status,
        String error) {
}
