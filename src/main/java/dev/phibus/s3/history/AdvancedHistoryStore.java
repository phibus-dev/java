package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.PartResult;
import dev.phibus.s3.test.TestRequest;
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
import org.springframework.stereotype.Repository;

@Repository
public class AdvancedHistoryStore {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;

    public AdvancedHistoryStore(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public Page search(Filter filter) {
        int page = Math.max(0, filter.page());
        int size = Math.max(1, Math.min(filter.size(), 200));
        List<Object> parameters = new ArrayList<>();
        String where = where(filter, parameters);
        String select = """
                select id,status,created_at,started_at,finished_at,endpoint,bucket,region,object_key,
                       operation,total_bytes,bytes_transferred,average_speed_mibps,p50_latency_ms,
                       p95_latency_ms,p99_latency_ms,successful_parts,failed_parts,cleanup_successful,
                       message,baseline,baseline_name,path_style_access,object_size_mib,part_size_mib,
                       parallelism,object_count,delete_after_test
                  from test_run
                """ + where + " order by created_at desc limit ? offset ?";
        String count = "select count(*) from test_run " + where;
        try (Connection connection = connection()) {
            long total;
            try (PreparedStatement ps = connection.prepareStatement(count)) {
                bind(ps, parameters);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); }
            }
            List<RunRow> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                int next = bind(ps, parameters) + 1;
                ps.setInt(next++, size);
                ps.setInt(next, page * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(mapRun(rs));
                }
            }
            return new Page(rows, page, size, total, (int) Math.ceil(total / (double) size));
        } catch (SQLException e) {
            throw new TestHistoryStore.HistoryPersistenceException("Cannot search test history", e);
        }
    }

    public Detail get(UUID id) {
        String sql = """
                select id,status,created_at,started_at,finished_at,endpoint,bucket,region,object_key,
                       operation,total_bytes,bytes_transferred,average_speed_mibps,p50_latency_ms,
                       p95_latency_ms,p99_latency_ms,successful_parts,failed_parts,cleanup_successful,
                       message,baseline,baseline_name,path_style_access,object_size_mib,part_size_mib,
                       parallelism,object_count,delete_after_test
                  from test_run where id=?
                """;
        try (Connection connection = connection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                RunRow row = mapRun(rs);
                return new Detail(row, parts(connection, id), toRequest(row));
            }
        } catch (SQLException e) {
            throw new TestHistoryStore.HistoryPersistenceException("Cannot read history detail", e);
        }
    }

    public Comparison compare(UUID leftId, UUID rightId) {
        Detail left = get(leftId);
        Detail right = get(rightId);
        if (left == null || right == null) return null;
        return new Comparison(left.run(), right.run(),
                change(right.run().averageSpeedMiBps(), left.run().averageSpeedMiBps()),
                change(right.run().p95LatencyMs(), left.run().p95LatencyMs()),
                change(right.run().p99LatencyMs(), left.run().p99LatencyMs()),
                right.run().failedParts() - left.run().failedParts(),
                change(duration(right.run()), duration(left.run())));
    }

    private List<PartResult> parts(Connection connection, UUID id) throws SQLException {
        String sql = "select object_number,part_number,bytes,duration_ms,speed_mibps,etag,status,error_message from part_result where test_run_id=? order by object_number,part_number";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<PartResult> result = new ArrayList<>();
                while (rs.next()) result.add(new PartResult(rs.getInt(1), rs.getInt(2), rs.getLong(3),
                        rs.getLong(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8)));
                return result;
            }
        }
    }

    private static TestRequest toRequest(RunRow row) {
        return new TestRequest(row.endpoint(), row.bucket(), row.region(), null, null, row.pathStyleAccess(),
                row.objectKey(), row.objectSizeMiB(), row.partSizeMiB(), row.parallelism(), row.objectCount(),
                row.deleteAfterTest(), row.operation());
    }

    private static String where(Filter filter, List<Object> parameters) {
        StringBuilder sql = new StringBuilder(" where 1=1");
        add(sql, parameters, "status", filter.status());
        add(sql, parameters, "operation", filter.operation());
        addLike(sql, parameters, "endpoint", filter.endpoint());
        addLike(sql, parameters, "bucket", filter.bucket());
        if (filter.query() != null && !filter.query().isBlank()) {
            sql.append(" and (lower(endpoint) like ? or lower(bucket) like ? or lower(object_key) like ?)");
            String value = "%" + filter.query().trim().toLowerCase() + "%";
            parameters.add(value); parameters.add(value); parameters.add(value);
        }
        if (filter.from() != null) { sql.append(" and created_at>=?"); parameters.add(Timestamp.from(filter.from())); }
        if (filter.to() != null) { sql.append(" and created_at<?"); parameters.add(Timestamp.from(filter.to())); }
        return sql.toString();
    }

    private static void add(StringBuilder sql, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { sql.append(" and ").append(column).append("=?"); params.add(value); }
    }
    private static void addLike(StringBuilder sql, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { sql.append(" and lower(").append(column).append(") like ?"); params.add("%" + value.trim().toLowerCase() + "%"); }
    }
    private static int bind(PreparedStatement ps, List<Object> values) throws SQLException {
        int i = 1; for (Object value : values) ps.setObject(i++, value); return i - 1;
    }

    private static RunRow mapRun(ResultSet rs) throws SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getString("status"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("endpoint"),
                rs.getString("bucket"), rs.getString("region"), rs.getString("object_key"),
                rs.getString("operation"), rs.getLong("total_bytes"), rs.getLong("bytes_transferred"),
                rs.getDouble("average_speed_mibps"), rs.getDouble("p50_latency_ms"), rs.getDouble("p95_latency_ms"),
                rs.getDouble("p99_latency_ms"), rs.getInt("successful_parts"), rs.getInt("failed_parts"),
                rs.getBoolean("cleanup_successful"), rs.getString("message"), rs.getBoolean("baseline"),
                rs.getString("baseline_name"), rs.getBoolean("path_style_access"), rs.getLong("object_size_mib"),
                rs.getLong("part_size_mib"), rs.getInt("parallelism"), rs.getInt("object_count"),
                rs.getBoolean("delete_after_test"));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private Connection connection() throws SQLException {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        return DriverManager.getConnection(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()));
    }
    private static double change(double current, double previous) { return previous == 0 ? 0 : (current - previous) * 100.0 / previous; }
    private static long duration(RunRow row) { return row.startedAt() == null || row.finishedAt() == null ? 0 : java.time.Duration.between(row.startedAt(), row.finishedAt()).toMillis(); }

    public record Filter(String status, String operation, String endpoint, String bucket, String query,
                         Instant from, Instant to, int page, int size) { }
    public record Page(List<RunRow> items, int page, int size, long totalElements, int totalPages) { }
    public record Detail(RunRow run, List<PartResult> parts, TestRequest repeatRequest) { }
    public record Comparison(RunRow left, RunRow right, double speedChangePercent, double p95ChangePercent,
                             double p99ChangePercent, int errorDifference, double durationChangePercent) { }
    public record RunRow(UUID id, String status, Instant createdAt, Instant startedAt, Instant finishedAt,
                         String endpoint, String bucket, String region, String objectKey, String operation,
                         long totalBytes, long bytesTransferred, double averageSpeedMiBps, double p50LatencyMs,
                         double p95LatencyMs, double p99LatencyMs, int successfulParts, int failedParts,
                         boolean cleanupSuccessful, String message, boolean baseline, String baselineName,
                         boolean pathStyleAccess, long objectSizeMiB, long partSizeMiB, int parallelism,
                         int objectCount, boolean deleteAfterTest) { }
}
