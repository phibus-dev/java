package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.PartResult;
import dev.phibus.s3.test.TestRun;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public List<HistoryRow> list(int limit) {
        if (!configured()) return List.of();
        ensureMigrated();
        String sql = """
                select id, status, created_at, started_at, finished_at, endpoint, bucket, region,
                       object_key, total_bytes, bytes_transferred, average_speed_mibps,
                       p50_latency_ms, p95_latency_ms, p99_latency_ms, successful_parts,
                       failed_parts, cleanup_successful, message
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
                       failed_parts, cleanup_successful, message
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
            for (PartResult part : run.parts()) {
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

    private static HistoryRow map(ResultSet rs) throws SQLException {
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getString("status"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("endpoint"), rs.getString("bucket"),
                rs.getString("region"), rs.getString("object_key"), rs.getLong("total_bytes"),
                rs.getLong("bytes_transferred"), rs.getDouble("average_speed_mibps"),
                rs.getDouble("p50_latency_ms"), rs.getDouble("p95_latency_ms"),
                rs.getDouble("p99_latency_ms"), rs.getInt("successful_parts"), rs.getInt("failed_parts"),
                rs.getBoolean("cleanup_successful"), rs.getString("message"));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record HistoryRow(UUID id, String status, Instant createdAt, Instant startedAt, Instant finishedAt,
                             String endpoint, String bucket, String region, String objectKey, long totalBytes,
                             long bytesTransferred, double averageSpeedMiBps, double p50LatencyMs,
                             double p95LatencyMs, double p99LatencyMs, int successfulParts, int failedParts,
                             boolean cleanupSuccessful, String message) { }

    public static final class HistoryPersistenceException extends RuntimeException {
        public HistoryPersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
