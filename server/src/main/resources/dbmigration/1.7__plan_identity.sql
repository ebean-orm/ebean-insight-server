-- apply alter tables
alter table ebean_insight.query_plan add column if not exists name varchar;
alter table ebean_insight.query_plan add column if not exists kind varchar;
alter table ebean_insight.query_plan add column if not exists type varchar;
alter table ebean_insight.query_plan_change add column if not exists name varchar;
alter table ebean_insight.query_plan_change add column if not exists kind varchar;
alter table ebean_insight.query_plan_change add column if not exists type varchar;

-- wipe existing captured plans so they are re-captured with v2 identity
-- (name/kind/type/label sourced from the matched metric); stale orm.* labels are dropped
truncate table ebean_insight.query_plan_change restart identity cascade;
truncate table ebean_insight.query_plan restart identity cascade;
