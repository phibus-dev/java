alter table test_run add column if not exists initiator varchar(200) not null default 'local';

update test_run set initiator = 'local' where initiator is null or btrim(initiator) = '';

create index if not exists idx_test_run_initiator_created on test_run(initiator, created_at desc);
