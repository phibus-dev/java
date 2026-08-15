package dev.phibus.s3.clickhouse;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseLoadTestEngine {
    private final ClickHouseConnectionProvider connections;

    public ClickHouseLoadTestEngine(ClickHouseConnectionProvider connections) {
        this.connections = connections;
    }

    public void execute(ClickHouseTestRun run) {
        ClickHouseTestRequest request = run.request();
        try {
            run.start();
            if (request.autoCreateTable()) createTable(run);
            switch (request.normalizedOperation()) {
                case "INSERT" -> executeInsert(run);
                case "SELECT" -> executeSelect(run);
                case "INSERT_SELECT" -> executeInsertSelect(run);
                default -> throw new IllegalArgumentException("Unsupported ClickHouse operation: " + request.operation());
            }
            run.complete();
        } catch (Exception e) {
            run.fail(rootMessage(e));
        }
    }

    private void createTable(ClickHouseTestRun run) throws Exception {
        String table = run.request().normalizedTable();
        try (Connection connection = connections.open(run.request().profileId(), run.request().endpoint());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(profileTimeout(run));
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " (event_time DateTime64(3), sequence UInt64, payload String) ENGINE = MergeTree ORDER BY sequence");
        }
    }

    private void executeInsert(ClickHouseTestRun run) throws Exception {
        int workers = run.request().concurrency();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicLong sequence = new AtomicLong();
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                tasks.add(() -> { insertWorker(run, sequence); return null; });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) future.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private void insertWorker(ClickHouseTestRun run, AtomicLong sequence) throws Exception {
        ClickHouseTestRequest request = run.request();
        String sql = "INSERT INTO " + request.normalizedTable() + " (event_time, sequence, payload) VALUES (now64(3), ?, ?)";
        byte[] payloadBytes = new byte[request.payloadBytes()];
        java.util.Arrays.fill(payloadBytes, (byte) 'x');
        String payload = new String(payloadBytes, StandardCharsets.US_ASCII);
        try (Connection connection = connections.open(request.profileId(), request.endpoint());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(profileTimeout(run));
            while (!run.isCancelled() && !run.durationExpired()) {
                long first = sequence.getAndAdd(request.batchSize());
                if (!request.durationMode() && first >= request.rowCount()) break;
                int batch = request.batchSize();
                if (!request.durationMode()) batch = (int) Math.min(batch, request.rowCount() - first);
                Instant started = Instant.now();
                try {
                    for (int i = 0; i < batch; i++) {
                        statement.setLong(1, first + i);
                        statement.setString(2, payload);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                    long latencyMs = Math.max(1, Duration.between(started, Instant.now()).toMillis());
                    run.operationCompleted(batch, (long) batch * request.payloadBytes(), latencyMs);
                } catch (Exception e) {
                    run.operationFailed();
                    throw e;
                }
            }
        }
    }

    private void executeSelect(ClickHouseTestRun run) throws Exception {
        ClickHouseTestRequest request = run.request();
        long completed = 0;
        try (Connection connection = connections.open(request.profileId(), request.endpoint());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(profileTimeout(run));
            while (!run.isCancelled() && !run.durationExpired() && (request.durationMode() || completed < request.rowCount())) {
                Instant started = Instant.now();
                try (ResultSet rs = statement.executeQuery("SELECT count(), sum(length(payload)) FROM " + request.normalizedTable())) {
                    long rows = 0;
                    long bytes = 0;
                    if (rs.next()) {
                        rows = rs.getLong(1);
                        bytes = rs.getLong(2);
                    }
                    long latencyMs = Math.max(1, Duration.between(started, Instant.now()).toMillis());
                    run.operationCompleted(rows, bytes, latencyMs);
                    completed++;
                } catch (Exception e) {
                    run.operationFailed();
                    throw e;
                }
            }
        }
    }

    private void executeInsertSelect(ClickHouseTestRun run) throws Exception {
        executeInsert(run);
        if (!run.isCancelled()) executeSelect(run);
    }

    private int profileTimeout(ClickHouseTestRun run) {
        return connections.queryTimeoutSeconds(run.request().profileId());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
