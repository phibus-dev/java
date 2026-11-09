CREATE TABLE IF NOT EXISTS kafka_m3_run (
    id UUID PRIMARY KEY,
    profile_id UUID REFERENCES kafka_profile(id) ON DELETE SET NULL,
    test_type VARCHAR(64) NOT NULL,
    topic VARCHAR(249),
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    broker_count INTEGER,
    controller_id INTEGER,
    partition_count INTEGER,
    replica_count BIGINT,
    isr_count BIGINT,
    under_replicated_partitions BIGINT,
    offline_partitions BIGINT,
    min_isr_violations BIGINT,
    scaling_json TEXT,
    error_message TEXT,
    initiator VARCHAR(256)
);

CREATE INDEX IF NOT EXISTS ix_kafka_m3_run_started_at ON kafka_m3_run(started_at DESC);
CREATE INDEX IF NOT EXISTS ix_kafka_m3_run_test_type ON kafka_m3_run(test_type);
