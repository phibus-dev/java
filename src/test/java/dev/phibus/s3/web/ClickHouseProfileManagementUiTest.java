package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseProfileManagementUiTest {
    @Test
    void supportsEditingAndEmptyDeleteResponses() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/clickhouse-profiles.html"));
        String script = Files.readString(Path.of("src/main/resources/static/clickhouse-profiles.js"));

        assertThat(html)
                .contains("class=\"secondary ch-edit\"")
                .contains("id=\"ch-cancel-edit\"");
        assertThat(script)
                .contains("method: editingId ? 'PUT' : 'POST'")
                .contains("return body ? JSON.parse(body) : null")
                .contains("Оставьте пароль пустым");
    }
}
