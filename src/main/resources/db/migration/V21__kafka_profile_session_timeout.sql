ALTER TABLE kafka_profile
    ADD COLUMN IF NOT EXISTS session_timeout_ms INTEGER NOT NULL DEFAULT 10000;

ALTER TABLE kafka_profile
    DROP CONSTRAINT IF EXISTS ck_kafka_profile_session_timeout_ms;

ALTER TABLE kafka_profile
    ADD CONSTRAINT ck_kafka_profile_session_timeout_ms
        CHECK (session_timeout_ms BETWEEN 1000 AND 300000);
