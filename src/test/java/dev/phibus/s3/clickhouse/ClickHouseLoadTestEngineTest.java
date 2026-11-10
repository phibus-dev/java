package dev.phibus.s3.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.phibus.s3.runtime.RuntimeResourceManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ClickHouseLoadTestEngineTest {
    @Test
    void selectExecutesRequestedQueryCountRatherThanRequestedRows() throws Exception {
        ClickHouseConnectionProvider connections = mock(ClickHouseConnectionProvider.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        UUID profileId = UUID.randomUUID();
        when(connections.open(profileId, null)).thenReturn(connection);
        when(connections.queryTimeoutSeconds(profileId)).thenReturn(30);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getLong(1)).thenReturn(1_000_000L);
        when(result.getLong(2)).thenReturn(128_000_000L);
        ClickHouseTestRequest request = new ClickHouseTestRequest(profileId, null,
                "evo_snt_perf_load", "SELECT", 1, 100, 1_000_000, 0, 0, 128, 3L, false);
        ClickHouseTestRun run = new ClickHouseTestRun(request, "http://clickhouse:8123");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             RuntimeResourceManager resources = new RuntimeResourceManager(2, 8, 60,
                     new SimpleMeterRegistry())) {
            new ClickHouseLoadTestEngine(connections, executor, resources).execute(run);
        }

        verify(statement, times(3)).executeQuery(anyString());
        assertThat(run.snapshot().queries()).isEqualTo(3);
        assertThat(run.snapshot().status()).isEqualTo(ClickHouseTestRun.Status.COMPLETED);
    }
}
