package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestPresetUiTest {

    @Test
    void rendersPresetManagementControlsAndExternalScript() throws IOException {
        String template = resource("/templates/index.html");

        assertThat(template)
                .contains("id=\"preset-name\"")
                .contains("id=\"preset-select\"")
                .contains("id=\"save-preset\"")
                .contains("id=\"load-preset\"")
                .contains("id=\"delete-preset\"")
                .contains("src=\"/test-presets.js\"");
    }

    @Test
    void doesNotPersistCredentialsAndAvoidsUnsafeDomRendering() throws IOException {
        String script = resource("/static/test-presets.js");

        assertThat(script)
                .contains("SENSITIVE_FIELDS")
                .contains("'accessKey', 'secretKey'")
                .contains("localStorage")
                .contains("replaceChildren")
                .doesNotContain("innerHTML")
                .doesNotContain("accessKey: value")
                .doesNotContain("secretKey: value");
    }

    @Test
    void clearsCredentialInputsAfterLoadingPreset() throws IOException {
        String script = resource("/static/test-presets.js");

        assertThat(script)
                .contains("if (accessKey) accessKey.value = ''")
                .contains("if (secretKey) secretKey.value = ''");
    }

    @Test
    void selectingPresetAutomaticallyLoadsSavedConfiguration() throws IOException {
        String script = resource("/static/test-presets.js");

        assertThat(script)
                .contains("function loadSelectedPreset()")
                .contains("select.addEventListener('change'")
                .contains("loadSelectedPreset();")
                .contains("assignField('profileId', config.profileId, true)")
                .contains("assignField('scenario', config.scenario, true)")
                .contains("syncBucketSelector(config.bucket)")
                .contains("byId('load-preset').addEventListener('click', loadSelectedPreset)");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
