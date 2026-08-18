package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseHistoryDetailUiTest {

    @Test
    void detailPageShowsExecutionTimeMetricsAndErrors() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/clickhouse-history-detail.html"));
        String controller = Files.readString(Path.of("src/main/java/dev/phibus/s3/web/ClickHouseHistoryController.java"));

        assertThat(html)
                .contains("Фактическое время выполнения")
                .contains("actualDurationMillis")
                .contains("run.startedAt")
                .contains("run.finishedAt")
                .contains("run.bytes")
                .contains("run.queries")
                .contains("run.message")
                .contains("diagnostic-output")
                .contains("Ошибки и сообщения выполнения")
                .contains("Auto create table");
        assertThat(controller)
                .contains("Duration.between(run.startedAt(), run.finishedAt())")
                .contains("actualDurationMillis");
    }
}
