package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

@Component
public class BootstrapSettingsStore {
    private static final String ENV_PATH = "S3_PERF_BOOTSTRAP_FILE";
    private final ObjectMapper objectMapper;
    private final Path path;

    public BootstrapSettingsStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String configured = System.getenv(ENV_PATH);
        this.path = Path.of(configured == null || configured.isBlank()
                ? "config/bootstrap-settings.json" : configured).toAbsolutePath().normalize();
    }

    public synchronized BootstrapSettings load() {
        if (!Files.exists(path)) {
            return BootstrapSettings.empty();
        }
        try {
            return objectMapper.readValue(path.toFile(), BootstrapSettings.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bootstrap settings from " + path, e);
        }
    }

    public synchronized void save(BootstrapSettings settings) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            try {
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and non-POSIX filesystems do not support POSIX permissions.
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save bootstrap settings to " + path, e);
        }
    }

    public Path path() {
        return path;
    }
}
