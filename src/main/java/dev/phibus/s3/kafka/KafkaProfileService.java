package dev.phibus.s3.kafka;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KafkaProfileService {
    private final JdbcTemplate jdbc;

    public KafkaProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Profile> list() {
        return jdbc.query("""
                SELECT id, name, bootstrap_servers, security_protocol, sasl_mechanism, username,
                       credentials_source, vault_secret_path, password_field, password_env,
                       ca_certificate_path, default_topic, client_id_prefix, is_default,
                       created_at, updated_at
                  FROM kafka_profile
                 ORDER BY is_default DESC, name
                """, this::map);
    }

    public Profile get(UUID id) {
        List<Profile> result = jdbc.query("""
                SELECT id, name, bootstrap_servers, security_protocol, sasl_mechanism, username,
                       credentials_source, vault_secret_path, password_field, password_env,
                       ca_certificate_path, default_topic, client_id_prefix, is_default,
                       created_at, updated_at
                  FROM kafka_profile WHERE id = ?
                """, this::map, id);
        if (result.isEmpty()) throw new IllegalArgumentException("Kafka profile not found: " + id);
        return result.getFirst();
    }

    public Profile defaultProfile() {
        return list().stream().filter(Profile::defaultProfile).findFirst().orElse(null);
    }

    @Transactional
    public Profile create(ProfileRequest request) {
        validate(request);
        UUID id = UUID.randomUUID();
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                INSERT INTO kafka_profile(id, name, bootstrap_servers, security_protocol, sasl_mechanism,
                    username, credentials_source, vault_secret_path, password_field, password_env,
                    ca_certificate_path, default_topic, client_id_prefix, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.name().trim(), request.bootstrapServers().trim(),
                normalizedProtocol(request.securityProtocol()), blankToNull(request.saslMechanism()),
                blankToNull(request.username()), normalizedSource(request.credentialsSource()),
                blankToNull(request.vaultSecretPath()), defaultValue(request.passwordField(), "password"),
                blankToNull(request.passwordEnv()), blankToNull(request.caCertificatePath()),
                blankToNull(request.defaultTopic()), defaultValue(request.clientIdPrefix(), "evo-snt"),
                request.defaultProfile());
        return get(id);
    }

    @Transactional
    public Profile update(UUID id, ProfileRequest request) {
        get(id);
        validate(request);
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                UPDATE kafka_profile
                   SET name=?, bootstrap_servers=?, security_protocol=?, sasl_mechanism=?, username=?,
                       credentials_source=?, vault_secret_path=?, password_field=?, password_env=?,
                       ca_certificate_path=?, default_topic=?, client_id_prefix=?, is_default=?,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, request.name().trim(), request.bootstrapServers().trim(),
                normalizedProtocol(request.securityProtocol()), blankToNull(request.saslMechanism()),
                blankToNull(request.username()), normalizedSource(request.credentialsSource()),
                blankToNull(request.vaultSecretPath()), defaultValue(request.passwordField(), "password"),
                blankToNull(request.passwordEnv()), blankToNull(request.caCertificatePath()),
                blankToNull(request.defaultTopic()), defaultValue(request.clientIdPrefix(), "evo-snt"),
                request.defaultProfile(), id);
        return get(id);
    }

    @Transactional
    public Profile makeDefault(UUID id) {
        get(id);
        clearDefault();
        jdbc.update("UPDATE kafka_profile SET is_default=TRUE, updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return get(id);
    }

    public void delete(UUID id) {
        Profile profile = get(id);
        if (profile.defaultProfile()) throw new IllegalArgumentException("Default Kafka profile cannot be deleted");
        jdbc.update("DELETE FROM kafka_profile WHERE id=?", id);
    }

    private void clearDefault() {
        jdbc.update("UPDATE kafka_profile SET is_default=FALSE, updated_at=CURRENT_TIMESTAMP WHERE is_default=TRUE");
    }

    private Profile map(ResultSet rs, int row) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("bootstrap_servers"),
                rs.getString("security_protocol"), rs.getString("sasl_mechanism"), rs.getString("username"),
                rs.getString("credentials_source"), rs.getString("vault_secret_path"), rs.getString("password_field"),
                rs.getString("password_env"), rs.getString("ca_certificate_path"), rs.getString("default_topic"),
                rs.getString("client_id_prefix"), rs.getBoolean("is_default"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void validate(ProfileRequest r) {
        if (r == null || r.name() == null || r.name().isBlank()) throw new IllegalArgumentException("Profile name is required");
        if (r.bootstrapServers() == null || r.bootstrapServers().isBlank()) throw new IllegalArgumentException("Kafka bootstrap servers are required");
        String protocol = normalizedProtocol(r.securityProtocol());
        if (protocol.startsWith("SASL_") && (r.saslMechanism() == null || r.saslMechanism().isBlank()))
            throw new IllegalArgumentException("SASL mechanism is required for " + protocol);
        String source = normalizedSource(r.credentialsSource());
        if (protocol.startsWith("SASL_") && "VAULT".equals(source) && (r.vaultSecretPath() == null || r.vaultSecretPath().isBlank()))
            throw new IllegalArgumentException("Vault secret path is required for SASL/VAULT");
    }

    private static String normalizedProtocol(String value) {
        String protocol = defaultValue(value, "PLAINTEXT").toUpperCase();
        if (!List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL").contains(protocol))
            throw new IllegalArgumentException("Unsupported Kafka security protocol: " + protocol);
        return protocol;
    }

    private static String normalizedSource(String value) {
        String source = defaultValue(value, "VAULT").toUpperCase();
        if (!List.of("VAULT", "ENVIRONMENT", "NONE").contains(source))
            throw new IllegalArgumentException("Unsupported Kafka credentials source: " + source);
        return source;
    }

    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ProfileRequest(String name, String bootstrapServers, String securityProtocol, String saslMechanism,
                                 String username, String credentialsSource, String vaultSecretPath, String passwordField,
                                 String passwordEnv, String caCertificatePath, String defaultTopic, String clientIdPrefix,
                                 boolean defaultProfile) { }

    public record Profile(UUID id, String name, String bootstrapServers, String securityProtocol, String saslMechanism,
                          String username, String credentialsSource, String vaultSecretPath, String passwordField,
                          String passwordEnv, String caCertificatePath, String defaultTopic, String clientIdPrefix,
                          boolean defaultProfile, Instant createdAt, Instant updatedAt) { }
}
