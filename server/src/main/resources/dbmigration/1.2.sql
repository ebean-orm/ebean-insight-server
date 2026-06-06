-- apply alter tables
alter table ebean_insight.app_metric add column if not exists plan_capable boolean default false not null;
-- backfill plan_capable for existing rows (orm.* metrics support query plan capture)
update ebean_insight.app_metric set plan_capable = true where name like 'orm.%';

-- foreign keys and indices
create index if not exists ix_app_metric_plan_capable on ebean_insight.app_metric (plan_capable);
