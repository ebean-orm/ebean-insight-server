-- apply changes
create table ebean_insight.user_usage (
  minute_at                     timestamptz not null,
  request_count                 bigint not null,
  total_micros                  bigint not null,
  max_micros                    bigint not null,
  user_id                       varchar(200) not null,
  method                        varchar(10) not null,
  path                          varchar(300) not null
);

-- apply alter tables
alter table ebean_insight.ui_session add column if not exists user_sub varchar;
-- foreign keys and indices
create index if not exists ix_user_usage_minute on ebean_insight.user_usage (minute_at);
create index if not exists ix_user_usage_user_minute on ebean_insight.user_usage (user_id,minute_at);
