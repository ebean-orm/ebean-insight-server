# Playbook: Top-N triage — find the most expensive queries

**When to use this:** you have no specific lead, you just want to know *what is
costing the most* right now (a slow service, a CPU-heavy database, a
pre-release check). This playbook finds the worst offenders, then hands each one
to the [slow-query-trace playbook](playbook-slow-query-trace.md) for root cause.

Each step shows the **CLI**, the **MCP tool**, and the **raw HTTP** call. `$BASE`
is the server base URL; `$APP`/`$ENV` are optional filters.

## 1. Rank the expensive queries

Rank metrics by total time (the usual triage measure — total = mean × calls, so
it surfaces both slow queries and chatty ones). Drop `--app` to rank across all
applications.

```bash
# CLI — across all apps, by total time, last hour
insight top --sort total --since-minutes 60 -n 20
# scope to one app/env, and to plan-capable queries only
insight top --app $APP --env $ENV --sort total --plan-capable -n 20
# group by a different dimension across the three levels (default is label):
#   name = metric families (coarsest), label = one row per label tag (default),
#   hash = individual queries (finest); also any tag key such as type, kind
insight top --by name --sort total -n 20              # coarsest: metric families
insight top --by name --all-apps --sort total -n 20   # collapse a shared name across apps
insight top --by label --sort total -n 20             # middle (default): per label tag
insight top --app $APP --by hash --sort total -n 20   # finest: individual queries
```

By default a metric family (or tag value) reported by several apps shows **one
row per app** (the APP column is populated). Add `--all-apps` (HTTP
`allApps=true`) to roll those into a single cross-app row instead (APP blank).

```text
# MCP tool
top(orderBy=total, sinceMinutes=60, limit=20)
top(app=$APP, env=$ENV, orderBy=total, planCapable=true, limit=20)
```

```bash
# raw HTTP — cross-app
curl "$BASE/v1/metrics/top?orderBy=total&sinceMinutes=60&limit=20"
# raw HTTP — per-app (also supports env, planCapable)
curl "$BASE/v1/apps/$APP/metrics/top?orderBy=total&planCapable=true&limit=20&env=$ENV"
```

Switch `--sort` / `orderBy` between `total`, `mean`, `max`, `count`, `value` to
change the lens (and `--by` to change the grouping dimension):

| Rank by | Surfaces |
|---------|----------|
| `total` | overall load (default) — slow × frequent |
| `mean`  | individually slow queries |
| `max`   | worst-case spikes / outliers |
| `count` | chattiest queries (N+1 candidates) |

Add `--plan-capable` (`planCapable=true`) to ignore queries you cannot get an
EXPLAIN for.

## 2. Pick a hash and drill in

Each row carries the metric **hash**. Take the worst row's hash (and its app /
env) and follow the [slow-query-trace playbook](playbook-slow-query-trace.md)
from **step 1** (`metric`) onward: identify → quantify → plan → (capture) →
change-check.

> **Interactive (CLI):** run `insight top -i` (or `missing-plans -i`) and you
> never leave the session — pick a row, then **s**ql / **p**lan / **c**apture /
> **t**rend / **h**istory (plan-change diff) for it directly.

If many of the top rows are plan-capable but have no captured plan, jump to the
[missing-plans backfill playbook](playbook-missing-plans-backfill.md) to capture
them in bulk first.

## TL;DR

```
top (--by <dimension>, --sort total|mean|max|count|value, --plan-capable)
  → pick worst hash
  → slow-query-trace playbook (metric → stats/trend → plans/plan → …)
```

## Related

- [Playbook: slow-query trace → root cause](playbook-slow-query-trace.md)
- [Playbook: missing-plans backfill](playbook-missing-plans-backfill.md)
- API spec: [`api/src/main/openapi/v1.yaml`](../api/src/main/openapi/v1.yaml)
