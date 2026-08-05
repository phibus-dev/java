package dev.phibus.s3.workload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

public final class WeightedOperationSelector {
    private static final List<String> ALLOWED = List.of("UPLOAD", "DOWNLOAD", "HEAD", "LIST", "DELETE");
    private final List<Range> ranges;
    private final SplittableRandom random;

    public WeightedOperationSelector(Map<String, Integer> weights, long seed) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (weights != null) {
            weights.forEach((operation, weight) -> {
                String op = operation == null ? "" : operation.trim().toUpperCase();
                if (!ALLOWED.contains(op)) throw new IllegalArgumentException("Unsupported workload operation: " + operation);
                if (weight == null || weight < 0) throw new IllegalArgumentException("Workload weight must be non-negative");
                if (weight > 0) normalized.put(op, weight);
            });
        }
        int total = normalized.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) throw new IllegalArgumentException("Workload weights must total 100, actual: " + total);
        this.ranges = new ArrayList<>();
        int upper = 0;
        for (Map.Entry<String, Integer> entry : normalized.entrySet()) {
            upper += entry.getValue();
            ranges.add(new Range(entry.getKey(), upper));
        }
        this.random = new SplittableRandom(seed);
    }

    public String next() {
        int value = random.nextInt(100) + 1;
        return ranges.stream().filter(r -> value <= r.upperInclusive()).findFirst().orElseThrow().operation();
    }

    private record Range(String operation, int upperInclusive) { }
}
