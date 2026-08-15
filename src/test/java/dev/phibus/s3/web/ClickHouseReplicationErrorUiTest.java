package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseReplicationErrorUiTest {
    @Test
    void rendersApiFailureInlineInsteadOfOpeningBrowserAlert() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/clickhouse-replication.js"));

        assertThat(script)
                .contains("role','alert")
                .contains("Не удалось получить состояние репликации")
                .doesNotContain("alert(e.message)");
    }
}
