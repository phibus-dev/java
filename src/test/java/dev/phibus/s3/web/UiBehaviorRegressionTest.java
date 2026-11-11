package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UiBehaviorRegressionTest {

    @Test
    void globalSearchUsesServerSideHistoryQuery() throws IOException {
        String ui = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        String home = Files.readString(Path.of("src/main/resources/static/home-dashboard.js"));
        String controller = Files.readString(Path.of("src/main/java/dev/phibus/s3/web/HistoryController.java"));

        assertThat(ui).contains("/history?q=${encodeURIComponent(query)}").doesNotContain("/history?search=");
        assertThat(home).contains("/history?q=${encodeURIComponent(q)}").doesNotContain("/history?search=");
        assertThat(controller).contains("name = \"q\"");
    }

    @Test
    void commonUiDoesNotReportSuccessBeforeRequestCompletes() throws IOException {
        String ui = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        assertThat(ui)
                .doesNotContain("Операция отправлена")
                .doesNotContain("enhanceForms")
                .contains("toast.setAttribute('role', type === 'error' ? 'alert' : 'status')");
    }

    @Test
    void breadcrumbsUseKnownRoutesInsteadOfSyntheticIntermediateLinks() throws IOException {
        String ui = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        assertThat(ui)
                .contains("/^\\/run-history\\/[^/]+$/")
                .contains("['История', '/history'], ['Результат запуска']")
                .contains("/^\\/clickhouse\\/history\\/[^/]+$/")
                .contains("['Тесты и история', '/clickhouse']")
                .doesNotContain("path+=`/${p}`");
    }

    @Test
    void auditTableRendersUntrustedValuesAsText() throws IOException {
        String audit = Files.readString(Path.of("src/main/resources/static/audit.js"));
        assertThat(audit)
                .contains("cell.textContent")
                .contains("rows.replaceChildren()")
                .doesNotContain("innerHTML");
    }

    @Test
    void userMenuAndTablesReceiveAccessibleSemantics() throws IOException {
        String ui = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        assertThat(ui)
                .contains("aria-expanded")
                .contains("event.key === 'Escape'")
                .contains("header.setAttribute('scope', 'col')")
                .contains("caption.className = 'sr-only'");
    }

    @Test
    void homeDashboardKeepsKafkaHealthCard() throws IOException {
        String home = Files.readString(Path.of("src/main/resources/static/home-dashboard.js"));
        assertThat(home).contains("statusCard('Kafka', 'Профили и тесты'");
    }
}
