ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS name VARCHAR(200);
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS test_type VARCHAR(32) NOT NULL DEFAULT 'S3';
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS request_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS primary_endpoint TEXT;
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(64);
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE distributed_test ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS assignment_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS rows_processed BIGINT NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS operations_per_second DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS throughput_mibps DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS p50_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS p95_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS p99_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS latency_histogram TEXT;
ALTER TABLE distributed_test_agent ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_distributed_test_status_updated
    ON distributed_test(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_distributed_test_agent_status_updated
    ON distributed_test_agent(status, updated_at DESC);
