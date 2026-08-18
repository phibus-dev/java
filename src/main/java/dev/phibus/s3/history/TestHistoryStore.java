package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.PartResult;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Repository;

@Repository
public class TestHistoryStore {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private volatile String migratedJdbcUrl;

    public TestHistoryStore(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public void save(TestRun.Snapshot run) {
        if (!configured()) return;
        ensureMigrated();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            upsertRun(connection, run);
            replaceParts(connection, run);
            connection.commit();
        } catch (SQLException e) {
            throw new HistoryPersistenceException("Cannot save test history", e);
        }
    }

    public void saveDistributed(DistributedHistoryResult result) {
        if (!configured()) return;
        ensureMigrated();
        String sql = """
                insert into test_run (id, status, created_at, started_at, finished_at, endpoint, bucket, region,
                    object_key, operation, execution_mode, configured_duration_seconds, warmup_seconds, stop_reason,
                    total_bytes, bytes_transferred, completed_parts, total_parts, average_speed_mibps,
                    p50_latency_ms, p95_latency_ms, p99_latency_ms, successful_parts, failed_parts,
                    delete_after_test, cleanup_successful, message, path_style_access, object_size_mib,
                    part_size_mib, parallelism, object_count, initiator)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    status=excluded.status, finished_at=excluded.finished_at,
                    bytes_transferred=excluded.bytes_transferred, completed_parts=excluded.completed_parts,
                    total_parts=excluded.total_parts, average_speed_mibps=excluded.average_speed_mibps,
                    p50_latency_ms=excluded.p50_latency_ms, p95_latency_ms=excluded.p95_latency_ms,
                    p99_latency_ms=excluded.p99_latency_ms, successful_parts=excluded.successful_parts,
                    failed_parts=excluded.failed_parts, message=excluded.message, initiator=excluded.initiator
                """;
        TestRequest request = result.request();
        int completed = safeInt(result.completedOperations());
        int failed = safeInt(result.errors());
        try (Connection connection = connection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, result.id());
            ps.setString(i++, result.status());
            ps.setTimestamp(i++, timestamp(result.startedAt()));
            ps.setTimestamp(i++, timestamp(result.startedAt()));
            ps.setTimestamp(i++, timestamp(result.finishedAt()));
            ps.setString(i++, request.endpoint());
            ps.setString(i++, request.bucket());
            ps.setString(i++, request.region());
            ps.setString(i++, request.objectKey());
            ps.setString(i++, request.normalizedOperation());
            ps.setString(i++, request.normalizedExecutionMode());
            ps.setLong(i++, request.effectiveDurationSeconds());
            ps.setLong(i++, request.effectiveWarmupSeconds());
            ps.setString(i++, "FAILED".equals(result.status()) ? "ERROR" : "NORMAL");
            ps.setLong(i++, request.totalBytes());
            ps.setLong(i++, result.bytesTransferred());
            ps.setInt(i++, completed);
            ps.setInt(i++, completed);
            ps.setDouble(i++, result.throughputMiBps());
            ps.setDouble(i++, result.p50LatencyMs());
            ps.setDouble(i++, result.p95LatencyMs());
            ps.setDouble(i++, result.p99LatencyMs());
            ps.setInt(i++, Math.max(0, completed - failed));
            ps.setInt(i++, failed);
            ps.setBoolean(i++, request.deleteAfterTest());
            ps.setBoolean(i++, false);
            ps.setString(i++, result.message());
            ps.setBoolean(i++, request.pathStyleAccess());
            ps.setLong(i++, request.objectSizeMiB());
            ps.setLong(i++, request.partSizeMiB());
            ps.setInt(i++, request.parallelism());
            ps.setInt(i++, request.objectCount());
            ps.setString(i, normalizeInitiator(result.initiator()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HistoryPersistenceException("Cannot save distributed test history", e);
        }
    }

    public List<HistoryRow> list(int limit) {
        if (!configured()) return List.of();
        ensureMigrated();
        String sql = """
                select id, status, created_at, started_at, finished_at, endpoint, bucket, region,
                       object_key, total_bytes, bytes_transferred, average_speed_mibps,
                       p50_latency_ms, p95_latency_ms, p99_latency_ms, successful_parts,
                       failed_parts, cleanup_successful, message, initiator
                  from test_run
                 order by created_at desc
                 limit ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, Math.min(limit, 1000)));
            try (ResultSet rs = statement.executeQuery()) {
                List<HistoryRow> result = new ArrayList<>();
                while (rs.next()) result.add(map(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new HistoryPersistenceException("Cannot read test history", e);
        }
    }

    public HistoryRow get(UUID id) {
        if (!configured()) return null;
        ensureMigrated();
        String sql = """
                select id, status, created_at, started_at, finished_at, endpoint, bucket, region,
                       object_key, total_bytes, bytes_transferred, average_speed_mibps,
                       p50_latency_ms, p95_latency_ms, p99_latency_ms, successful_parts,
                       failed_parts, cleanup_successful, message, initiator
                  from test_run where id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new HistoryPersistenceException("Cannot read test history item", e);
        }
    }

    private boolean configured() {
        return settingsService.load().postgresql().configured();
    }

