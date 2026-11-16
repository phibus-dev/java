CREATE TABLE IF NOT EXISTS api_idempotency (
    client_hash CHAR(64) NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    run_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (client_hash, idempotency_key_hash),
    CONSTRAINT uq_api_idempotency_run UNIQUE (run_id)
);

CREATE INDEX IF NOT EXISTS idx_api_idempotency_created_at
    ON api_idempotency(created_at);
