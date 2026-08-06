package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HealthDashboardUiTest {

    @Test
    void exposesDashboardWithoutInlineJavaScript() throws IOException {
        String template = resource("/templates/monitoring.html");
        String script = resource("/static/monitoring.js");

        assertThat(template)
                .contains("href=\"/monitoring\"")
                .contains("id=\"health-components\"")
                .contains("src=\"/monitoring.js\"")
                .doesNotContain("<script>")
                .doesNotContain("onclick=");
        assertThat(script)
                .contains("/api/health/overview")
                .contains("setInterval")
                .contains("30000")
                .contains("replaceChildren")
                .doesNotContain("innerHTML");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
