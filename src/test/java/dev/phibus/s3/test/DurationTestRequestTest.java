package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DurationTestRequestTest {
    @Test
    void durationModeDoesNotPrecalculateTotalBytes() {
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "test.bin", 64, 8, 2, 1, true, "UPLOAD", "TIME_DURATION", 300, 30);
        assertTrue(request.durationMode());
        assertEquals(300, request.effectiveDurationSeconds());
        assertEquals(30, request.effectiveWarmupSeconds());
        assertEquals(0, request.totalBytes());
    }

    @Test
    void legacyConstructorKeepsObjectCountMode() {
        TestRequest request = new TestRequest("http://s3", "bucket", "us-east-1", null, null,
                true, "test.bin", 64, 8, 2, 2, true, "UPLOAD");
        assertFalse(request.durationMode());
        assertEquals("OBJECT_COUNT", request.normalizedExecutionMode());
        assertEquals(128L * 1024 * 1024, request.totalBytes());
    }
}
