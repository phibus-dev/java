package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ApplicationUiConsistencyTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void usesSingleDeterministicStylesheetEntryPoint() throws IOException {
        String entry = Files.readString(Path.of("src/main/resources/static/application.css"));
        String base = Files.readString(Path.of("src/main/resources/static/app.css"));
        String ui = Files.readString(Path.of("src/main/resources/static/app-ui.css"));
        String layout = Files.readString(TEMPLATES.resolve("fragments/layout.html"));

        assertThat(entry)
                .contains("@import url('/app.css?v=20261109.1')")
                .contains("@import url('/app-ui.css?v=20261109.1')");
        assertThat(base).doesNotContain("@import url('/app-ui.css')");
        assertThat(layout).contains("/application.css?v=20261109.1");
        assertThat(ui)
                .doesNotContain("data-ui-view")
                .doesNotContain("ui-view-switcher");
    }

    @Test
    void everyPageUsesFragmentsAndHasNoInlineStyleOrScript() throws IOException {
        try (Stream<Path> paths = Files.list(TEMPLATES)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".html")).toList()) {
                String html = Files.readString(path);
                assertThat(html).as(path.getFileName().toString())
                        .contains("fragments/layout :: headAssets")
                        .contains("fragments/layout :: header")
                        .contains("fragments/layout :: primaryNav")
                        .contains("fragments/layout :: footer")
                        .contains("fragments/layout :: commonScripts")
                        .contains("— ЭВО.СНТ</title>")
                        .doesNotContain("<style")
                        .doesNotContain(" style=\"")
                        .doesNotContain("<script>")
                        .doesNotContain("onclick=");
            }
        }
    }

    @Test
    void sharedNavigationUsesConsistentLabelsAndAccessibleState() throws IOException {
        String layout = Files.readString(TEMPLATES.resolve("fragments/layout.html"));

        assertThat(layout)
                .contains("aria-label=\"Основная навигация\"")
                .contains("aria-label=\"Разделы S3\"")
                .contains("aria-label=\"Разделы ClickHouse\"")
                .contains("aria-label=\"Разделы Kafka\"")
                .contains("Consumer / Throughput")
                .contains("aria-current=${active ==")
                .doesNotContain("Consumer\\Throughput");
    }
}
