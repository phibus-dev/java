package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UnifiedHeaderUiTest {

    @Test
    void sharedLayoutOwnsHeaderNavigationFooterAndVersion() throws IOException {
        String js = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        String css = Files.readString(Path.of("src/main/resources/static/app-ui.css"));
        String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));

        assertThat(js)
                .contains("header-version")
                .contains("footer-version")
                .contains("aria-expanded")
                .doesNotContain("ensureBrandHeader");
        assertThat(css)
                .contains(".brand-header {")
                .contains(".brand-home-link {")
                .contains(".header-version {");
        assertThat(layout)
                .contains("th:fragment=\"header(title, subtitle)\"")
                .contains("th:fragment=\"primaryNav(active)\"")
                .contains("th:fragment=\"footer\"")
                .contains("brand-home-link");
    }

    @Test
    void everyPageTemplateUsesSharedShell() throws IOException {
        try (Stream<Path> templates = Files.list(Path.of("src/main/resources/templates"))) {
            for (Path template : templates.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(template);
                assertThat(html).as(template.toString())
                        .contains("fragments/layout :: headAssets")
                        .contains("fragments/layout :: header")
                        .contains("fragments/layout :: primaryNav")
                        .contains("fragments/layout :: footer")
                        .contains("fragments/layout :: commonScripts")
                        .doesNotContain("href=\"/app.css\"")
                        .doesNotContain("href=\"/app-ui.css")
                        .doesNotContain("src=\"/app-ui.js");
            }
        }
    }
}