    private synchronized void ensureMigrated() {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (settings.jdbcUrl().equals(migratedJdbcUrl)) return;
        Flyway.configure()
                .dataSource(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        migratedJdbcUrl = settings.jdbcUrl();
    }

    private Connection connection() throws SQLException {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        return DriverManager.getConnection(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()));
    }

    private static void upsertRun(Connection connection, TestRun.Snapshot run) throws SQLException {
        String sql = """
                insert into test_run (id, status, created_at, started_at, finished_at, endpoint, bucket, region,
                    object_key, total_bytes, bytes_transferred, completed_parts, total_parts, average_speed_mibps,
                    p50_latency_ms, p95_latency_ms, p99_latency_ms, successful_parts, failed_parts,
                    delete_after_test, cleanup_successful, message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set
                    status = excluded.status, started_at = excluded.started_at, finished_at = excluded.finished_at,
                    bytes_transferred = excluded.bytes_transferred, completed_parts = excluded.completed_parts,
                    total_parts = excluded.total_parts, average_speed_mibps = excluded.average_speed_mibps,
                    p50_latency_ms = excluded.p50_latency_ms, p95_latency_ms = excluded.p95_latency_ms,
                    p99_latency_ms = excluded.p99_latency_ms, successful_parts = excluded.successful_parts,
                    failed_parts = excluded.failed_parts, cleanup_successful = excluded.cleanup_successful,
                    message = excluded.message
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, run.id());
            ps.setString(i++, run.status().name());
            ps.setTimestamp(i++, timestamp(run.createdAt()));
            ps.setTimestamp(i++, timestamp(run.startedAt()));
            ps.setTimestamp(i++, timestamp(run.finishedAt()));
            ps.setString(i++, run.endpoint());
            ps.setString(i++, run.bucket());
            ps.setString(i++, run.region());
            ps.setString(i++, run.objectKey());
            ps.setLong(i++, run.totalBytes());
            ps.setLong(i++, run.bytesTransferred());
            ps.setInt(i++, run.completedParts());
            ps.setInt(i++, run.totalParts());
            ps.setDouble(i++, run.averageSpeedMiBps());
            ps.setDouble(i++, run.p50LatencyMs());
            ps.setDouble(i++, run.p95LatencyMs());
            ps.setDouble(i++, run.p99LatencyMs());
            ps.setInt(i++, run.successfulParts());
            ps.setInt(i++, run.failedParts());
            ps.setBoolean(i++, run.deleteAfterTest());
            ps.setBoolean(i++, run.cleanupSuccessful());
            ps.setString(i, run.message());
            ps.executeUpdate();
        }
    }

    private static void replaceParts(Connection connection, TestRun.Snapshot run) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("delete from part_result where test_run_id = ?")) {
            delete.setObject(1, run.id());
            delete.executeUpdate();
        }
        String sql = """
                insert into part_result(test_run_id, object_number, part_number, bytes, duration_ms,
                    speed_mibps, etag, status, error_message) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (PartResult part : deduplicateParts(run.parts())) {
                insert.setObject(1, run.id());
                insert.setInt(2, part.objectNumber());
                insert.setInt(3, part.partNumber());
                insert.setLong(4, part.bytes());
                insert.setLong(5, part.durationMillis());
                insert.setDouble(6, part.speedMiBps());
                insert.setString(7, part.eTag());
                insert.setString(8, part.status());
                insert.setString(9, part.error());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static List<PartResult> deduplicateParts(List<PartResult> parts) {
        Map<PartKey, PartResult> unique = new LinkedHashMap<>();
        for (PartResult part : parts) {
            unique.put(new PartKey(part.objectNumber(), part.partNumber()), part);
        }
        return List.copyOf(unique.values());
    }

    private static HistoryRow map(ResultSet rs) throws SQLException {
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getString("status"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("endpoint"), rs.getString("bucket"),
                rs.getString("region"), rs.getString("object_key"), rs.getLong("total_bytes"),
                rs.getLong("bytes_transferred"), rs.getDouble("average_speed_mibps"),
                rs.getDouble("p50_latency_ms"), rs.getDouble("p95_latency_ms"),
                rs.getDouble("p99_latency_ms"), rs.getInt("successful_parts"), rs.getInt("failed_parts"),
                rs.getBoolean("cleanup_successful"), rs.getString("message"),
                normalizeInitiator(rs.getString("initiator")));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static int safeInt(long value) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value)); }
    private static String normalizeInitiator(String value) {
        return value == null || value.isBlank() ? "local" : value.trim();
    }

    private record PartKey(int objectNumber, int partNumber) { }

    public record HistoryRow(UUID id, String status, Instant createdAt, Instant startedAt, Instant finishedAt,
                             String endpoint, String bucket, String region, String objectKey, long totalBytes,
                             long bytesTransferred, double averageSpeedMiBps, double p50LatencyMs,
                             double p95LatencyMs, double p99LatencyMs, int successfulParts, int failedParts,
                             boolean cleanupSuccessful, String message, String initiator) { }

    public record DistributedHistoryResult(UUID id, TestRequest request, String initiator, String status,
                                           Instant startedAt, Instant finishedAt, long completedOperations,
                                           long bytesTransferred, double throughputMiBps, double p50LatencyMs,
                                           double p95LatencyMs, double p99LatencyMs, long errors, String message) { }

    public static final class HistoryPersistenceException extends RuntimeException {
        public HistoryPersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
