-- apply alter tables
alter table ebean_insight.app add column if not exists config jsonb;
