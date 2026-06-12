# Playbook: Plan-change alert — respond to a query-plan regression

**When to use this:** ebean-insight recorded a **plan-shape change** for a query
(e.g. an index scan flipped to a sequential scan after stats drift, a schema
change, or a parameter-sniffing flip), or you simply want to audit recent plan
changes. This playbook triages the change and decides whether it actually hurt.

Change events are one of two types: **`FIRST`** (the first plan ever captured
for that hash — informational) and **`CHANGED`** (the plan differs from the
previously captured one — the one to care about). Each step shows the **CLI**,
the **MCP tool**, and the **raw HTTP** call. `$BASE` is the server base URL;
`$APP`/`$HASH`/`$ENV` are your values.

## 1. List recent plan changes

```bash
# CLI — newest first; filter by app/env/type, or by one query's history (--hash)
insight changes --type CHANGED --since-hours 24 -n 20
insight changes --app $APP --env $ENV --hash $HASH
```

> **Interactive (CLI):** `insight changes --type CHANGED -i` walks the whole
> playbook in one session — pick a change to see its from/to diff (step 2), then
> press **d** to drill into that query (sql/plan/capture/trend), covering step 3.

```text
# MCP tool
changes(changeType=CHANGED, sinceHours=24, limit=20)
changes(app=$APP, env=$ENV, hash=$HASH)
```

```bash
# raw HTTP
curl "$BASE/v1/plan-changes?changeType=CHANGED&sinceHours=24&limit=20"
curl "$BASE/v1/plan-changes?app=$APP&env=$ENV&hash=$HASH"
```

Focus on `CHANGED` events; `FIRST` events just mean a query was captured for the
first time.

## 2. Open the change — the from/to diff

Each change has an id. Open it for the full **before/after** query plans side by
side — this is where you see, for example, the join that lost its index.

```bash
# CLI
insight change <id>
```

```text
# MCP tool
change(id=<id>)
```

```bash
# raw HTTP
curl "$BASE/v1/plan-changes/<id>"
```

## 3. Did it actually hurt? — quantify

A plan change is not automatically bad. Confirm impact with the metric's stats /
trend over a window that straddles the change time (the change event carries the
hash, app, and env to use here).

```bash
# CLI
insight trend $APP $HASH --env $ENV --since-hours 24 --by mean

# raw HTTP — aggregated + per-bucket
curl "$BASE/v1/apps/$APP/metrics/by-hash/$HASH/stats?sinceHours=24&env=$ENV"
curl "$BASE/v1/apps/$APP/metrics/by-hash/$HASH/timeseries?sinceHours=24&env=$ENV"
```

```text
# MCP tools
stats(app=$APP, hash=$HASH, sinceHours=24, env=$ENV)
trend(app=$APP, hash=$HASH, sinceHours=24, env=$ENV)
```

If mean/max time stepped up around the change timestamp, it regressed. To see
the SQL and where it comes from, use `metric` (CLI `insight metric $APP $HASH` /
MCP `metric(app=$APP, hash=$HASH)`).

## TL;DR

```
changes (--type CHANGED)
  → change <id>            (before/after plans)
  → stats / trend          (did it hurt, around the change time?)
  → metric                 (SQL + origin, if you need to fix it)
```

A regressed `CHANGED` event with a visibly worse plan and a step-up in mean time
is your root cause; from there it is a database/indexing/stats fix.

## Related

- [Playbook: slow-query trace → root cause](playbook-slow-query-trace.md)
- [Playbook: Top-N triage](playbook-topn-triage.md)
- [Playbook: missing-plans backfill](playbook-missing-plans-backfill.md)
- API spec: [`api/src/main/openapi/v1.yaml`](../api/src/main/openapi/v1.yaml)
