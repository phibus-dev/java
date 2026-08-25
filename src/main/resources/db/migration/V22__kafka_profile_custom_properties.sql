ALTER TABLE kafka_profile
    ADD COLUMN IF NOT EXISTS custom_properties TEXT NOT NULL DEFAULT '{}';
