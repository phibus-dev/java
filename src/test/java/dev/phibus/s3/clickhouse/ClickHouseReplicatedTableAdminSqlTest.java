package dev.phibus.s3.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClickHouseReplicatedTableAdminSqlTest {
    @Test
    void dropsReplicatedTableSynchronouslyBeforeRecreatingIt() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/phibus/s3/clickhouse/ClickHouseReplicatedTableAdminService.java"));

        assertThat(source).contains("DROP TABLE IF EXISTS \" + table + \" SYNC");
    }

    @Test
    void offersShardMacroInDefaultKeeperPath() throws IOException {
        String template = Files.readString(Path.of(
                "src/main/resources/templates/clickhouse-failover-tests.html"));

        assertThat(template)
                .contains("Keeper path (поддерживает {shard} и {replica})")
                .contains("value=\"/clickhouse/tables/{shard}/evo_snt_perf_replica\"")
                .contains("id=\"replicaMacro\" value=\"{replica}\"");
    }
}
