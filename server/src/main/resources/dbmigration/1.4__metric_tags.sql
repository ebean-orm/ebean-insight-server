-- apply alter tables
alter table ebean_insight.app_metric add column if not exists tags jsonb;
