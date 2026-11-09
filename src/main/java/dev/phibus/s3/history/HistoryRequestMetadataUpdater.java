package dev.phibus.s3.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public HistoryRequestMetadataUpdater(SettingsService settingsService, BootstrapSecretCodec codec,
                                         ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    public void update(UUID id, TestRequest request) {
        BootstrapSettings.PostgreSqlSettings settings = settingsService.load().postgresql();
        if (!settings.configured()) return;
        String sql = """
                update test_run
                   set operation=?, path_style_access=?, object_size_mib=?, part_size_mib=?,
                       parallelism=?, object_count=?, execution_mode=?, configured_duration_seconds=?,
                       warmup_seconds=?, workload_profile=?, workload_weights_json=?,
                       target_operations_per_second=?, operation_threads_json=?, profile_id=?
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
            ps.setString(7, request.normalizedExecutionMode());
            ps.setLong(8, request.effectiveDurationSeconds());
            ps.setLong(9, request.effectiveWarmupSeconds());
            ps.setString(10, request.normalizedWorkloadProfile());
            ps.setString(11, writeMap(request.normalizedWorkloadWeights()));
            ps.setInt(12, request.targetOperationsPerSecond());
            ps.setString(13, writeMap(request.operationThreads()));
            ps.setObject(14, request.profileId());
            ps.setObject(15, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TestHistoryStore.HistoryPersistenceException("Cannot update test request metadata", e);
        }
    }

    private String writeMap(java.util.Map<String,Integer> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? java.util.Map.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize test request metadata", e);
        }
    }
}
