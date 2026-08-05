package dev.phibus.s3.history;

import dev.phibus.s3.settings.BootstrapSecretCodec;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.SettingsService;
import dev.phibus.s3.test.TestRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRequestMetadataUpdater {
    private final SettingsService settingsService;
    private final BootstrapSecretCodec codec;

    public HistoryRequestMetadataUpdater(SettingsService settingsService, BootstrapSecretCodec codec) {
        this.settingsService = settingsService;
        this.codec = codec;
    }

    public void update(UUID id, TestRequest request) {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (!settings.configured()) return;
        String sql = """
                update test_run
                   set operation=?, path_style_access=?, object_size_mib=?, part_size_mib=?,
                       parallelism=?, object_count=?
                 where id=?
                """;
        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl(), settings.username(),
                codec.decrypt(settings.encryptedPassword()));
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, request.normalizedOperation());
            ps.setBoolean(2, request.pathStyleAccess());
            ps.setLong(3, request.objectSizeMiB());
            ps.setLong(4, request.partSizeMiB());
            ps.setInt(5, request.parallelism());
            ps.setInt(6, request.objectCount());
            ps.setObject(7, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TestHistoryStore.HistoryPersistenceException("Cannot update test request metadata", e);
        }
    }
}
