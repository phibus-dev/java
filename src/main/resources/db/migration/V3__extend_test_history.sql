alter table test_run add column if not exists path_style_access boolean not null default true;
alter table test_run add column if not exists object_size_mib bigint not null default 1;
alter table test_run add column if not exists part_size_mib bigint not null default 5;
alter table test_run add column if not exists parallelism integer not null default 1;
alter table test_run add column if not exists object_count integer not null default 1;

create index if not exists idx_test_run_operation_created on test_run(operation, created_at desc);
create index if not exists idx_test_run_endpoint_bucket on test_run(endpoint, bucket);
create index if not exists idx_test_run_object_key_lower on test_run(lower(object_key));
