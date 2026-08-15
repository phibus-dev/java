package dev.phibus.s3.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.phibus.s3.test.PartResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestHistoryStoreTest {
    @Test
    void deduplicatesPartsByObjectAndPartNumberUsingLatestResult() {
        PartResult first = new PartResult(1, 2, 1024, 100, 10.0, "etag-old", "SUCCESS", null);
        PartResult duplicate = new PartResult(1, 2, 2048, 80, 25.0, "etag-new", "SUCCESS", null);
        PartResult another = new PartResult(1, 3, 4096, 120, 34.0, "etag-3", "SUCCESS", null);

        List<PartResult> result = TestHistoryStore.deduplicateParts(List.of(first, duplicate, another));

        assertEquals(2, result.size());
        assertEquals(duplicate, result.get(0));
        assertEquals(another, result.get(1));
    }
}
