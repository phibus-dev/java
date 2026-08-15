package dev.phibus.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadTestEngineRegistryTest {
    @Test
    void registersAndResolvesEngineByType() {
        LoadTestEngine s3 = new StubEngine(TestType.S3);
        LoadTestEngineRegistry registry = new LoadTestEngineRegistry(List.of(s3));

        assertEquals(s3, registry.require(TestType.S3));
        assertTrue(registry.supports(TestType.S3));
        assertFalse(registry.supports(TestType.CLICKHOUSE));
        assertEquals(List.of(TestType.S3), registry.supportedTypes());
    }

    @Test
    void rejectsMissingEngine() {
        LoadTestEngineRegistry registry = new LoadTestEngineRegistry(List.of(new StubEngine(TestType.S3)));
        assertThrows(IllegalArgumentException.class, () -> registry.require(TestType.CLICKHOUSE));
    }

    @Test
    void rejectsDuplicateEngineType() {
        assertThrows(IllegalStateException.class, () -> new LoadTestEngineRegistry(
                List.of(new StubEngine(TestType.S3), new StubEngine(TestType.S3))));
    }

    private record StubEngine(TestType type) implements LoadTestEngine {
        @Override
        public void execute(TestRun run) {
            // no-op
        }
    }
}
