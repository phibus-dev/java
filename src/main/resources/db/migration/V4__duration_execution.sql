alter table test_run add column if not exists execution_mode varchar(32) not null default 'OBJECT_COUNT';
alter table test_run add column if not exists configured_duration_seconds bigint not null default 0;
alter table test_run add column if not exists warmup_seconds bigint not null default 0;
alter table test_run add column if not exists stop_reason varchar(32) not null default 'NORMAL';
create index if not exists idx_test_run_execution_mode on test_run(execution_mode);
