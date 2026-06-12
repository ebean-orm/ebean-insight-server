# Playbook: from a slow-query trace to a root cause

You are looking at a distributed trace (Grafana Cloud, Datadog, Tempo, …) and
one span is a slow database query. The span carries a **query hash** tag emitted
by ebean-insight. This guide walks the hash through the `/v1` API to a root
cause, showing the **CLI**, the **MCP tool**, and the **raw HTTP** call at each
step.

It is written around the trace scenario, but the drill-down (steps 2–5) is the
same playbook whatever raised the signal — a `top` ranking, a `missing-plans`
gap, or a plan-change alert all converge here.

## 0. What you need from the trace

The metric endpoints are **app-scoped**, so a hash on its own is not enough.
Lift three things from the span / resource tags:

| You need | Typical span/resource tag |
|----------|---------------------------|
| `app`    | `service.name` / your deployment's app name |
| `hash`   | the ebean-insight query-hash tag on the span |
| `env`    | `deployment.environment` |

`env` is optional but strongly recommended: without it you aggregate across all
environments, which usually is not what you want when chasing a production
regression.

In the examples below, `$BASE` is the server base URL (e.g. via the
[`forwarder`](../forwarder/README.md) or a port-forward) and `$APP`, `$HASH`,
`$ENV` are the values from the trace.

> **Interactive shortcut (CLI):** `insight metric $APP $HASH -i` (add
> `--env $ENV`) opens a guided drill-down for the hash — pick **s**ql, **p**lan,
> **c**apture, **t**rend, or **h**istory (plan-change diff) without retyping the
> hash. This single session covers steps 1–5 below.

## 1. Identify the query — *what is this hash?*

Confirm the hash, see its SQL, its ebean `label`, and whether it is
plan-capable.

```bash
# CLI
insight metric $APP $HASH

# raw HTTP
curl "$BASE/v1/apps/$APP/metrics/by-hash/$HASH"
```

```text
# MCP tool
metric(app=$APP, hash=$HASH)
```

Returns the SQL, the location/label (e.g. `orm.Customer.custMain.contacts.lazy`),
and `planCapable`. An empty result means the hash is unknown for that app —
double-check you took `app` and `hash` from the same span.

## 2. Quantify it — *slow always, or slow lately?*

Get count / total / mean / max over a window, and the per-bucket trend.

```bash
# CLI — trend chart (top chart selectable: --by total|mean|max|count)
insight trend $APP $HASH --env $ENV --since-minutes 180 --by mean

# raw HTTP — aggregated stats over the window
curl "$BASE/v1/apps/$APP/metrics/by-hash/$HASH/stats?sinceMinutes=60&env=$ENV"
# raw HTTP — per-bucket time-series
curl "$BASE/v1/apps/$APP/metrics/by-hash/$HASH/timeseries?sinceMinutes=180&env=$ENV"
```

```text
# MCP tools
stats(app=$APP, hash=$HASH, sinceMinutes=60, env=$ENV)
trend(app=$APP, hash=$HASH, sinceMinutes=180, env=$ENV)
```

Use this to decide whether you are chasing a steady cost (always slow → likely a
plan/indexing problem, go to step 3) or a recent regression (got slow at a point
in time → also check step 5 for a plan-shape change).

> Note: the CLI has no dedicated `stats` command — `insight trend` covers the
> over-time view and `insight top` the ranking; the aggregated single-window
> `stats` figure is currently surfaced via the API and the MCP `stats` tool.

## 3. Look at the actual plan — *the EXPLAIN*

Find recent captured plans for this hash, then open one for the full SQL, bind
values, and plan text.

```bash
# CLI
insight plans --app $APP --hash $HASH --env $ENV -n 5
insight plan <planId>            # add --raw for just the EXPLAIN text

# raw HTTP
curl "$BASE/v1/plans?app=$APP&hash=$HASH&env=$ENV&limit=5"
curl "$BASE/v1/plans/<planId>"
```

```text
# MCP tools
plans(app=$APP, hash=$HASH, env=$ENV, limit=5)
plan(id=<planId>)
```

This is where the root cause usually shows up: a sequential scan where you
expected an index, a bad join order, or a row-estimate that is wildly off.

## 4. No recent plan? — *request a capture*

If step 3 returns nothing but the metric is `planCapable`, ask the app to
capture an EXPLAIN on its next execution, then poll until it lands.

```bash
# CLI
insight capture $APP $HASH --env $ENV
insight pending --app $APP --env $ENV
# then re-run the step 3 commands

# raw HTTP
curl -X POST "$BASE/v1/apps/$APP/plans/by-hash/$HASH/request?env=$ENV"
curl "$BASE/v1/plans/pending"
```

```text
# MCP tools
capture(app=$APP, hash=$HASH, env=$ENV)
pending(app=$APP, env=$ENV)
```

Capture is best-effort and asynchronous — it EXPLAINs the *next* execution of
that query, so a plan appears once the app runs it again. `missing-plans` lists
plan-capable metrics that have no recent capture if you want to find these
proactively.

## 5. Regression check — *did the plan shape change?*

If the query got slow at a point in time, look for a recorded plan-shape change
(e.g. index scan → seq scan after stats drift or a schema change).

```bash
# CLI
insight changes --app $APP --hash $HASH
insight change <id>              # full from/to plan diff

# raw HTTP
curl "$BASE/v1/plan-changes?app=$APP&hash=$HASH"
curl "$BASE/v1/plan-changes/<id>"
```

```text
# MCP tools
changes(app=$APP, hash=$HASH)
change(id=<id>)
```

A `CHANGED` event with a before/after plan is the smoking gun for a regression;
a `FIRST` event just means this is the first plan captured for that hash.

## TL;DR call sequence

```
trace → {app, hash, env}
  → metric        (what is it)
  → stats / trend (how bad, steady or recent)
  → plans → plan  (the EXPLAIN, root cause)
  → [capture → pending] if no plan yet
  → changes → change   if it regressed
```

The realistic first two calls are **`metric`** then **`stats`/`trend`**; most
investigations end at **`plan`**.

## Related

- [Playbook: Top-N triage](playbook-topn-triage.md) — find the worst queries when you have no specific lead
- [Playbook: missing-plans backfill](playbook-missing-plans-backfill.md) — capture EXPLAINs ahead of time
- [Playbook: plan-change alert](playbook-plan-change-alert.md) — respond to a plan regression
- API spec: [`api/src/main/openapi/v1.yaml`](../api/src/main/openapi/v1.yaml)
- CLI usage: [`cli/README.md`](../cli/README.md)
- MCP tools / clients: [`docs/install-mcp.md`](install-mcp.md) ·
  [`docs/connect-mcp-clients.md`](connect-mcp-clients.md)
