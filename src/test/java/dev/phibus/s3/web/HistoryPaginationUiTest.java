package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryPaginationUiTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void initializesPaginationBeforeSessionRequestAndUsesExplicitRows() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        String styles = Files.readString(Path.of("src/main/resources/static/app-ui.css"));

        assertThat(script.indexOf("enableHistoryPagination()"))
                .isLessThan(script.indexOf("await loadSession()"));
        assertThat(script)
                .contains("tbody[data-history-page]")
                .contains("refreshHistoryPagination")
                .contains("classList.add('history-row-hidden')");
        assertThat(styles).contains(".history-row-hidden{display:none!important}");
    }

    @Test
    void marksEveryPaginatedHistoryAndRefreshesDynamicKafkaTables() throws IOException {
        for (String template : List.of("kafka.html", "kafka-m2.html", "kafka-m3.html", "kafka-m4.html",
                "clickhouse-tests.html", "clickhouse-replicated-tests.html", "clickhouse-failover-tests.html")) {
            assertThat(Files.readString(TEMPLATES.resolve(template)))
                    .as(template)
                    .contains("data-history-page");
        }
        for (String script : List.of("kafka.js", "kafka-m2.js", "kafka-m3.js", "kafka-m4.js")) {
            assertThat(Files.readString(Path.of("src/main/resources/static").resolve(script)))
                    .as(script)
                    .contains("refreshHistoryPagination");
        }
    }

    @Test
    void commonUiScriptIsCacheBustedInEveryTemplate() throws IOException {
        try (var paths = Files.list(TEMPLATES)) {
            for (Path template : paths.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(template);
                if (html.contains("/app-ui.js")) {
                    assertThat(html).as(template.getFileName().toString())
                            .contains("/app-ui.js?v=20260825.3");
                }
            }
        }
    }
}
