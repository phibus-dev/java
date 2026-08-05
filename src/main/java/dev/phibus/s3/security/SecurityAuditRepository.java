package dev.phibus.s3.security;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityAuditRepository {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;

    public SecurityAuditRepository(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public void save(SecurityAuditEvent event) {
        if (!configured()) {
            return;
        }
        String sql = """
                INSERT INTO security_audit_event
                (username, action, http_method, request_path, response_status, remote_address, duration_ms, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.username());
            statement.setString(2, event.action());
            statement.setString(3, event.httpMethod());
            statement.setString(4, event.requestPath());
            statement.setObject(5, event.responseStatus());
            statement.setString(6, event.remoteAddress());
            statement.setObject(7, event.durationMs());
            statement.setString(8, "{}");
            statement.executeUpdate();
        } catch (SQLException | RuntimeException ignored) {
            // Audit persistence must never interrupt the user request. The filter also writes the event to the application log.
        }
    }

    public List<SecurityAuditEvent> find(String username, String method, Integer status, Instant from, Instant to,
                                         int limit, int offset) {
        if (!configured()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, username, action, http_method, request_path,
                       response_status, remote_address, duration_ms
                FROM security_audit_event WHERE 1=1
                """);
        ArrayList<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            sql.append(" AND username ILIKE ?");
            args.add("%" + username + "%");
        }
        if (method != null && !method.isBlank()) {
            sql.append(" AND http_method = ?");
            args.add(method.toUpperCase());
        }
        if (status != null) {
            sql.append(" AND response_status = ?");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));

        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                statement.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<SecurityAuditEvent> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (SQLException | RuntimeException e) {
            return List.of();
        }
    }

    public Map<String, Long> summary() {
        if (!configured()) {
            return Map.of("total", 0L, "errors", 0L);
        }
        String sql = """
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE response_status >= 400) AS errors
                FROM security_audit_event
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (rs.next()) {
                return Map.of("total", rs.getLong("total"), "errors", rs.getLong("errors"));
            }
        } catch (SQLException | RuntimeException ignored) {
            // Return an empty summary while PostgreSQL is unavailable or bootstrap configuration is incomplete.
        }
        return Map.of("total", 0L, "errors", 0L);
    }

    private boolean configured() {
        return settingsService.load().postgresql().configured();
    }

    private Connection connection() throws SQLException {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        return DriverManager.getConnection(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()));
    }

    private static SecurityAuditEvent mapRow(ResultSet rs) throws SQLException {
        Timestamp occurredAt = rs.getTimestamp("occurred_at");
        return new SecurityAuditEvent(rs.getLong("id"), occurredAt == null ? null : occurredAt.toInstant(),
                rs.getString("username"), rs.getString("action"), rs.getString("http_method"),
                rs.getString("request_path"), rs.getInt("response_status"), rs.getString("remote_address"),
                rs.getLong("duration_ms"));
    }

    public record SecurityAuditEvent(Long id, Instant occurredAt, String username, String action,
                                     String httpMethod, String requestPath, Integer responseStatus,
                                     String remoteAddress, Long durationMs) {
        public SecurityAuditEvent(String username, String action, String httpMethod, String requestPath,
                                  Integer responseStatus, String remoteAddress, Long durationMs) {
            this(null, null, username, action, httpMethod, requestPath, responseStatus, remoteAddress, durationMs);
        }
    }
}
