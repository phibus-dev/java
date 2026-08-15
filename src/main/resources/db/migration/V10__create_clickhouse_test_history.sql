CREATE TABLE IF NOT EXISTS clickhouse_test_run (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL,
    endpoint TEXT NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    concurrency INTEGER NOT NULL,
    batch_size INTEGER NOT NULL,
    requested_rows BIGINT NOT NULL,
    duration_seconds BIGINT NOT NULL,
    warmup_seconds BIGINT NOT NULL,
    payload_bytes INTEGER NOT NULL,
    auto_create_table BOOLEAN NOT NULL,
    rows_processed BIGINT NOT NULL,
    bytes_processed BIGINT NOT NULL,
    queries BIGINT NOT NULL,
    errors BIGINT NOT NULL,
    rows_per_second DOUBLE PRECISION NOT NULL,
    mib_per_second DOUBLE PRECISION NOT NULL,
    queries_per_second DOUBLE PRECISION NOT NULL,
    p50_latency_ms DOUBLE PRECISION NOT NULL,
    p95_latency_ms DOUBLE PRECISION NOT NULL,
    p99_latency_ms DOUBLE PRECISION NOT NULL,
    message TEXT
);

CREATE INDEX IF NOT EXISTS idx_clickhouse_test_run_created_at
    ON clickhouse_test_run(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_clickhouse_test_run_operation_table
    ON clickhouse_test_run(operation, table_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_clickhouse_test_run_profile
    ON clickhouse_test_run(profile_id, created_at DESC);
