package dev.phibus.s3.clickhouse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseConnectionProvider {
    private final ObjectProvider<ClickHouseProfileService> profiles;

    public ClickHouseConnectionProvider(ObjectProvider<ClickHouseProfileService> profiles) {
        this.profiles = profiles;
    }

    public Connection open(UUID profileId, String requestedEndpoint) throws SQLException {
        return open(profileService().connectionSpec(profileId, requestedEndpoint));
    }

    public Connection open(ClickHouseConnectionSpec spec) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", spec.username() == null ? "default" : spec.username());
        properties.setProperty("password", spec.password() == null ? "" : spec.password());
        properties.setProperty("connection_timeout", Integer.toString(spec.connectionTimeoutMs()));
        properties.setProperty("socket_timeout", Integer.toString(Math.multiplyExact(spec.queryTimeoutSeconds(), 1000)));
        return DriverManager.getConnection(jdbcUrl(spec.endpoint(), spec.database()), properties);
    }

    public String endpoint(UUID profileId, String requestedEndpoint) {
        return profileService().connectionSpec(profileId, requestedEndpoint).endpoint();
    }

    public int queryTimeoutSeconds(UUID profileId) {
        return profileService().get(profileId).queryTimeoutSeconds();
    }

    private ClickHouseProfileService profileService() {
        ClickHouseProfileService service = profiles.getIfAvailable();
        if (service == null) throw new IllegalStateException("ClickHouse profiles are available only on coordinator");
        return service;
    }

    private static String jdbcUrl(String endpoint, String database) {
        String normalized = endpoint.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        String db = database == null || database.isBlank() ? "default" : database.trim();
        return "jdbc:clickhouse:" + normalized + "/" + db;
    }
}
