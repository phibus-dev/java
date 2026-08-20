CREATE TABLE IF NOT EXISTS clickhouse_replicated_consistency_detail (
    run_id UUID NOT NULL,
    endpoint TEXT NOT NULL,
    shard_key TEXT NOT NULL,
    replica_name TEXT,
    rows_count BIGINT NOT NULL DEFAULT 0,
    sequence_sum TEXT,
    payload_bytes TEXT,
    consistent BOOLEAN,
    note TEXT,
    PRIMARY KEY (run_id, endpoint),
    CONSTRAINT fk_ch_repl_consistency_run
        FOREIGN KEY (run_id) REFERENCES clickhouse_replicated_scenario_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ch_repl_consistency_run
    ON clickhouse_replicated_consistency_detail(run_id);
