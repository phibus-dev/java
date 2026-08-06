package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class BootstrapSettingsStore {
    private static final String ENV_PATH = "S3_PERF_BOOTSTRAP_FILE";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final ObjectMapper objectMapper;
    private final Path path;

    public BootstrapSettingsStore(ObjectMapper objectMapper) {
        this(objectMapper, configuredPath());
    }

    BootstrapSettingsStore(ObjectMapper objectMapper, Path path) {
        this.objectMapper = objectMapper;
        this.path = path.toAbsolutePath().normalize();
    }

    private static Path configuredPath() {
        String configured = System.getenv(ENV_PATH);
        return Path.of(configured == null || configured.isBlank()
                ? "config/bootstrap-settings.json" : configured);
    }

    public synchronized BootstrapSettings load() {
        if (!Files.isRegularFile(path)) {
            return BootstrapSettings.empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return BootstrapSettings.empty();
            }
            return objectMapper.readValue(json, BootstrapSettings.class);
        } catch (IOException e) {
            throw new IllegalStateException("Bootstrap configuration is corrupted: " + path, e);
        }
    }

    public synchronized void save(BootstrapSettings settings) {
        try {
            Files.createDirectories(path.getParent());
            createBackupIfPresent();

            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);

            // Validate the exact bytes before replacing the active configuration.
            BootstrapSettings validated = objectMapper.readValue(temporary.toFile(), BootstrapSettings.class);
            if (validated == null) {
                throw new IllegalStateException("Bootstrap settings validation returned no data");
            }

            try {
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and non-POSIX filesystems do not support POSIX permissions.
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save bootstrap settings to " + path, e);
        }
    }

    private void createBackupIfPresent() throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            return;
        }
        Path backupDirectory = path.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve("config-backup-" + BACKUP_TIMESTAMP.format(LocalDateTime.now()) + ".json");
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    public Path path() {
        return path;
    }
}
