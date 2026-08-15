package dev.phibus.s3.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ClickHouseJdbcTimeTest {
    @Test
    void bindsInstantAsExplicitPostgreSqlTimestampWithTimeZone() {
        Instant instant = Instant.parse("2026-08-15T16:10:58.880Z");

        var parameter = ClickHouseJdbcTime.timestamptz(instant);

        assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(parameter.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-15T16:10:58.880Z"));
    }

    @Test
    void preservesExplicitSqlTypeForNullTimestamps() {
        var parameter = ClickHouseJdbcTime.timestamptz(null);

        assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(parameter.getValue()).isNull();
    }
}
