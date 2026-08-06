package dev.phibus.s3.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapSettingsStoreTest {
    @TempDir
    Path directory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsEmptySettingsWhenFileDoesNotExist() {
        BootstrapSettingsStore store = new BootstrapSettingsStore(objectMapper, directory.resolve("bootstrap-settings.json"));

        BootstrapSettings settings = store.load();

        assertThat(settings.postgresql().configured()).isFalse();
        assertThat(settings.keycloak().enabled()).isFalse();
    }

    @Test
    void returnsEmptySettingsForZeroLengthAndWhitespaceFiles() throws Exception {
        Path file = directory.resolve("bootstrap-settings.json");
        BootstrapSettingsStore store = new BootstrapSettingsStore(objectMapper, file);

        Files.createFile(file);
        assertThat(store.load().keycloak().enabled()).isFalse();

        Files.writeString(file, " \n\t ");
        assertThat(store.load().postgresql().configured()).isFalse();
    }

    @Test
    void acceptsPostgresqlConfigurationWithoutKeycloakSection() throws Exception {
        Path file = directory.resolve("bootstrap-settings.json");
        Files.writeString(file, """
                {
                  "postgresql": {
                    "jdbcUrl": "jdbc:postgresql://localhost:5432/evo",
                    "username": "evo",
                    "encryptedPassword": "ciphertext"
                  }
                }
                """);
        BootstrapSettingsStore store = new BootstrapSettingsStore(objectMapper, file);

        BootstrapSettings settings = store.load();

        assertThat(settings.postgresql().configured()).isTrue();
        assertThat(settings.keycloak().enabled()).isFalse();
    }

    @Test
    void reportsCorruptNonEmptyJson() throws Exception {
        Path file = directory.resolve("bootstrap-settings.json");
        Files.writeString(file, "{not-json");
        BootstrapSettingsStore store = new BootstrapSettingsStore(objectMapper, file);

        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bootstrap configuration is corrupted")
                .hasMessageContaining(file.toString());
    }

    @Test
    void validatesSaveAndCreatesBackupOfPreviousConfiguration() throws Exception {
        Path file = directory.resolve("bootstrap-settings.json");
        Files.writeString(file, "{}\n");
        BootstrapSettingsStore store = new BootstrapSettingsStore(objectMapper, file);
        BootstrapSettings updated = new BootstrapSettings(
                new BootstrapSettings.PostgreSqlSettings("jdbc:postgresql://db:5432/evo", "evo", "encrypted"),
                BootstrapSettings.VaultSettings.empty(),
                BootstrapSettings.S3ProfileSettings.empty(),
                BootstrapSettings.KeycloakSettings.empty());

        store.save(updated);

        assertThat(store.load().postgresql().jdbcUrl()).isEqualTo("jdbc:postgresql://db:5432/evo");
        try (var files = Files.list(directory.resolve("backups"))) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("config-backup-")).count())
                    .isEqualTo(1);
        }
    }
}
