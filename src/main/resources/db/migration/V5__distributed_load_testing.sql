CREATE TABLE IF NOT EXISTS agent (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    hostname VARCHAR(255),
    address VARCHAR(512),
    version VARCHAR(100),
    cpu_count INTEGER NOT NULL,
    memory_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    tags_json TEXT,
    registered_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS distributed_test (
    id UUID PRIMARY KEY,
    test_run_id UUID,
    status VARCHAR(32) NOT NULL,
    requested_agents INTEGER NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    aggregate_bytes BIGINT NOT NULL DEFAULT 0,
    aggregate_operations BIGINT NOT NULL DEFAULT 0,
    aggregate_errors BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS distributed_test_agent (
    distributed_test_id UUID NOT NULL REFERENCES distributed_test(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    bytes_transferred BIGINT NOT NULL DEFAULT 0,
    operations BIGINT NOT NULL DEFAULT 0,
    errors BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (distributed_test_id, agent_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_last_seen ON agent(last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_distributed_test_created ON distributed_test(created_at DESC);
