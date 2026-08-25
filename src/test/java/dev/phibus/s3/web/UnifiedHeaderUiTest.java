package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UnifiedHeaderUiTest {

    @Test
    void sharedUiNormalizesHeaderAndDisplaysVersion() throws IOException {
        String js = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        String css = Files.readString(Path.of("src/main/resources/static/app-ui.css"));

        assertThat(js)
                .contains("header-version")
                .contains("version.textContent=`Версия ${resolvedVersion}`")
                .contains("brand-home-link");
        assertThat(css)
                .contains(".brand-header{display:grid!important")
                .contains(".brand-home-link{flex-direction:column")
                .contains(".header-version{justify-self:center!important");
    }

    @Test
    void everyTemplateUsesSameCacheBustedHeaderAssets() throws IOException {
        try (Stream<Path> templates = Files.list(Path.of("src/main/resources/templates"))) {
            for (Path template : templates.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(template);
                if (html.contains("/app-ui.js")) {
                    assertThat(html).as(template.toString())
                            .contains("/app-ui.css?v=20260825.3")
                            .contains("/app-ui.js?v=20260825.3");
                }
            }
        }
    }
}
