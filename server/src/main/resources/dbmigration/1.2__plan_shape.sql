-- apply alter tables
alter table ebean_insight.query_plan add column if not exists plan_shape varchar;
alter table ebean_insight.query_plan add column if not exists plan_shape_hash varchar;
alter table ebean_insight.query_plan add column if not exists plan_shape_algo integer;
