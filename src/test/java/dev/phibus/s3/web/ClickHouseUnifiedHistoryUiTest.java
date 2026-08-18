package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseUnifiedHistoryUiTest {

    @Test
    void commonHistoryContainsLoadAndReplicatedTestsWithTypeAndDetails() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/clickhouse-tests.html"));
        String store = Files.readString(Path.of("src/main/java/dev/phibus/s3/clickhouse/ClickHouseHistoryStore.java"));
        String replicatedDetail = Files.readString(Path.of("src/main/resources/templates/clickhouse-replicated-history-detail.html"));

        assertThat(html)
                .contains("Общая история ClickHouse")
                .contains("Тип теста")
                .contains("Load test")
                .contains("Replicated test")
                .contains("r.detailUrl")
                .contains("r.comparable");
        assertThat(store)
                .contains("'LOAD_TEST' AS test_type")
                .contains("'REPLICATED_TEST' AS test_type")
                .contains("clickhouse_replicated_scenario_run")
                .contains("UNION ALL")
                .contains("/clickhouse/replicated-tests/history/");
        assertThat(replicatedDetail)
                .contains("Результат Replicated test")
                .contains("Replication catch-up")
                .contains("Consistency passed")
                .contains("Ошибки и сообщения выполнения");
    }
}
