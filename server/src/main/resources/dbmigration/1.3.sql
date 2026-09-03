-- foreign keys and indices
create index if not exists ix_timed_m1_app_id on ebean_insight.timed_m1 (app_id);
create index if not exists ix_timed_m1_env_id on ebean_insight.timed_m1 (env_id);
create index if not exists ix_timed_m1_metric_id on ebean_insight.timed_m1 (metric_id);
