CREATE TABLE IF NOT EXISTS security_audit_event (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    username VARCHAR(255) NOT NULL,
    action VARCHAR(128) NOT NULL,
    http_method VARCHAR(16),
    request_path TEXT,
    response_status INTEGER,
    remote_address VARCHAR(128),
    duration_ms BIGINT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_security_audit_event_occurred_at
    ON security_audit_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_event_username
    ON security_audit_event (username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_audit_event_action
    ON security_audit_event (action, occurred_at DESC);
