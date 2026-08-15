package dev.phibus.s3.clickhouse;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.SqlParameterValue;

final class ClickHouseJdbcTime {
    private ClickHouseJdbcTime() { }

    static SqlParameterValue timestamptz(Instant value) {
        OffsetDateTime jdbcValue = value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, jdbcValue);
    }
}
