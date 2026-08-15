CREATE TABLE IF NOT EXISTS clickhouse_replication_snapshot (
    id UUID PRIMARY KEY,
    profile_id UUID NOT NULL REFERENCES clickhouse_profile(id) ON DELETE CASCADE,
    collected_at TIMESTAMPTZ NOT NULL,
    endpoint TEXT NOT NULL,
    database_name TEXT NOT NULL,
    health_status VARCHAR(16) NOT NULL,
    reachable BOOLEAN NOT NULL,
    readonly_replicas BIGINT NOT NULL DEFAULT 0,
    expired_sessions BIGINT NOT NULL DEFAULT 0,
    inactive_replicas BIGINT NOT NULL DEFAULT 0,
    queue_size BIGINT NOT NULL DEFAULT 0,
    max_absolute_delay_seconds BIGINT NOT NULL DEFAULT 0,
    max_log_lag BIGINT NOT NULL DEFAULT 0,
    active_parts BIGINT NOT NULL DEFAULT 0,
    rows_in_active_parts BIGINT NOT NULL DEFAULT 0,
    bytes_on_disk BIGINT NOT NULL DEFAULT 0,
    active_merges BIGINT NOT NULL DEFAULT 0,
    failed_mutations BIGINT NOT NULL DEFAULT 0,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_ch_replication_snapshot_profile_time
    ON clickhouse_replication_snapshot(profile_id, collected_at DESC);
CREATE INDEX IF NOT EXISTS idx_ch_replication_snapshot_endpoint_time
    ON clickhouse_replication_snapshot(endpoint, collected_at DESC);
