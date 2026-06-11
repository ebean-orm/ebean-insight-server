# ebean-insight-server

- Collects metrics from applications using Ebean ORM that send it their metrics.
- Performs a rollup of metrics on 1 min, 10 min, 1 hour basis.
- Supports ability to request Query plans and collect query plans
- For Postgres query plans, uses pev2 to view query plan details
- Can also forward ingested metrics to any OTLP/HTTP collector
  (Grafana Alloy, OpenTelemetry Collector, Grafana Cloud, etc.)
- Exposes a versioned, agent-friendly REST API at `/v1` (contract-first via
  OpenAPI; see [`api/src/main/openapi/v1.yaml`](api/src/main/openapi/v1.yaml)).

## Install

- **Server** — Kubernetes, Docker, or standalone native binary:
  [`docs/install-server.md`](docs/install-server.md)
- **CLI** (`insight`) — per-OS binaries for macOS, Linux, Windows:
  [`docs/install-cli.md`](docs/install-cli.md) · non-interactive recipe for AI
  agents: [`docs/install-cli-agents.md`](docs/install-cli-agents.md)
- **MCP server** — expose the `/v1` API to AI agents over the Model Context
  Protocol: [`docs/install-mcp.md`](docs/install-mcp.md) ·
  [connecting clients](docs/connect-mcp-clients.md)

For the server's full configuration / mode reference see
[`docs/deployment-modes.md`](docs/deployment-modes.md). To require OAuth2 JWT
bearer auth on the server's endpoints see [`docs/auth.md`](docs/auth.md). For
day-to-day CLI usage see [`cli/README.md`](cli/README.md).

## Modules

| Module | Purpose |
|--------|---------|
| `api`  | OpenAPI spec (`v1.yaml`) and generated `/v1` API interfaces + DTOs (record types). |
| `client` | Generated typed HTTP client (`*HttpClient`) for the `/v1` API. |
| `server` | The running service: ingest endpoints, rollups, UI, and `/v1` controllers. |
| `forwarder` | Library that maintains a stable local endpoint to the server via a supervised `kubectl port-forward`. See [`forwarder/README.md`](forwarder/README.md). |
| `cli` | `insight` command line tool over the `/v1` API; compiles to a native binary. See [`cli/README.md`](cli/README.md). |
| `mcp` | Model Context Protocol server exposing the `/v1` API to AI agents; compiles to a native binary. See [`docs/install-mcp.md`](docs/install-mcp.md). |

## /v1 API (agent / CLI / tooling)

The `/v1` API uses **natural keys** in the path (app name, env name) instead
of internal numeric ids — making it suitable for CLIs, agents, and ad-hoc
tooling. The OpenAPI spec is the source of truth:

- Spec: [`api/src/main/openapi/v1.yaml`](api/src/main/openapi/v1.yaml)

All endpoints expect the `Insight-Key` header (same auth as `/api`).
Time windows accept either `sinceMinutes` or `sinceHours` (not both → 400).

### Example recipes

Discover apps + envs:
```shell
curl -H "Insight-Key: $KEY" http://localhost:8090/v1/apps
curl -H "Insight-Key: $KEY" http://localhost:8090/v1/envs
curl -H "Insight-Key: $KEY" http://localhost:8090/v1/apps/myapp
```

Top-N most expensive metrics for an app (last hour):
```shell
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/metrics/top?orderBy=total&sinceMinutes=60&limit=20"
```

Top-N across all apps (last 24h, plan-capable only):
```shell
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/metrics/top?sinceHours=24&planCapable=true&limit=50"
```

Find ORM metrics lacking a recent plan capture, ranked by execution cost
(omit the `/apps/myapp` segment to rank across all apps):
```shell
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/metrics/missing-plans?by=total&sinceHours=24&limit=50"
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/metrics/missing-plans?by=total&limit=50"
```

