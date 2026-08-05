create table if not exists test_run (
    id uuid primary key,
    status varchar(32) not null,
    created_at timestamptz not null,
    started_at timestamptz,
    finished_at timestamptz,
    endpoint text,
    bucket text,
    region text,
    object_key text,
    total_bytes bigint not null,
    bytes_transferred bigint not null,
    completed_parts integer not null,
    total_parts integer not null,
    average_speed_mibps double precision not null,
    p50_latency_ms double precision not null,
    p95_latency_ms double precision not null,
    p99_latency_ms double precision not null,
    successful_parts integer not null,
    failed_parts integer not null,
    delete_after_test boolean not null,
    cleanup_successful boolean not null,
    message text
);

create index if not exists idx_test_run_created_at on test_run(created_at desc);
create index if not exists idx_test_run_status on test_run(status);
create index if not exists idx_test_run_endpoint_bucket on test_run(endpoint, bucket);

create table if not exists part_result (
    test_run_id uuid not null references test_run(id) on delete cascade,
    object_number integer not null,
    part_number integer not null,
    bytes bigint not null,
    duration_ms bigint not null,
    speed_mibps double precision not null,
    etag text,
    status varchar(32) not null,
    error_message text,
    primary key (test_run_id, object_number, part_number)
);

create index if not exists idx_part_result_test_run on part_result(test_run_id);
