create table if not exists dataset (
  id bigint auto_increment primary key,
  name varchar(200) not null,
  version int not null,
  sha256 varchar(64) not null,
  items int not null,
  created timestamp not null,
  unique (name, version)
);
create table if not exists dataset_item (
  dataset_id bigint not null,
  idx int not null,
  input clob not null,
  reference clob,
  meta clob,
  primary key (dataset_id, idx)
);
create table if not exists target (
  id bigint auto_increment primary key,
  name varchar(200) not null unique,
  url varchar(1000) not null,
  model varchar(200),
  params clob not null,
  created timestamp not null
);
create table if not exists run (
  id bigint auto_increment primary key,
  dataset_id bigint not null,
  target_id bigint not null,
  target_snapshot clob not null,
  metrics varchar(1000) not null,
  concurrency int not null,
  use_cache boolean not null,
  status varchar(20) not null,
  created timestamp not null,
  finished timestamp,
  error clob
);
create table if not exists result (
  run_id bigint not null,
  idx int not null,
  output clob,
  latency_ms double,
  error clob,
  scores clob not null,
  cached boolean not null,
  attempts int not null,
  primary key (run_id, idx)
);
create table if not exists response_cache (
  cache_key varchar(64) primary key,
  output clob not null,
  created timestamp not null
);
-- runs: who executes the items ('local' threads in this service, or 'worker' = leased to external workers)
alter table run add column if not exists executor varchar(10) default 'local' not null;
alter table run add column if not exists judge_snapshot clob;
alter table run add column if not exists github clob;

-- transactional outbox: rows are written in the same transaction as the state change they describe
create table if not exists outbox (
  seq bigint auto_increment primary key,
  event_id varchar(36) not null unique,
  type varchar(50) not null,
  aggregate_id bigint not null,
  payload clob not null,
  created timestamp not null,
  published timestamp,
  attempts int default 0 not null,
  last_error clob
);
create index if not exists outbox_unpublished on outbox(published, seq);
-- consumers record the events they have applied, so a redelivered event has no second effect
create table if not exists processed_event (
  consumer varchar(50) not null,
  event_id varchar(36) not null,
  processed timestamp not null,
  primary key (consumer, event_id)
);
-- idempotency keys for POST /runs
create table if not exists idempotency_key (
  idem_key varchar(200) primary key,
  request_sha256 varchar(64) not null,
  run_id bigint not null,
  created timestamp not null
);
-- tasks leased to external workers; token is the fencing token, incremented on every lease
create table if not exists task (
  run_id bigint not null,
  idx int not null,
  state varchar(10) not null,
  token bigint default 0 not null,
  owner varchar(200),
  lease_expires timestamp,
  leases int default 0 not null,
  primary key (run_id, idx)
);
create index if not exists task_state on task(state);
-- GitHub check runs created for evaluation runs
create table if not exists github_check (
  run_id bigint primary key,
  check_run_id bigint not null
);
create table if not exists run_counter (
  name varchar(50) primary key,
  n bigint not null
);
