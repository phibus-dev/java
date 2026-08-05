package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceBaselineStore {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private volatile String migratedJdbcUrl;

    public PerformanceBaselineStore(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public List<BaselineRow> list() {
        ensureMigrated();
        String sql = """
                select id, baseline_name, baseline_marked_at, endpoint, bucket, operation,
                       created_at, average_speed_mibps, p95_latency_ms, p99_latency_ms,
                       failed_parts, total_bytes
                  from test_run
                 where baseline = true
                 order by baseline_marked_at desc
                """;
        try (Connection connection = connection(); PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<BaselineRow> rows = new ArrayList<>();
            while (rs.next()) rows.add(mapBaseline(rs));
            return rows;
        } catch (SQLException e) {
            throw new BaselinePersistenceException("Cannot read baselines", e);
        }
    }

    public RegressionReport markBaseline(UUID runId, String name) {
        ensureMigrated();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            RunMetrics run = getMetrics(connection, runId);
            if (run == null) throw new BaselineNotFoundException(runId);
            try (PreparedStatement clear = connection.prepareStatement(
                    "update test_run set baseline=false, baseline_name=null, baseline_marked_at=null " +
                    "where endpoint=? and bucket=? and operation=? and baseline=true")) {
                clear.setString(1, run.endpoint());
                clear.setString(2, run.bucket());
                clear.setString(3, run.operation());
                clear.executeUpdate();
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "update test_run set baseline=true, baseline_name=?, baseline_marked_at=now() where id=?")) {
                mark.setString(1, name == null || name.isBlank() ? "Baseline " + run.createdAt() : name.trim());
                mark.setObject(2, runId);
                mark.executeUpdate();
            }
            connection.commit();
            return compare(runId);
        } catch (SQLException e) {
            throw new BaselinePersistenceException("Cannot mark baseline", e);
        }
    }

    public void removeBaseline(UUID runId) {
        ensureMigrated();
        try (Connection connection = connection(); PreparedStatement ps = connection.prepareStatement(
                "update test_run set baseline=false, baseline_name=null, baseline_marked_at=null where id=?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new BaselinePersistenceException("Cannot remove baseline", e);
        }
    }

    public RegressionReport compare(UUID runId) {
        ensureMigrated();
        try (Connection connection = connection()) {
            RunMetrics current = getMetrics(connection, runId);
            if (current == null) throw new BaselineNotFoundException(runId);
            RunMetrics baseline = current.baseline() ? current : findBaseline(connection, current);
            if (baseline == null) return RegressionReport.withoutBaseline(current);
            return RegressionReport.compare(current, baseline);
        } catch (SQLException e) {
            throw new BaselinePersistenceException("Cannot compare test with baseline", e);
        }
    }

    private RunMetrics findBaseline(Connection connection, RunMetrics current) throws SQLException {
        String sql = """
                select id, endpoint, bucket, operation, created_at, started_at, finished_at,
                       average_speed_mibps, p95_latency_ms, p99_latency_ms, failed_parts,
                       successful_parts, total_bytes, baseline
                  from test_run
                 where endpoint=? and bucket=? and operation=? and baseline=true
                 limit 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, current.endpoint());
            ps.setString(2, current.bucket());
            ps.setString(3, current.operation());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapMetrics(rs) : null; }
        }
    }

    private RunMetrics getMetrics(Connection connection, UUID id) throws SQLException {
        String sql = """
                select id, endpoint, bucket, operation, created_at, started_at, finished_at,
                       average_speed_mibps, p95_latency_ms, p99_latency_ms, failed_parts,
                       successful_parts, total_bytes, baseline
                  from test_run where id=?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? mapMetrics(rs) : null; }
        }
    }

    private static RunMetrics mapMetrics(ResultSet rs) throws SQLException {
        Instant started = rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant();
        Instant finished = rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant();
        long durationMs = started == null || finished == null ? 0 : Duration.between(started, finished).toMillis();
        return new RunMetrics(rs.getObject("id", UUID.class), rs.getString("endpoint"), rs.getString("bucket"),
                rs.getString("operation"), rs.getTimestamp("created_at").toInstant(), rs.getDouble("average_speed_mibps"),
                rs.getDouble("p95_latency_ms"), rs.getDouble("p99_latency_ms"), rs.getInt("failed_parts"),
                rs.getInt("successful_parts"), rs.getLong("total_bytes"), durationMs, rs.getBoolean("baseline"));
    }

    private static BaselineRow mapBaseline(ResultSet rs) throws SQLException {
        return new BaselineRow(rs.getObject("id", UUID.class), rs.getString("baseline_name"),
                rs.getTimestamp("baseline_marked_at").toInstant(), rs.getString("endpoint"), rs.getString("bucket"),
                rs.getString("operation"), rs.getTimestamp("created_at").toInstant(),
                rs.getDouble("average_speed_mibps"), rs.getDouble("p95_latency_ms"), rs.getDouble("p99_latency_ms"),
                rs.getInt("failed_parts"), rs.getLong("total_bytes"));
    }

    private synchronized void ensureMigrated() {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (!settings.configured()) throw new IllegalStateException("PostgreSQL is not configured");
        if (settings.jdbcUrl().equals(migratedJdbcUrl)) return;
        Flyway.configure().dataSource(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()))
                .locations("classpath:db/migration").load().migrate();
        migratedJdbcUrl = settings.jdbcUrl();
    }

    private Connection connection() throws SQLException {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        return DriverManager.getConnection(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()));
    }

    private static double change(double current, double baseline) {
        return baseline == 0 ? 0 : (current - baseline) * 100.0 / baseline;
    }

    public record BaselineRow(UUID id, String name, Instant markedAt, String endpoint, String bucket,
                              String operation, Instant createdAt, double averageSpeedMiBps,
                              double p95LatencyMs, double p99LatencyMs, int errors, long totalBytes) { }

    public record RunMetrics(UUID id, String endpoint, String bucket, String operation, Instant createdAt,
                             double averageSpeedMiBps, double p95LatencyMs, double p99LatencyMs,
                             int errors, int successfulOperations, long totalBytes, long durationMs,
                             boolean baseline) { }

    public record RegressionReport(RunMetrics current, RunMetrics baseline, boolean baselineAvailable,
                                   double speedChangePercent, double p95ChangePercent,
                                   double p99ChangePercent, double errorChangePercent,
                                   double durationChangePercent, String verdict) {
        static RegressionReport withoutBaseline(RunMetrics current) {
            return new RegressionReport(current, null, false, 0, 0, 0, 0, 0, "NO_BASELINE");
        }
        static RegressionReport compare(RunMetrics current, RunMetrics baseline) {
            double speed = change(current.averageSpeedMiBps(), baseline.averageSpeedMiBps());
            double p95 = change(current.p95LatencyMs(), baseline.p95LatencyMs());
            double p99 = change(current.p99LatencyMs(), baseline.p99LatencyMs());
            double errors = change(current.errors(), baseline.errors());
            double duration = change(current.durationMs(), baseline.durationMs());
            boolean regression = speed < -10.0 || p95 > 15.0 || p99 > 20.0 || current.errors() > baseline.errors();
            boolean improvement = speed > 10.0 && p95 < 0 && p99 < 0 && current.errors() <= baseline.errors();
            return new RegressionReport(current, baseline, true, speed, p95, p99, errors, duration,
                    regression ? "REGRESSION" : improvement ? "IMPROVEMENT" : "STABLE");
        }
    }

    public static final class BaselineNotFoundException extends RuntimeException {
        public BaselineNotFoundException(UUID id) { super("Test run not found: " + id); }
    }
    public static final class BaselinePersistenceException extends RuntimeException {
        public BaselinePersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
