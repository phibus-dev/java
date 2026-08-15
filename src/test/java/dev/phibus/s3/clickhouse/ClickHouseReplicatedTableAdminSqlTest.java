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
}
