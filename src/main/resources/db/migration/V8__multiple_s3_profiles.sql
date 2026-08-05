CREATE TABLE IF NOT EXISTS s3_profile (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    endpoint VARCHAR(500) NOT NULL,
    region VARCHAR(120) NOT NULL DEFAULT 'us-east-1',
    bucket VARCHAR(255),
    path_style_access BOOLEAN NOT NULL DEFAULT TRUE,
    credentials_source VARCHAR(30) NOT NULL DEFAULT 'VAULT',
    vault_secret_path VARCHAR(500),
    access_key_field VARCHAR(120) NOT NULL DEFAULT 'accessKey',
    secret_key_field VARCHAR(120) NOT NULL DEFAULT 'secretKey',
    session_token_field VARCHAR(120) NOT NULL DEFAULT 'sessionToken',
    ca_certificate_path VARCHAR(1000),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_s3_profile_credentials_source
        CHECK (credentials_source IN ('VAULT', 'ENVIRONMENT', 'MANUAL'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_s3_profile_single_default
    ON s3_profile (is_default)
    WHERE is_default = TRUE;

CREATE INDEX IF NOT EXISTS idx_s3_profile_name ON s3_profile(name);
