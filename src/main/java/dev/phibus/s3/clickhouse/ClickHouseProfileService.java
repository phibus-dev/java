package dev.phibus.s3.clickhouse;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class ClickHouseProfileService {
    private final JdbcTemplate jdbc;
    private final BootstrapSecretCodec codec;

    public ClickHouseProfileService(JdbcTemplate jdbc, BootstrapSecretCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public List<Profile> list() {
        return jdbc.query("""
                SELECT id, name, endpoints, database_name, username, connection_timeout_ms,
                       query_timeout_seconds, is_default, created_at, updated_at
                  FROM clickhouse_profile
                 ORDER BY is_default DESC, name
                """, this::map);
    }

    public Profile get(UUID id) {
        List<Profile> profiles = jdbc.query("""
                SELECT id, name, endpoints, database_name, username, connection_timeout_ms,
                       query_timeout_seconds, is_default, created_at, updated_at
                  FROM clickhouse_profile WHERE id = ?
                """, this::map, id);
        if (profiles.isEmpty()) throw new IllegalArgumentException("ClickHouse profile not found: " + id);
        return profiles.getFirst();
    }

    public ClickHouseConnectionSpec connectionSpec(UUID id, String requestedEndpoint) {
        Profile profile = get(id);
        String endpoint = requestedEndpoint == null || requestedEndpoint.isBlank()
                ? profile.endpoints().getFirst() : requestedEndpoint.trim();
        if (!profile.endpoints().contains(endpoint)) {
            throw new IllegalArgumentException("Endpoint is not part of ClickHouse profile: " + endpoint);
        }
        return new ClickHouseConnectionSpec(endpoint, profile.database(), profile.username(), decryptPassword(id),
                profile.connectionTimeoutMs(), profile.queryTimeoutSeconds());
    }

    @Transactional
    public Profile create(ProfileRequest request) {
        validate(request);
        UUID id = UUID.randomUUID();
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                INSERT INTO clickhouse_profile(id, name, endpoints, database_name, username, encrypted_password,
                    connection_timeout_ms, query_timeout_seconds, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.name().trim(), normalizeEndpoints(request.endpoints()),
                defaultValue(request.database(), "default"), defaultValue(request.username(), "default"),
                encryptPassword(request.password()), normalizeConnectionTimeout(request.connectionTimeoutMs()),
                normalizeQueryTimeout(request.queryTimeoutSeconds()), request.defaultProfile());
        return get(id);
    }

    @Transactional
    public Profile update(UUID id, ProfileRequest request) {
        get(id);
        validate(request);
        String existingPassword = encryptedPassword(id);
        String encryptedPassword = request.password() == null || request.password().isBlank()
                ? existingPassword : codec.encrypt(request.password());
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                UPDATE clickhouse_profile
                   SET name = ?, endpoints = ?, database_name = ?, username = ?, encrypted_password = ?,
                       connection_timeout_ms = ?, query_timeout_seconds = ?, is_default = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, request.name().trim(), normalizeEndpoints(request.endpoints()),
                defaultValue(request.database(), "default"), defaultValue(request.username(), "default"),
                encryptedPassword, normalizeConnectionTimeout(request.connectionTimeoutMs()),
                normalizeQueryTimeout(request.queryTimeoutSeconds()), request.defaultProfile(), id);
        return get(id);
    }

    @Transactional
    public Profile makeDefault(UUID id) {
        get(id);
        clearDefault();
        jdbc.update("UPDATE clickhouse_profile SET is_default = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return get(id);
    }

    public void delete(UUID id) {
        Profile profile = get(id);
        if (profile.defaultProfile()) throw new IllegalArgumentException("Default ClickHouse profile cannot be deleted");
        jdbc.update("DELETE FROM clickhouse_profile WHERE id = ?", id);
    }

    public DiscoveryResult discover(UUID id) {
        Profile profile = get(id);
        String password = decryptPassword(id);
        List<NodeDiscovery> nodes = new ArrayList<>();
        for (String endpoint : profile.endpoints()) nodes.add(discoverNode(endpoint, profile, password));
        return new DiscoveryResult(profile.id(), profile.name(), nodes);
    }

    public NodeDiscovery test(ProfileRequest request) {
        validate(request);
        Profile transientProfile = new Profile(UUID.randomUUID(), request.name(), splitEndpoints(request.endpoints()),
                defaultValue(request.database(), "default"), defaultValue(request.username(), "default"),
                normalizeConnectionTimeout(request.connectionTimeoutMs()), normalizeQueryTimeout(request.queryTimeoutSeconds()),
                false, Instant.now(), Instant.now());
        return discoverNode(transientProfile.endpoints().getFirst(), transientProfile,
                request.password() == null ? "" : request.password());
    }

    private NodeDiscovery discoverNode(String endpoint, Profile profile, String password) {
        Instant started = Instant.now();
        try (Connection connection = open(endpoint, profile, password)) {
            String version = scalar(connection, "SELECT version()", profile.queryTimeoutSeconds());
            List<String> clusters = strings(connection, "SELECT DISTINCT cluster FROM system.clusters ORDER BY cluster",
                    profile.queryTimeoutSeconds());
            long tables = count(connection, "SELECT count() FROM system.tables WHERE database = ?", profile.database(),
                    profile.queryTimeoutSeconds());
            long replicatedTables = count(connection,
                    "SELECT count() FROM system.tables WHERE database = ? AND engine LIKE 'Replicated%'", profile.database(),
                    profile.queryTimeoutSeconds());
            long replicas = count(connection, "SELECT count() FROM system.replicas WHERE database = ?", profile.database(),
                    profile.queryTimeoutSeconds());
            long readonlyReplicas = count(connection,
                    "SELECT count() FROM system.replicas WHERE database = ? AND is_readonly = 1", profile.database(),
                    profile.queryTimeoutSeconds());
            return new NodeDiscovery(endpoint, true, version, clusters, tables, replicatedTables, replicas,
                    readonlyReplicas, Duration.between(started, Instant.now()).toMillis(), null);
        } catch (Exception e) {
            return new NodeDiscovery(endpoint, false, null, List.of(), 0, 0, 0, 0,
                    Duration.between(started, Instant.now()).toMillis(), rootMessage(e));
        }
    }

    private Connection open(String endpoint, Profile profile, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", profile.username());
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("connection_timeout", Integer.toString(profile.connectionTimeoutMs()));
        properties.setProperty("socket_timeout", Integer.toString(Math.multiplyExact(profile.queryTimeoutSeconds(), 1000)));
        return DriverManager.getConnection(jdbcUrl(endpoint, profile.database()), properties);
    }

    static String jdbcUrl(String endpoint, String database) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://")))
            throw new IllegalArgumentException("ClickHouse endpoint must start with http:// or https://");
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return "jdbc:clickhouse:" + normalized + "/" + defaultValue(database, "default");
    }

    static void validate(ProfileRequest request) {
        if (request == null || request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("ClickHouse profile name is required");
        List<String> endpoints = splitEndpoints(request.endpoints());
        if (endpoints.isEmpty()) throw new IllegalArgumentException("At least one ClickHouse endpoint is required");
        for (String endpoint : endpoints) jdbcUrl(endpoint, request.database());
        normalizeConnectionTimeout(request.connectionTimeoutMs());
        normalizeQueryTimeout(request.queryTimeoutSeconds());
    }

    private static List<String> splitEndpoints(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines().flatMap(line -> List.of(line.split(",")).stream())
                .map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }

    private static String normalizeEndpoints(String value) { return String.join("\n", splitEndpoints(value)); }
    private static int normalizeConnectionTimeout(Integer value) {
        int timeout = value == null ? 5000 : value;
        if (timeout < 100 || timeout > 120000) throw new IllegalArgumentException("Connection timeout must be 100..120000 ms");
        return timeout;
    }
    private static int normalizeQueryTimeout(Integer value) {
        int timeout = value == null ? 30 : value;
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("Query timeout must be 1..3600 seconds");
        return timeout;
    }

    private static String scalar(Connection connection, String sql, int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
        }
    }
    private static List<String> strings(Connection connection, String sql, int timeout) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (ResultSet rs = statement.executeQuery(sql)) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return List.copyOf(result);
            }
        }
    }
    private static long count(Connection connection, String sql, String database, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, database);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    private Profile map(ResultSet rs, int row) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), splitEndpoints(rs.getString("endpoints")),
                rs.getString("database_name"), rs.getString("username"), rs.getInt("connection_timeout_ms"),
                rs.getInt("query_timeout_seconds"), rs.getBoolean("is_default"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    private String encryptedPassword(UUID id) {
        return jdbc.queryForObject("SELECT encrypted_password FROM clickhouse_profile WHERE id = ?", String.class, id);
    }
    private String decryptPassword(UUID id) {
        String encrypted = encryptedPassword(id);
        return encrypted == null || encrypted.isBlank() ? "" : codec.decrypt(encrypted);
    }
    private String encryptPassword(String password) { return password == null || password.isBlank() ? null : codec.encrypt(password); }
    private void clearDefault() { jdbc.update("UPDATE clickhouse_profile SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP WHERE is_default = TRUE"); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record ProfileRequest(String name, String endpoints, String database, String username, String password,
                                 Integer connectionTimeoutMs, Integer queryTimeoutSeconds, boolean defaultProfile) { }
    public record Profile(UUID id, String name, List<String> endpoints, String database, String username,
                          int connectionTimeoutMs, int queryTimeoutSeconds, boolean defaultProfile,
                          Instant createdAt, Instant updatedAt) { }
    public record NodeDiscovery(String endpoint, boolean reachable, String version, List<String> clusters,
                                long tables, long replicatedTables, long replicas, long readonlyReplicas,
                                long latencyMs, String error) { }
    public record DiscoveryResult(UUID profileId, String profileName, List<NodeDiscovery> nodes) { }
}