Trace → plan: take a hash from a span attribute (`ebean.query_hash`) and
look up the matching metric and any captured plans, optionally requesting
a fresh capture:
```shell
HASH=8a519a4c120289bd505a4a79c27f2895
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/metrics/by-hash/$HASH"
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/plans/by-hash/$HASH"
curl -X POST -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/plans/by-hash/$HASH/request?env=prod"
```

Time-series for one hash (mean is derived client-side from the additive
`count`/`total`; bucket resolution is chosen automatically from the window):
```shell
curl -H "Insight-Key: $KEY" \
  "http://localhost:8090/v1/apps/myapp/metrics/by-hash/$HASH/timeseries?sinceHours=6"
```

Fetch a specific plan (full SQL + plan + bind values) by id:
```shell
curl -H "Insight-Key: $KEY" http://localhost:8090/v1/plans/12345
```

List in-flight capture requests — requested but not yet collected (tracked
durably; survives forwarder polls and server restarts; a request whose query
never executes ages out after ~15 minutes):
```shell
curl -H "Insight-Key: $KEY" "http://localhost:8090/v1/plans/pending"
curl -H "Insight-Key: $KEY" "http://localhost:8090/v1/plans/pending?app=myapp&env=test"
```

### Conventions

- **Natural keys** — `{app}` and `?env=` are names, not ids. Unknown
  natural keys return `200` with an empty list (not `404`) — except
  `GET /v1/apps/{app}` and `POST .../plans/by-hash/{hash}/request` which
  return `404` when the app doesn't exist.
- **planCapable** — derived from the metric name (`orm.`, `dto.`, or
  `sql.query.`, excluding `orm.update.`) and stored on
  `app_metric.plan_capable`. Only plan-capable metrics support
  `POST .../plans/by-hash/{hash}/request`; other metrics return `400`.
- **orderBy** — allowlist on `/metrics/top`: `total` (default), `mean`,
  `max`, `count`. Anything else → `400`.
- **Hash vs label** — `hash` is the metric `key` (deterministic SQL hash;
  ORM-only), `label` is the human metric name (e.g. `orm.OrderDao.find`).

### CLI

For interactive / scripted use there is an `insight` CLI ([`cli/README.md`](cli/README.md))
that wraps these endpoints. It can reach the server via a static `--url`, or — by
default — a supervised `kubectl port-forward` that reuses your cluster RBAC as
auth (no `Insight-Key` needed), optionally held open by a background
`insight forward` daemon:

```shell
insight forward            # optional: hold one supervised tunnel open
insight apps
insight plans -n 5
insight plan 12345 --raw
```

## Deployment modes

| Mode | What it does | Postgres |
|------|--------------|----------|
| **Persist** (default) | Stores metrics + query plans in Postgres, runs rollups, serves UI. | required |
| **Forward-only** | Pure smart-proxy — forwards each ingest via OTLP, optionally logs query plans. | not required |

Set both `METRICS_STORE_ENABLED=false` and `PLANS_STORE_ENABLED=false` to
enable forward-only mode. Either mode can additionally forward via
`FORWARD_OTEL_ENABLED=true` + `FORWARD_OTEL_ENDPOINT=http://...`.

See [`docs/deployment-modes.md`](docs/deployment-modes.md) for full
configuration, env-var reference, Docker examples and expected startup logs.

#### Future TODOs:
- Support reporting aggregated metrics onto Graphite, StatsD, etc
- Provide automation for automatically collecting query plans for:
  - new queries,
  - queries that exceed a threshold (anomalies)


## Building local native image
Requires GraalVM installed
```shell
sdk install java 24-graal

sdk use java 24-graal
```

Build on a Mac (no G1GC supported)
```shell
mvn clean package -P native,mac -DskipTests
```
Build on a Linux (with G1GC)
```shell
mvn clean package -P native,linux -DskipTests
```

## Run the application locally

#### Step 1
- Requires docker to be installed locally
- Run the main method on src/test/java/main/StartPostgresDocker

#### Step 2
Run the native application. We pass it an external configuration file via `-Dprops.file=`.
```shell
./target/ebean-insight -Dprops.file=application.yaml
```
