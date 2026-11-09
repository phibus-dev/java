CREATE TABLE IF NOT EXISTS kafka_profile (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
    bootstrap_servers TEXT NOT NULL,
    security_protocol VARCHAR(32) NOT NULL DEFAULT 'PLAINTEXT',
    sasl_mechanism VARCHAR(32),
    username VARCHAR(256),
    credentials_source VARCHAR(32) NOT NULL DEFAULT 'VAULT',
    vault_secret_path TEXT,
    password_field VARCHAR(128) NOT NULL DEFAULT 'password',
    password_env VARCHAR(128),
    ca_certificate_path TEXT,
    default_topic VARCHAR(249),
    client_id_prefix VARCHAR(128) NOT NULL DEFAULT 'evo-snt',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_kafka_profile_default
    ON kafka_profile(is_default) WHERE is_default = TRUE;

CREATE TABLE IF NOT EXISTS kafka_test_run (
    id UUID PRIMARY KEY,
    profile_id UUID REFERENCES kafka_profile(id) ON DELETE SET NULL,
    test_type VARCHAR(64) NOT NULL DEFAULT 'KAFKA_PRODUCER',
    topic VARCHAR(249) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    requested_messages BIGINT,
    sent_messages BIGINT NOT NULL DEFAULT 0,
    sent_bytes BIGINT NOT NULL DEFAULT 0,
    producer_threads INTEGER NOT NULL DEFAULT 1,
    message_size_bytes INTEGER NOT NULL,
    throughput_messages_sec DOUBLE PRECISION,
    throughput_mib_sec DOUBLE PRECISION,
    latency_avg_ms DOUBLE PRECISION,
    latency_p95_ms DOUBLE PRECISION,
    latency_p99_ms DOUBLE PRECISION,
    latency_max_ms DOUBLE PRECISION,
    error_count BIGINT NOT NULL DEFAULT 0,
    retry_count BIGINT NOT NULL DEFAULT 0,
    error_message TEXT,
    config_json TEXT,
    initiator VARCHAR(256)
);

CREATE INDEX IF NOT EXISTS ix_kafka_test_run_started_at ON kafka_test_run(started_at DESC);
CREATE INDEX IF NOT EXISTS ix_kafka_test_run_status ON kafka_test_run(status);
