CREATE TABLE IF NOT EXISTS clickhouse_failover_run (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    source_endpoint TEXT,
    status VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    fault_confirmed_at TIMESTAMPTZ,
    recovery_started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    rows_written BIGINT NOT NULL DEFAULT 0,
    failed_operations BIGINT NOT NULL DEFAULT 0,
    max_replication_delay_seconds BIGINT NOT NULL DEFAULT 0,
    max_replication_queue BIGINT NOT NULL DEFAULT 0,
    max_log_lag BIGINT NOT NULL DEFAULT 0,
    service_interruption_ms BIGINT NOT NULL DEFAULT 0,
    recovery_time_ms BIGINT NOT NULL DEFAULT 0,
    consistency_passed BOOLEAN,
    replica_count INTEGER NOT NULL DEFAULT 0,
    message TEXT
);

CREATE INDEX IF NOT EXISTS idx_clickhouse_failover_run_profile_created
    ON clickhouse_failover_run(profile_id, created_at DESC);
