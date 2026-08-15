package dev.phibus.s3.clickhouse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClickHouseHistoryStore {
    private final JdbcTemplate jdbc;

    public ClickHouseHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ClickHouseTestRun.Snapshot snapshot, ClickHouseTestRequest request) {
        jdbc.update("""
                INSERT INTO clickhouse_test_run(
                    id, profile_id, endpoint, table_name, operation, status, created_at, started_at, finished_at,
                    concurrency, batch_size, requested_rows, duration_seconds, warmup_seconds, payload_bytes,
                    auto_create_table, rows_processed, bytes_processed, queries, errors, rows_per_second,
                    mib_per_second, queries_per_second, p50_latency_ms, p95_latency_ms, p99_latency_ms, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status, started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at,
                    rows_processed = EXCLUDED.rows_processed, bytes_processed = EXCLUDED.bytes_processed,
                    queries = EXCLUDED.queries, errors = EXCLUDED.errors, rows_per_second = EXCLUDED.rows_per_second,
                    mib_per_second = EXCLUDED.mib_per_second, queries_per_second = EXCLUDED.queries_per_second,
                    p50_latency_ms = EXCLUDED.p50_latency_ms, p95_latency_ms = EXCLUDED.p95_latency_ms,
                    p99_latency_ms = EXCLUDED.p99_latency_ms, message = EXCLUDED.message
                """,
                snapshot.id(), request.profileId(), snapshot.endpoint(), snapshot.table(), snapshot.operation(),
                snapshot.status().name(), snapshot.createdAt(), snapshot.startedAt(), snapshot.finishedAt(),
                request.concurrency(), request.batchSize(), request.rowCount(), request.durationSeconds(),
                request.warmupSeconds(), request.payloadBytes(), request.autoCreateTable(), snapshot.rows(),
                snapshot.bytes(), snapshot.queries(), snapshot.errors(), snapshot.rowsPerSecond(),
                snapshot.mibPerSecond(), snapshot.queriesPerSecond(), snapshot.p50LatencyMs(),
                snapshot.p95LatencyMs(), snapshot.p99LatencyMs(), snapshot.message());
    }

    public List<HistoryRow> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT * FROM clickhouse_test_run
                ORDER BY created_at DESC
                LIMIT ?
                """, this::map, safeLimit);
    }

    public HistoryRow get(UUID id) {
        List<HistoryRow> result = jdbc.query("SELECT * FROM clickhouse_test_run WHERE id = ?", this::map, id);
        if (result.isEmpty()) throw new IllegalArgumentException("ClickHouse history entry not found: " + id);
        return result.getFirst();
    }

    public Comparison compare(UUID leftId, UUID rightId) {
        HistoryRow left = get(leftId);
        HistoryRow right = get(rightId);
        return new Comparison(left, right,
                percentChange(right.rowsPerSecond(), left.rowsPerSecond()),
                percentChange(right.mibPerSecond(), left.mibPerSecond()),
                percentChange(right.queriesPerSecond(), left.queriesPerSecond()),
                percentChange(right.p95LatencyMs(), left.p95LatencyMs()),
                percentChange(right.p99LatencyMs(), left.p99LatencyMs()));
    }

    public List<TrendPoint> trends(String operation, String table, int limit) {
        int safeLimit = Math.max(2, Math.min(limit, 200));
        String op = normalize(operation);
        String tableName = normalize(table);
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, rows_per_second, mib_per_second, queries_per_second,
                       p95_latency_ms, p99_latency_ms, errors
                  FROM clickhouse_test_run
                 WHERE status = 'COMPLETED'
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (op != null) { sql.append(" AND operation = ?"); args.add(op); }
        if (tableName != null) { sql.append(" AND table_name = ?"); args.add(tableName); }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(safeLimit);
        List<TrendPoint> newestFirst = jdbc.query(sql.toString(), (rs, row) -> new TrendPoint(
                rs.getObject("id", UUID.class), instant(rs, "created_at"), rs.getDouble("rows_per_second"),
                rs.getDouble("mib_per_second"), rs.getDouble("queries_per_second"),
                rs.getDouble("p95_latency_ms"), rs.getDouble("p99_latency_ms"), rs.getLong("errors")), args.toArray());
        return newestFirst.reversed();
    }

    private HistoryRow map(ResultSet rs, int row) throws SQLException {
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getString("endpoint"), rs.getString("table_name"), rs.getString("operation"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                rs.getInt("concurrency"), rs.getInt("batch_size"), rs.getLong("requested_rows"),
                rs.getLong("duration_seconds"), rs.getLong("warmup_seconds"), rs.getInt("payload_bytes"),
                rs.getBoolean("auto_create_table"), rs.getLong("rows_processed"), rs.getLong("bytes_processed"),
                rs.getLong("queries"), rs.getLong("errors"), rs.getDouble("rows_per_second"),
                rs.getDouble("mib_per_second"), rs.getDouble("queries_per_second"), rs.getDouble("p50_latency_ms"),
                rs.getDouble("p95_latency_ms"), rs.getDouble("p99_latency_ms"), rs.getString("message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Double percentChange(double current, double previous) {
        if (previous == 0) return current == 0 ? 0.0 : null;
        return (current - previous) * 100.0 / previous;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public record HistoryRow(UUID id, UUID profileId, String endpoint, String table, String operation, String status,
                             Instant createdAt, Instant startedAt, Instant finishedAt, int concurrency, int batchSize,
                             long requestedRows, long durationSeconds, long warmupSeconds, int payloadBytes,
                             boolean autoCreateTable, long rows, long bytes, long queries, long errors,
                             double rowsPerSecond, double mibPerSecond, double queriesPerSecond,
                             double p50LatencyMs, double p95LatencyMs, double p99LatencyMs, String message) { }

    public record Comparison(HistoryRow left, HistoryRow right, Double rowsPerSecondChangePercent,
                             Double mibPerSecondChangePercent, Double queriesPerSecondChangePercent,
                             Double p95LatencyChangePercent, Double p99LatencyChangePercent) { }

    public record TrendPoint(UUID id, Instant createdAt, double rowsPerSecond, double mibPerSecond,
                             double queriesPerSecond, double p95LatencyMs, double p99LatencyMs, long errors) { }
}
