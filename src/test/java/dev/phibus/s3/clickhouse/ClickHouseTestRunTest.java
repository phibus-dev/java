package dev.phibus.s3.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClickHouseTestRunTest {
    @Test
    void snapshotContainsRowsThroughputAndLatency() throws Exception {
        ClickHouseTestRequest request = request("INSERT", 100, 0);
        ClickHouseTestRun run = new ClickHouseTestRun(request, "http://clickhouse:8123");
        run.start();
        Thread.sleep(5);
        run.operationCompleted(50, 50 * 128L, 10);
        run.operationCompleted(50, 50 * 128L, 20);
        run.complete();

        ClickHouseTestRun.Snapshot snapshot = run.snapshot();
        assertThat(snapshot.status()).isEqualTo(ClickHouseTestRun.Status.COMPLETED);
        assertThat(snapshot.rows()).isEqualTo(100);
        assertThat(snapshot.bytes()).isEqualTo(12800);
        assertThat(snapshot.queries()).isEqualTo(2);
        assertThat(snapshot.p50LatencyMs()).isEqualTo(10);
        assertThat(snapshot.p95LatencyMs()).isEqualTo(20);
        assertThat(snapshot.percent()).isEqualTo(100.0);
        assertThat(snapshot.rowsPerSecond()).isPositive();
    }

    @Test
    void validatesOperationAndTableName() {
        ClickHouseTestRequest invalidOperation = request("DROP", 10, 0);
        assertThatThrownBy(() -> ClickHouseTestRunService.validate(invalidOperation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");

        ClickHouseTestRequest invalidTable = new ClickHouseTestRequest(UUID.randomUUID(), null, "bad-name", "INSERT",
                1, 100, 1000, 0, 0, 128, true);
        assertThatThrownBy(() -> ClickHouseTestRunService.validate(invalidTable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table");
    }

    private static ClickHouseTestRequest request(String operation, long rows, long duration) {
        return new ClickHouseTestRequest(UUID.randomUUID(), null, "evo_snt_perf_load", operation,
                2, 50, rows, duration, 0, 128, true);
    }
}
