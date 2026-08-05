package dev.phibus.s3.settings;

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
public class S3ProfileService {
    private final JdbcTemplate jdbc;

    public S3ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Profile> list() {
        return jdbc.query("""
                SELECT id, name, endpoint, region, bucket, path_style_access, credentials_source,
                       vault_secret_path, access_key_field, secret_key_field, session_token_field,
                       ca_certificate_path, is_default, created_at, updated_at
                  FROM s3_profile
                 ORDER BY is_default DESC, name
                """, this::map);
    }

    public Profile get(UUID id) {
        List<Profile> result = jdbc.query("""
                SELECT id, name, endpoint, region, bucket, path_style_access, credentials_source,
                       vault_secret_path, access_key_field, secret_key_field, session_token_field,
                       ca_certificate_path, is_default, created_at, updated_at
                  FROM s3_profile WHERE id = ?
                """, this::map, id);
        if (result.isEmpty()) throw new IllegalArgumentException("S3 profile not found: " + id);
        return result.getFirst();
    }

    public Profile defaultProfile() {
        List<Profile> result = jdbc.query("""
                SELECT id, name, endpoint, region, bucket, path_style_access, credentials_source,
                       vault_secret_path, access_key_field, secret_key_field, session_token_field,
                       ca_certificate_path, is_default, created_at, updated_at
                  FROM s3_profile WHERE is_default = TRUE
                """, this::map);
        return result.isEmpty() ? null : result.getFirst();
    }

    @Transactional
    public Profile create(ProfileRequest request) {
        validate(request);
        UUID id = UUID.randomUUID();
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                INSERT INTO s3_profile(id, name, endpoint, region, bucket, path_style_access,
                    credentials_source, vault_secret_path, access_key_field, secret_key_field,
                    session_token_field, ca_certificate_path, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.name().trim(), request.endpoint().trim(), defaultValue(request.region(), "us-east-1"),
                blankToNull(request.bucket()), request.pathStyleAccess(), normalizedSource(request.credentialsSource()),
                blankToNull(request.vaultSecretPath()), defaultValue(request.accessKeyField(), "accessKey"),
                defaultValue(request.secretKeyField(), "secretKey"), defaultValue(request.sessionTokenField(), "sessionToken"),
                blankToNull(request.caCertificatePath()), request.defaultProfile());
        return get(id);
    }

    @Transactional
    public Profile update(UUID id, ProfileRequest request) {
        get(id);
        validate(request);
        if (request.defaultProfile()) clearDefault();
        jdbc.update("""
                UPDATE s3_profile
                   SET name = ?, endpoint = ?, region = ?, bucket = ?, path_style_access = ?,
                       credentials_source = ?, vault_secret_path = ?, access_key_field = ?,
                       secret_key_field = ?, session_token_field = ?, ca_certificate_path = ?,
                       is_default = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, request.name().trim(), request.endpoint().trim(), defaultValue(request.region(), "us-east-1"),
                blankToNull(request.bucket()), request.pathStyleAccess(), normalizedSource(request.credentialsSource()),
                blankToNull(request.vaultSecretPath()), defaultValue(request.accessKeyField(), "accessKey"),
                defaultValue(request.secretKeyField(), "secretKey"), defaultValue(request.sessionTokenField(), "sessionToken"),
                blankToNull(request.caCertificatePath()), request.defaultProfile(), id);
        return get(id);
    }

    public Profile cloneProfile(UUID id, String requestedName) {
        Profile source = get(id);
        String name = requestedName == null || requestedName.isBlank() ? source.name() + " copy" : requestedName.trim();
        return create(new ProfileRequest(name, source.endpoint(), source.region(), source.bucket(),
                source.pathStyleAccess(), source.credentialsSource(), source.vaultSecretPath(),
                source.accessKeyField(), source.secretKeyField(), source.sessionTokenField(),
                source.caCertificatePath(), false));
    }

    @Transactional
    public Profile makeDefault(UUID id) {
        get(id);
        clearDefault();
        jdbc.update("UPDATE s3_profile SET is_default = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return get(id);
    }

    public void delete(UUID id) {
        Profile profile = get(id);
        if (profile.defaultProfile()) throw new IllegalArgumentException("Default S3 profile cannot be deleted");
        jdbc.update("DELETE FROM s3_profile WHERE id = ?", id);
    }

    private void clearDefault() {
        jdbc.update("UPDATE s3_profile SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP WHERE is_default = TRUE");
    }

    private Profile map(ResultSet rs, int row) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("endpoint"),
                rs.getString("region"), rs.getString("bucket"), rs.getBoolean("path_style_access"),
                rs.getString("credentials_source"), rs.getString("vault_secret_path"),
                rs.getString("access_key_field"), rs.getString("secret_key_field"),
                rs.getString("session_token_field"), rs.getString("ca_certificate_path"),
                rs.getBoolean("is_default"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void validate(ProfileRequest request) {
        if (request == null || request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("Profile name is required");
        if (request.endpoint() == null || request.endpoint().isBlank())
            throw new IllegalArgumentException("S3 endpoint is required");
        String source = normalizedSource(request.credentialsSource());
        if ("VAULT".equals(source) && (request.vaultSecretPath() == null || request.vaultSecretPath().isBlank()))
            throw new IllegalArgumentException("Vault secret path is required for VAULT credentials");
    }

    private static String normalizedSource(String value) {
        String source = defaultValue(value, "VAULT").toUpperCase();
        if (!List.of("VAULT", "ENVIRONMENT", "MANUAL").contains(source))
            throw new IllegalArgumentException("Unsupported credentials source: " + source);
        return source;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ProfileRequest(String name, String endpoint, String region, String bucket,
                                 boolean pathStyleAccess, String credentialsSource, String vaultSecretPath,
                                 String accessKeyField, String secretKeyField, String sessionTokenField,
                                 String caCertificatePath, boolean defaultProfile) { }

    public record Profile(UUID id, String name, String endpoint, String region, String bucket,
                          boolean pathStyleAccess, String credentialsSource, String vaultSecretPath,
                          String accessKeyField, String secretKeyField, String sessionTokenField,
                          String caCertificatePath, boolean defaultProfile, Instant createdAt, Instant updatedAt) { }
}
