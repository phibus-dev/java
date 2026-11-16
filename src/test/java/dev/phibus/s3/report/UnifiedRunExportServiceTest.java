package dev.phibus.s3.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.RunStatus;
import dev.phibus.s3.lifecycle.RunStopReason;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.test.TestType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UnifiedRunExportServiceTest {
    private final UnifiedRunExportService service = new UnifiedRunExportService(
            new ObjectMapper().findAndRegisterModules(), "");

    @Test
    void exportsTheSameUnifiedHistoryRowAsJsonCsvAndHtml() {
        UnifiedRunHistoryStore.RunHistoryRow run = run();

        UnifiedRunExportService.Export json = service.export(run, "json");
        UnifiedRunExportService.Export csv = service.export(run, "CSV");
        UnifiedRunExportService.Export html = service.export(run, "html");

        assertThat(json.mediaType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(text(json)).contains(run.id().toString(), "\"testType\" : \"KAFKA\"");
        assertThat(csv.mediaType().toString()).isEqualTo("text/csv");
        assertThat(text(csv)).contains("\"id\",\"testType\"", "\"KAFKA\",\"DISTRIBUTED\"",
                "\"completed, with warning\"");
        assertThat(html.mediaType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(text(html)).contains("Унифицированный отчёт", "events&lt;prod&gt;")
                .doesNotContain("events<prod>");
        assertThat(json.filename()).endsWith(".json");
        assertThat(csv.filename()).endsWith(".csv");
        assertThat(html.filename()).endsWith(".html");
    }

    @Test
    void rejectsUnknownFormat() {
        assertThatThrownBy(() -> service.export(run(), "xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported export format");
    }

    private static String text(UnifiedRunExportService.Export export) {
        return new String(export.body(), StandardCharsets.UTF_8);
    }

    private static UnifiedRunHistoryStore.RunHistoryRow run() {
        Instant started = Instant.parse("2026-11-16T10:00:00Z");
        return new UnifiedRunHistoryStore.RunHistoryRow(
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                TestType.KAFKA, RunScope.DISTRIBUTED, RunStatus.COMPLETED_WITH_ERRORS,
                started.minusSeconds(1), started, started.plusSeconds(7), "nightly",
                "PRODUCE", "events<prod>", "ci", 1000, 0, 1_024_000,
                142.8, 0.139, 4.1, 6.5, 6.8, 1, RunStopReason.NORMAL,
                "completed, with warning", 2, "KAFKA_PRODUCE_ACK", "/api/distributed-tests/1", true);
    }
}
