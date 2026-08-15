package dev.phibus.s3.clickhouse;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseConnectionProvider {
    private final ClickHouseProfileService profiles;
    private final JdbcTemplate jdbc;
    private final BootstrapSecretCodec codec;

    public ClickHouseConnectionProvider(ClickHouseProfileService profiles, JdbcTemplate jdbc, BootstrapSecretCodec codec) {
        this.profiles = profiles;
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Connection open(UUID profileId, String requestedEndpoint) throws SQLException {
        ClickHouseProfileService.Profile profile = profiles.get(profileId);
        String endpoint = selectEndpoint(profile, requestedEndpoint);
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_password FROM clickhouse_profile WHERE id = ?", String.class, profileId);
        String password = encrypted == null || encrypted.isBlank() ? "" : codec.decrypt(encrypted);

        Properties properties = new Properties();
        properties.setProperty("user", profile.username());
        properties.setProperty("password", password);
        properties.setProperty("connection_timeout", Integer.toString(profile.connectionTimeoutMs()));
        properties.setProperty("socket_timeout", Integer.toString(Math.multiplyExact(profile.queryTimeoutSeconds(), 1000)));
        return DriverManager.getConnection(jdbcUrl(endpoint, profile.database()), properties);
    }

    public String endpoint(UUID profileId, String requestedEndpoint) {
        return selectEndpoint(profiles.get(profileId), requestedEndpoint);
    }

    private static String selectEndpoint(ClickHouseProfileService.Profile profile, String requestedEndpoint) {
        if (requestedEndpoint == null || requestedEndpoint.isBlank()) return profile.endpoints().getFirst();
        String normalized = requestedEndpoint.trim();
        if (!profile.endpoints().contains(normalized)) {
            throw new IllegalArgumentException("Endpoint is not part of ClickHouse profile: " + normalized);
        }
        return normalized;
    }

    private static String jdbcUrl(String endpoint, String database) {
        String normalized = endpoint.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        String db = database == null || database.isBlank() ? "default" : database.trim();
        return "jdbc:clickhouse:" + normalized + "/" + db;
    }
}
