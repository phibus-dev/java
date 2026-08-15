CREATE TABLE clickhouse_profile (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
    endpoints TEXT NOT NULL,
    database_name VARCHAR(255) NOT NULL DEFAULT 'default',
    username VARCHAR(255) NOT NULL DEFAULT 'default',
    encrypted_password TEXT,
    connection_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    query_timeout_seconds INTEGER NOT NULL DEFAULT 30,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_clickhouse_profile_connection_timeout CHECK (connection_timeout_ms BETWEEN 100 AND 120000),
    CONSTRAINT ck_clickhouse_profile_query_timeout CHECK (query_timeout_seconds BETWEEN 1 AND 3600)
);

CREATE UNIQUE INDEX ux_clickhouse_profile_default
    ON clickhouse_profile (is_default)
    WHERE is_default = TRUE;
