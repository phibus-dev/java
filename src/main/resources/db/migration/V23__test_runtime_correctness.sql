ALTER TABLE part_result ADD COLUMN IF NOT EXISTS sequence_no BIGINT;

WITH ranked AS (
    SELECT ctid,
           ROW_NUMBER() OVER (PARTITION BY test_run_id ORDER BY object_number, part_number) - 1 AS value
      FROM part_result
)
UPDATE part_result AS target
   SET sequence_no = ranked.value
  FROM ranked
 WHERE target.ctid = ranked.ctid
   AND target.sequence_no IS NULL;

ALTER TABLE part_result ALTER COLUMN sequence_no SET NOT NULL;
ALTER TABLE part_result DROP CONSTRAINT IF EXISTS part_result_pkey;
ALTER TABLE part_result ADD CONSTRAINT part_result_pkey PRIMARY KEY (test_run_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_part_result_object_part
    ON part_result(test_run_id, object_number, part_number);

ALTER TABLE test_run ADD COLUMN IF NOT EXISTS workload_profile VARCHAR(80) NOT NULL DEFAULT 'CUSTOM';
ALTER TABLE test_run ADD COLUMN IF NOT EXISTS workload_weights_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE test_run ADD COLUMN IF NOT EXISTS target_operations_per_second INTEGER NOT NULL DEFAULT 0;
ALTER TABLE test_run ADD COLUMN IF NOT EXISTS operation_threads_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE test_run ADD COLUMN IF NOT EXISTS profile_id UUID;

ALTER TABLE test_run ALTER COLUMN completed_parts TYPE BIGINT;
ALTER TABLE test_run ALTER COLUMN total_parts TYPE BIGINT;
ALTER TABLE test_run ALTER COLUMN successful_parts TYPE BIGINT;
ALTER TABLE test_run ALTER COLUMN failed_parts TYPE BIGINT;

ALTER TABLE clickhouse_test_run ADD COLUMN IF NOT EXISTS requested_queries BIGINT NOT NULL DEFAULT 1;

-- Manual credentials must never remain in scheduler JSON. Existing schedules
-- continue to use their profile/environment/Vault configuration after cleanup.
UPDATE test_schedule
   SET test_request_json = (test_request_json::jsonb - 'accessKey' - 'secretKey')::text,
       updated_at = CURRENT_TIMESTAMP
 WHERE test_request_json IS NOT NULL
   AND test_request_json <> '';
