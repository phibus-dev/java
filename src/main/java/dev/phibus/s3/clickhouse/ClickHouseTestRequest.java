package dev.phibus.s3.clickhouse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record ClickHouseTestRequest(
        @NotNull UUID profileId,
        String endpoint,
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*", message = "Invalid table name") String table,
        @Pattern(regexp = "INSERT|SELECT|INSERT_SELECT", message = "Unsupported ClickHouse operation") String operation,
        @Min(1) @Max(64) int concurrency,
        @Min(1) @Max(100000) int batchSize,
        @Min(1) long rowCount,
        @Min(0) @Max(86400) long durationSeconds,
        @Min(0) @Max(3600) long warmupSeconds,
        @Min(1) @Max(1048576) int payloadBytes,
        boolean autoCreateTable) {

    public String normalizedTable() {
        return table == null || table.isBlank() ? "evo_snt_perf_load" : table.trim();
    }

    public String normalizedOperation() {
        return operation == null || operation.isBlank() ? "INSERT" : operation.trim().toUpperCase();
    }

    public boolean durationMode() {
        return durationSeconds > 0;
    }
}
