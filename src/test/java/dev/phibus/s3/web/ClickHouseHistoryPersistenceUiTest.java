package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseHistoryPersistenceUiTest {
    @Test
    void waitsForTerminalHistoryBeforeReloadingPage() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/clickhouse-tests.js"));

        assertThat(script)
                .contains("await waitForPersistedRun(run.id, run.status)")
                .contains("/api/clickhouse/history/${encodeURIComponent(id)}")
                .contains("(await response.json()).status === terminalStatus")
                .contains("итоговый результат пока не сохранён");
    }
}
