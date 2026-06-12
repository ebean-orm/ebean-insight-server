# Playbook: Missing-plans backfill — capture EXPLAINs for expensive queries

**When to use this:** you want captured query plans (EXPLAINs) for the queries
that matter, but the expensive ones have never been captured — so when an
incident hits there is no plan to look at. This playbook finds plan-capable
metrics with no recent capture, ranked by cost, and requests captures for them.

Each step shows the **CLI**, the **MCP tool**, and the **raw HTTP** call. `$BASE`
is the server base URL; `$APP`/`$HASH`/`$ENV` are your values.

## 1. Find expensive queries with no recent plan

List plan-capable metrics that have no recent capture, ranked by cost. Drop
`--app` to rank across all applications; use `--older-than-hours` to also
re-capture plans that have gone stale.

```bash
# CLI — across all apps, by total cost
insight missing-plans --by total --since-minutes 60 -n 20
# one app, also treat captures older than 24h as missing
insight missing-plans --app $APP --by total --older-than-hours 24 -n 20
```

```text
# MCP tool
missing-plans(orderBy=total, sinceMinutes=60, limit=20)
missing-plans(app=$APP, orderBy=total, olderThanHours=24, limit=20)
```

```bash
# raw HTTP — cross-app
curl "$BASE/v1/metrics/missing-plans?orderBy=total&sinceMinutes=60&limit=20"
# raw HTTP — per-app
curl "$BASE/v1/apps/$APP/metrics/missing-plans?orderBy=total&olderThanHours=24&limit=20"
```

## 2. Request captures (backfill)

The CLI can capture every listed metric in one shot (capped by `-n`); it asks
for confirmation unless you pass `--yes`:

```bash
# CLI — list AND capture the top-N in one command
insight missing-plans --app $APP --by total -n 20 --capture --yes
```

Or capture specific hashes (one or many) directly:

```bash
# CLI
insight capture $APP $HASH1 $HASH2 --env $ENV

# raw HTTP — one POST per hash
curl -X POST "$BASE/v1/apps/$APP/plans/by-hash/$HASH1/request?env=$ENV"
```

```text
# MCP tool — one call per hash
capture(app=$APP, hash=$HASH1, env=$ENV)
```

Capture is **best-effort and asynchronous**: it EXPLAINs the *next* execution of
each query, so plans land once the app runs those queries again.

## 3. Watch the queue, then read the plans

```bash
# CLI
insight pending --app $APP --env $ENV     # in-flight requests not yet collected
insight plans   --app $APP --env $ENV -n 20
insight plan <planId>                     # --raw for just the EXPLAIN text
```

```text
# MCP tools
pending(app=$APP, env=$ENV)
plans(app=$APP, env=$ENV, limit=20)
plan(id=<planId>)
```

```bash
# raw HTTP
curl "$BASE/v1/plans/pending"
curl "$BASE/v1/plans?app=$APP&env=$ENV&limit=20"
curl "$BASE/v1/plans/<planId>"
```

Re-run `missing-plans` afterwards — the backfilled metrics should drop off the
list. Anything still listed is a query the app has not executed again yet.

## TL;DR

```
missing-plans (--by total, --older-than-hours)
  → capture (or `missing-plans --capture --yes`)
  → pending  (wait for the next execution)
  → plans → plan
```

## Related

- [Playbook: slow-query trace → root cause](playbook-slow-query-trace.md)
- [Playbook: Top-N triage](playbook-topn-triage.md)
- API spec: [`api/src/main/openapi/v1.yaml`](../api/src/main/openapi/v1.yaml)
