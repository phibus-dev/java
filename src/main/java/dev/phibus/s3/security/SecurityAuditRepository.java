package dev.phibus.s3.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityAuditRepository {
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public SecurityAuditRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public void save(SecurityAuditEvent event) {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO security_audit_event
                (username, action, http_method, request_path, response_status, remote_address, duration_ms, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, event.username(), event.action(), event.httpMethod(), event.requestPath(), event.responseStatus(),
                event.remoteAddress(), event.durationMs(), "{}");
    }

    public List<SecurityAuditEvent> find(String username, String method, Integer status, Instant from, Instant to,
                                         int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, username, action, http_method, request_path,
                       response_status, remote_address, duration_ms
                FROM security_audit_event WHERE 1=1
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
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
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at <= ?");
            args.add(java.sql.Timestamp.from(to));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        args.add(Math.max(offset, 0));
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        return jdbc == null ? List.of() : jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    public Map<String, Long> summary() {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return Map.of("total", 0L, "errors", 0L);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM security_audit_event", Long.class);
        Long errors = jdbc.queryForObject("SELECT count(*) FROM security_audit_event WHERE response_status >= 400", Long.class);
        return Map.of("total", total == null ? 0L : total, "errors", errors == null ? 0L : errors);
    }

    private SecurityAuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityAuditEvent(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
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
