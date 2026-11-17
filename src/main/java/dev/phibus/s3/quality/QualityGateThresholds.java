package dev.phibus.s3.quality;

public record QualityGateThresholds(
        Double minOperationsPerSecond,
        Double minThroughputMiBps,
        Double maxP95LatencyMs,
        Double maxP99LatencyMs,
        Double maxErrorRatePercent,
        Double maxOperationsDropPercent,
        Double maxThroughputDropPercent,
        Double maxP95IncreasePercent,
        Double maxP99IncreasePercent,
        Long minCompletedOperations,
        Integer minParticipants) {

    public QualityGateThresholds mergedWith(QualityGateThresholds overrides) {
        if (overrides == null) return this;
        return new QualityGateThresholds(
                choose(overrides.minOperationsPerSecond, minOperationsPerSecond),
                choose(overrides.minThroughputMiBps, minThroughputMiBps),
                choose(overrides.maxP95LatencyMs, maxP95LatencyMs),
                choose(overrides.maxP99LatencyMs, maxP99LatencyMs),
                choose(overrides.maxErrorRatePercent, maxErrorRatePercent),
                choose(overrides.maxOperationsDropPercent, maxOperationsDropPercent),
                choose(overrides.maxThroughputDropPercent, maxThroughputDropPercent),
                choose(overrides.maxP95IncreasePercent, maxP95IncreasePercent),
                choose(overrides.maxP99IncreasePercent, maxP99IncreasePercent),
                choose(overrides.minCompletedOperations, minCompletedOperations),
                choose(overrides.minParticipants, minParticipants));
    }

    public QualityGateThresholds validated() {
        nonNegative("minOperationsPerSecond", minOperationsPerSecond);
        nonNegative("minThroughputMiBps", minThroughputMiBps);
        nonNegative("maxP95LatencyMs", maxP95LatencyMs);
        nonNegative("maxP99LatencyMs", maxP99LatencyMs);
        percentage("maxErrorRatePercent", maxErrorRatePercent);
        percentage("maxOperationsDropPercent", maxOperationsDropPercent);
        percentage("maxThroughputDropPercent", maxThroughputDropPercent);
        nonNegative("maxP95IncreasePercent", maxP95IncreasePercent);
        nonNegative("maxP99IncreasePercent", maxP99IncreasePercent);
        if (minCompletedOperations != null && minCompletedOperations < 0) {
            throw new IllegalArgumentException("minCompletedOperations must not be negative");
        }
        if (minParticipants != null && minParticipants < 1) {
            throw new IllegalArgumentException("minParticipants must be positive");
        }
        return this;
    }

    private static <T> T choose(T override, T fallback) {
        return override == null ? fallback : override;
    }

    private static void percentage(String name, Double value) {
        nonNegative(name, value);
        if (value != null && value > 100) {
            throw new IllegalArgumentException(name + " must not exceed 100");
        }
    }

    private static void nonNegative(String name, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be a finite non-negative number");
        }
    }
}
