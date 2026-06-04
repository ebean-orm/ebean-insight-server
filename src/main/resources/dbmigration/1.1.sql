-- apply alter tables
alter table ebean_insight.app_metric add column if not exists plan_threshold_micros bigint;
