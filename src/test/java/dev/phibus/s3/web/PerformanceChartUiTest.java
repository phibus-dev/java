package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PerformanceChartUiTest {

    @Test
    void liveTestPageLoadsDetailedPerformanceChartRenderer() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/index.html"));
        String app = Files.readString(Path.of("src/main/resources/static/app.js"));
        String renderer = Files.readString(Path.of("src/main/resources/static/performance-chart.js"));

        assertThat(html).contains("/performance-chart.js").contains("speed-chart");
        assertThat(app).contains("new EvoPerformanceChart").contains("Текущая скорость").contains("Средняя скорость");
        assertThat(renderer).contains("niceStep").contains("drawTooltip").contains("xTitle").contains("yUnit");
    }

    @Test
    void historyPageUsesScalesGridAndAllLatencyPercentiles() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/history.html"));
        String trends = Files.readString(Path.of("src/main/resources/static/history-trends.js"));

        assertThat(html).contains("/performance-chart.js")
                .contains("Инициатор теста")
                .contains("run.initiator")
                .contains("throughput-chart")
                .contains("ops-chart")
                .contains("latency-chart")
                .contains("errors-chart");
        assertThat(trends).contains("p50LatencyMs").contains("p95LatencyMs").contains("p99LatencyMs")
                .contains("MiB/s").contains("Operations/sec");
    }
}
