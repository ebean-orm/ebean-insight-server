-- apply changes
--
-- One-shot data reset for old metrics that are missing location and sql
--
truncate table
  ebean_insight.timed_entry,
  ebean_insight.timed_m1,
  ebean_insight.timed_m10,
  ebean_insight.timed_m60,
  ebean_insight.timed_d1,
  ebean_insight.gauge_entry,
  ebean_insight.gauge_m1,
  ebean_insight.gauge_m10,
  ebean_insight.gauge_m60,
  ebean_insight.gauge_d1,
  ebean_insight.rollup_job,
  ebean_insight.query_plan_change,
  ebean_insight.query_plan,
  ebean_insight.capture_request,
  ebean_insight.app_metric,
  ebean_insight.app_pod,
  ebean_insight.app_db,
  ebean_insight.app,
  ebean_insight.env
  restart identity cascade;
