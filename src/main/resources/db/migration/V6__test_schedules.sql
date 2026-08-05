CREATE TABLE IF NOT EXISTS test_schedule (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    cron_expression VARCHAR(120) NOT NULL,
    time_zone VARCHAR(80) NOT NULL DEFAULT 'UTC',
    test_request_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_run_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ,
    last_test_run_id UUID,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_test_schedule_enabled_next
    ON test_schedule(enabled, next_run_at);

ALTER TABLE test_run ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE test_run ADD COLUMN IF NOT EXISTS schedule_id UUID;
CREATE INDEX IF NOT EXISTS idx_test_run_schedule_id ON test_run(schedule_id);
