CREATE TABLE IF NOT EXISTS clickhouse_replicated_scenario_run (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL,
    scenario VARCHAR(64) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    source_endpoint TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    rows_written BIGINT NOT NULL DEFAULT 0,
    insert_rows_per_second DOUBLE PRECISION NOT NULL DEFAULT 0,
    insert_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    replication_catchup_ms BIGINT NOT NULL DEFAULT 0,
    max_replication_delay_seconds BIGINT NOT NULL DEFAULT 0,
    max_replication_queue BIGINT NOT NULL DEFAULT 0,
    max_log_lag BIGINT NOT NULL DEFAULT 0,
    consistency_passed BOOLEAN,
    replica_count INTEGER NOT NULL DEFAULT 0,
    message TEXT
);

CREATE INDEX IF NOT EXISTS idx_ch_repl_scenario_profile_created
    ON clickhouse_replicated_scenario_run(profile_id, created_at DESC);
