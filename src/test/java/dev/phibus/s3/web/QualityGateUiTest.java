package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QualityGateUiTest {
    @Test
    void pageUsesSharedShellAndDocumentsAutomationContract() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/templates/quality-gates.html"));
        String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));

        assertThat(page).contains("primaryNav(active='quality')", "th:each=\"policy : ${policies}\"",
                "th:each=\"scenario : ${scenarios}\"", "evo-snt runs assert", "PASS", "FAIL");
        assertThat(layout).contains("href=\"/quality-gates\"", "active == 'quality'");
    }
}
