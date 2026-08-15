package dev.phibus.s3.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClickHouseReplicationMetricsTest {
    @Test
    void mapsHealthStatesToStableNumericCodes() {
        assertEquals(0, ClickHouseReplicationMetrics.healthCode("OK"));
        assertEquals(1, ClickHouseReplicationMetrics.healthCode("WARNING"));
        assertEquals(2, ClickHouseReplicationMetrics.healthCode("CRITICAL"));
    }
}
