package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KafkaHtmlEscapingUiTest {
    @Test
    void kafkaHistoryEscapesProfileTopicStatusAndDistributedRunName() throws IOException {
        String m2 = Files.readString(Path.of("src/main/resources/static/kafka-m2.js"));
        String m4 = Files.readString(Path.of("src/main/resources/static/kafka-m4.js"));

        assertThat(m2)
                .contains("${esc(r.testType)}")
                .contains("${esc(r.status)}")
                .contains("${esc(r.consistencyStatus||'—')}");
        assertThat(m4)
                .contains("${esc(p.name)}")
                .contains("p.defaultProfile?'selected':''")
                .contains("${esc(r.topic)}")
                .contains("${esc(r.name||'')}")
                .contains("${esc(r.latencyScope||'UNKNOWN')}")
                .contains("${esc(r.latencyAggregation||'NO_SAMPLES')}")
                .doesNotContain("${p.name}</option>")
                .doesNotContain("<td>${r.topic}</td>");
    }
}
