package dev.phibus.s3.clickhouse;

public record ClickHouseConnectionSpec(
        String endpoint,
        String database,
        String username,
        String password,
        int connectionTimeoutMs,
        int queryTimeoutSeconds) {
}
