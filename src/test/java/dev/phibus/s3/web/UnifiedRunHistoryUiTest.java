package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnifiedRunHistoryUiTest {
    @Test
    void unifiedHistoryExposesCommonFiltersAndAllRunTypes() throws IOException {
        String history = Files.readString(Path.of("src/main/resources/templates/history.html"));
        String detail = Files.readString(Path.of("src/main/resources/templates/run-history-detail.html"));

        assertThat(history)
                .contains("История запусков")
                .contains("name=\"scope\"")
                .contains("name=\"testType\"")
                .contains("name=\"from\"")
                .contains("name=\"to\"")
                .contains("DISTRIBUTED")
                .contains("CLICKHOUSE")
                .contains("KAFKA")
                .contains("rerun-run")
                .contains("Распределённые результаты дедуплицированы")
                .contains("history-trends.js");
        assertThat(detail)
                .contains("Причина остановки")
                .contains("Специализированный отчёт")
                .contains("run-history.js")
                .doesNotContain("accessKey")
                .doesNotContain("secretKey");
    }

    @Test
    void historyIsAvailableFromPrimaryNavigationAndS3KeepsItsFilteredShortcut() throws IOException {
        String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));

        assertThat(layout)
                .contains("href=\"/history\"")
                .contains(">История</a>")
                .contains("/history?scope=LOCAL&amp;testType=S3")
                .contains("История S3");
    }

    @Test
    void rerunScriptUsesEncodedIdentifiersCsrfFetchAndEscapesOutput() throws IOException {
        String history = Files.readString(Path.of("src/main/resources/templates/history.html"));
        String script = Files.readString(Path.of("src/main/resources/static/run-history.js"));

        assertThat(history).contains("/csrf-fetch.js").contains("/run-history.js");
        assertThat(script)
                .contains("encodeURIComponent(button.dataset.runId)")
                .contains("method: 'POST'")
                .contains("escapeHtml(run.runId)")
                .doesNotContain("innerHTML = error");
    }
}
