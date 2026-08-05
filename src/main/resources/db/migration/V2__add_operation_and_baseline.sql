alter table test_run add column if not exists operation varchar(32) not null default 'UPLOAD';
alter table test_run add column if not exists baseline boolean not null default false;
alter table test_run add column if not exists baseline_name varchar(200);
alter table test_run add column if not exists baseline_marked_at timestamptz;

create index if not exists idx_test_run_operation on test_run(operation);
create index if not exists idx_test_run_baseline_lookup on test_run(endpoint, bucket, operation, baseline) where baseline = true;

create unique index if not exists uq_test_run_active_baseline
    on test_run(endpoint, bucket, operation)
    where baseline = true;
