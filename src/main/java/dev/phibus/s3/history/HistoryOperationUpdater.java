package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryOperationUpdater {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;
    private volatile String migratedJdbcUrl;

    public HistoryOperationUpdater(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public void update(UUID runId, String operation) {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (!settings.configured()) return;
        ensureMigrated(settings);
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), settings.username(),
                codec.decrypt(settings.encryptedPassword()));
             PreparedStatement ps = connection.prepareStatement("update test_run set operation=? where id=?")) {
            ps.setString(1, operation == null || operation.isBlank() ? "UPLOAD" : operation);
            ps.setObject(2, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot persist test operation", e);
        }
    }

    private synchronized void ensureMigrated(BootstrapSettings.PostgreSqlSettings settings) {
        if (settings.jdbcUrl().equals(migratedJdbcUrl)) return;
        Flyway.configure().dataSource(settings.jdbcUrl(), settings.username(), codec.decrypt(settings.encryptedPassword()))
                .locations("classpath:db/migration").load().migrate();
        migratedJdbcUrl = settings.jdbcUrl();
    }
}
