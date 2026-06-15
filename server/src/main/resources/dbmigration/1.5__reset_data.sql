-- apply changes
--
-- One-shot data reset for the v1 -> v2 metric-model transition.
--
-- Background: a metric's identity is its hash (orm queries supply it from the
-- SQL, independent of the metric name). On ingest an existing app_metric row is
-- matched by hash and reused as-is; its name/tags are never refreshed. So when a
-- v2 client begins reporting an already-seen hash, the server keeps the stale v1
-- name (e.g. orm.DMessage.findMessages) instead of the v2 family name
-- (ebean.query + label tag). The app_metric catalog has no retention, so those
-- stale rows never age out on their own.
--
-- This migration empties all metric data and the catalog so the next ingest
-- recreates rows with their current (v2) name/tags. It deliberately does NOT
-- drop the schema: the Ebean db_migration history table lives in this schema,
-- and dropping it would break the migration run and leave the tables un-rebuilt.
-- Truncating keeps the schema, partitions and migration history intact while
-- clearing every row.
--
-- PRECONDITION: only deploy once the forwarding apps emit v2 for ALL queries
-- (insight top --by name shows only ebean.txn / ebean.query, no orm.* rows).
-- Otherwise v1 forwarders immediately refill the old-semantic rows.
--
-- Runs exactly once per database via migration.run (dev/test/na/apac), so no
-- kubectl/DB access is required and there is nothing to remove afterwards.
-- Truncating a partitioned parent truncates all of its partitions; cascade
-- handles foreign-key order; restart identity resets the id sequences.
-- The job control table is intentionally left alone (managed by DJob.initRollup).

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
