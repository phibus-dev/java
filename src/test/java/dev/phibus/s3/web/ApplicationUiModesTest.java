package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApplicationUiModesTest {

    @Test
    void applicationProvidesFourPersistentUiModes() throws IOException {
        String js = Files.readString(Path.of("src/main/resources/static/app-ui.js"));
        String css = Files.readString(Path.of("src/main/resources/static/app-ui.css"));
        String clickhouse = Files.readString(Path.of("src/main/resources/templates/clickhouse-tests.html"));
        String replicated = Files.readString(Path.of("src/main/resources/templates/clickhouse-replicated-history-detail.html"));
        String monitoring = Files.readString(Path.of("src/main/resources/templates/monitoring.html"));
        String tasks = Files.readString(Path.of("src/main/resources/templates/index.html"));

        assertThat(js)
                .contains("evo-snt-ui-view")
                .contains("id:'A'")
                .contains("id:'B'")
                .contains("id:'C'")
                .contains("id:'D'")
                .contains("localStorage")
                .contains("data-ui-mode");
        assertThat(css)
                .contains("data-ui-view=\"A\"")
                .contains("data-ui-view=\"B\"")
                .contains("data-ui-view=\"C\"")
                .contains("data-ui-view=\"D\"")
                .contains("ui-view-switcher");
        assertThat(clickhouse).contains("/app-ui.css").contains("/app-ui.js");
        assertThat(replicated).contains("/app-ui.css").contains("/app-ui.js");
        assertThat(monitoring).contains("/app-ui.css").contains("/app-ui.js");
        assertThat(tasks).contains("/app-ui.css").contains("/app-ui.js");
    }
}
