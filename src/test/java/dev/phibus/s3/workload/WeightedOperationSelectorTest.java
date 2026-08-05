package dev.phibus.s3.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedOperationSelectorTest {
    @Test
    void rejectsWeightsThatDoNotTotalOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedOperationSelector(Map.of("UPLOAD", 80, "HEAD", 10), 1));
    }

    @Test
    void followsConfiguredDistributionForLongRun() {
        WeightedOperationSelector selector = new WeightedOperationSelector(
                Map.of("UPLOAD", 40, "DOWNLOAD", 40, "HEAD", 20), 42);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100_000; i++) counts.merge(selector.next(), 1, Integer::sum);
        assertEquals(100_000, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(Math.abs(counts.get("UPLOAD") - 40_000) < 1_000);
        assertTrue(Math.abs(counts.get("DOWNLOAD") - 40_000) < 1_000);
        assertTrue(Math.abs(counts.get("HEAD") - 20_000) < 1_000);
    }
}
