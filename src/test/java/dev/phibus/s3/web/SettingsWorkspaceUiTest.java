package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SettingsWorkspaceUiTest {

    @Test
    void exposesAllSettingsSectionsAndPreservesExistingApiFields() throws IOException {
        String template = resource("/templates/settings.html");

        assertThat(template)
                .contains("class=\"brand-header\"")
                .contains("href=\"/settings/keycloak\"")
                .contains("href=\"/settings/s3-profiles\"")
                .contains("href=\"/settings/configuration\"")
                .contains("id=\"postgresql\"")
                .contains("id=\"vault\"")
                .contains("id=\"system-information\"")
                .contains("id=\"jdbcUrl\"")
                .contains("id=\"vaultAuthMethod\"")
                .contains("id=\"s3CredentialsSource\"");
    }

    @Test
    void providesConnectionDiagnosticsAndSafeSecretCleanup() throws IOException {
        String script = resource("/static/settings.js");

        assertThat(script)
                .contains("runConnectionTest")
                .contains("performance.now()")
                .contains("/api/settings/test/postgresql")
                .contains("/api/settings/test/vault")
                .contains("postgresPassword', 'vaultToken', 'vaultSecretId', 's3AccessKey', 's3SecretKey")
                .doesNotContain("innerHTML");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
