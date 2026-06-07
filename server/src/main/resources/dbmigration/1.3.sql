-- Switch the M1 (1-minute) rollup tables to UNLOGGED.
--
-- Trade-off (Option A, accepted): on any Postgres crash or failover the
-- M1 tables are truncated, wiping up to ~30 days of /v1 stats. M10/M60/D1
-- (logged) and the raw timed_entry/gauge_entry tables remain durable.
-- /v1 top/stats endpoints will return empty until M1 backfills again.
--
-- Postgres 16+: setting an unlogged parent partitioned table causes new
-- partitions to inherit UNLOGGED. Existing children still need ALTER.
do $$
declare
  rec record;
begin
  for rec in
    select inhrelid::regclass::text as child
    from pg_inherits
    where inhparent in (
      'ebean_insight.timed_m1'::regclass,
      'ebean_insight.gauge_m1'::regclass
    )
  loop
    execute format('alter table %s set unlogged', rec.child);
  end loop;
end;
$$;

alter table ebean_insight.timed_m1 set unlogged;
alter table ebean_insight.gauge_m1 set unlogged;
