package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KafkaM3ErrorUiTest {
    @Test
    void rendersReplicationFailureInline() throws IOException {
        String script = Files.readString(Path.of("src/main/resources/static/kafka-m3.js"));
        String page = Files.readString(Path.of("src/main/resources/templates/kafka-m3.html"));

        assertThat(page).contains("id=\"repDiagnostic\"");
        assertThat(script)
                .contains("problem.message||problem.detail||problem.error")
                .contains("$('repStatus').textContent='FAILED'")
                .contains("$('repDiagnostic').textContent=e.message")
                .contains("run.errorMessage||''");
    }
}
