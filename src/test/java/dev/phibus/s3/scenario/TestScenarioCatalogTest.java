package dev.phibus.s3.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TestScenarioCatalogTest {
    private final TestScenarioCatalog catalog = new TestScenarioCatalog();

    @Test
    void containsExpectedOperationalScenarios() {
        assertEquals("LIFECYCLE", catalog.get("SMOKE").operation());
        assertEquals("UPLOAD", catalog.get("UPLOAD_PERFORMANCE").operation());
        assertEquals("DOWNLOAD", catalog.get("READ_PERFORMANCE").operation());
        assertEquals("HEAD", catalog.get("METADATA").operation());
    }

    @Test
    void rejectsUnknownScenario() {
        assertThrows(IllegalArgumentException.class, () -> catalog.get("missing"));
    }
}
