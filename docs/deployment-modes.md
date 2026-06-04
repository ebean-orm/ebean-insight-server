# Deployment modes

`ebean-insight-server` supports two deployment modes selected at startup via
configuration. All keys can be supplied in `application.yaml`, as JVM system
properties, or as environment variables (the env-var form is shown in
parentheses).

| Mode | `metrics.store.enabled` | `plans.store.enabled` | Postgres |
|------|-------------------------|-----------------------|----------|
| **Persist (default)** | `true` | `true` | required |
| **Forward-only** | `false` | `false` | not required |

Mixed modes (one flag true, the other false) are also supported and behave as
expected — for example `metrics.store.enabled=false plans.store.enabled=true`
forwards metrics via OTLP and persists plans to Postgres.

---

## Persist mode (default)

The server stores incoming metrics and captured query plans in Postgres,
performs 1-min / 10-min / 1-hour / 1-day rollups, and serves the query-plan
viewer UI.

### Required configuration

```yaml
datasource:
  db:
    username: ebean_insight
    password: ebean_insight
    url: jdbc:postgresql://${db_url:localhost:9432}/ebean_insight
```

Or via env vars:
```
DB_URL=postgres-host:5432
DB_USER=ebean_insight
DB_PASS=ebean_insight
```

### What happens at startup

- `DataSource [db]` connects.
- `io.ebean.migration` runs schema migrations (idempotent).
- `GlobalMetrics` seeds known metric names.
- `RollupService` schedules the rollup job (`rollup.freqSecs=60` by default).
- `OnStart` schedules the daily partition-extend job.

### Optional: also forward to OTLP

Persist mode and forward-only mode are not mutually exclusive. Set
`forward.otel.enabled=true` (`FORWARD_OTEL_ENABLED=true`) to additionally push
each ingested `MetricRequest` to an OTLP/HTTP endpoint while still persisting
to Postgres.

```
FORWARD_OTEL_ENABLED=true
FORWARD_OTEL_ENDPOINT=http://alloy:4318
```

---

## Forward-only mode (smart-proxy / Lambda-friendly)

When both `metrics.store.enabled` and `plans.store.enabled` are `false`, the
server runs as a pure smart-proxy. **No Postgres is required.**

### What changes at startup

- `Database.builder()` is configured with `.offline(true)` and
  `.runMigration(false)` — no JDBC connection is attempted.
- `OnStart` skips partition maintenance and `GlobalMetrics` seeding.
- `RollupService` does not schedule its background job.
- `IngestQueueConsumer` skips DB writes; it only forwards each request via
  the OTLP forwarder and (optionally) emits captured query plans to the
  `org.ebean.monitor.queryplan` SLF4J logger.

### Required configuration

```
METRICS_STORE_ENABLED=false
PLANS_STORE_ENABLED=false
FORWARD_OTEL_ENABLED=true
FORWARD_OTEL_ENDPOINT=http://alloy:4318      # OTLP/HTTP base URL (no /v1/metrics suffix)
```

The forwarder appends the appropriate path (`/v1/metrics`, etc.) to the
`FORWARD_OTEL_ENDPOINT` base URL.

### Optional forwarder tuning

| Key | Env var | Default | Notes |
|-----|---------|---------|-------|
| `forward.otel.namespace` | `FORWARD_OTEL_NAMESPACE` | _(empty)_ | Prefix added to forwarded metric names |
| `forward.otel.queueSize` | `FORWARD_OTEL_QUEUESIZE` | `1024` | In-memory queue capacity; overflow increments `dropped` |
| `forward.otel.timeoutSeconds` | `FORWARD_OTEL_TIMEOUTSECONDS` | `30` | OTLP HTTP request timeout |
| `forward.otel.connectTimeoutSeconds` | `FORWARD_OTEL_CONNECTTIMEOUTSECONDS` | `10` | OTLP HTTP connect timeout |
| `forward.otel.pollMillis` | `FORWARD_OTEL_POLLMILLIS` | `200` | Drain-loop poll interval |
| `forward.otel.headers.Authorization` | `FORWARD_OTEL_HEADERS_AUTHORIZATION` | _(none)_ | Bearer / API-key headers for managed collectors (Grafana Cloud, etc.) |

### Optional query-plan logging

Even in forward-only mode the server can request query-plan captures from
clients (via the existing back-channel) and log each captured plan to a
dedicated SLF4J logger. Useful when you want plans searchable in your log
aggregator without running Postgres.

```yaml
autoplan:
  enabled: true
  defaultThresholdMicros: 100000   # request a plan when an SQL/ORM mean > 100ms
  cooldownMinutes: 180             # don't re-request the same hash within 3h
  logPlans: true                   # emit plans to logger org.ebean.monitor.queryplan
  logPlans.includeBind: false      # set true to include bind values (PII caution)
```

---

## Docker

Image: `docker.io/rbygrave/ebean-insight:<version>` (linux/amd64 native image).

### Forward-only

```shell
docker run --rm -p 8091:8091 \
  -e METRICS_STORE_ENABLED=false \
  -e PLANS_STORE_ENABLED=false \
  -e FORWARD_OTEL_ENABLED=true \
  -e FORWARD_OTEL_ENDPOINT=http://alloy:4318 \
  rbygrave/ebean-insight:1.1-RC4
```

### Persist (with optional OTLP fan-out)

```shell
docker run --rm -p 8091:8091 \
  -e db_url=postgres-host:5432 \
  -e db_user=ebean_insight \
  -e db_pass=ebean_insight \
  -e FORWARD_OTEL_ENABLED=true \
  -e FORWARD_OTEL_ENDPOINT=http://alloy:4318 \
  rbygrave/ebean-insight:1.1-RC4
```

---

## Verifying

### Healthy startup logs (forward-only)

```
io.avaje.config           Loaded properties from [resource:application.yaml]
org.ebean.monitor.config.OnStart            forward-only mode - skipping DB partition maintenance and data init
org.ebean.monitor.rollup.RollupService      forward-only mode - rollup service disabled
org.ebean.monitor.forward.MetricForwarder   OTLP forwarder enabled, endpoint=http://alloy:4318/v1/metrics queueSize=1024
org.ebean.monitor.ingest.IngestQueueConsumer  metrics storage disabled (metrics.store.enabled=false) - running in forward-only mode
io.avaje.jex              Avaje Jex started ... on TCP http://...:8091
```

### Healthy startup logs (persist)

```
io.ebean.datasource       DataSource [db] autoCommit[false] [READ_COMMITTED] min[2] max[200] in[185ms]
io.ebean.migration        DB migrations completed in 4ms - totalMigrations:N readResources:2ms
io.ebean.core             Started database[db] platform[POSTGRES] in 291ms
org.ebean.monitor.config.GlobalMetrics      loaded N global metrics
org.ebean.monitor.rollup.RollupService      rollup job owner:... freqSecs:60 expireSecs:90
io.avaje.jex              Avaje Jex started ... on TCP http://...:8091
```

### Forwarder counters

The forwarder logs aggregated counters on shutdown:

```
OTLP forwarder stopped, forwarded=N failed=N dropped=N
```

For the `verify/` smoke-test stack (Alloy → Mimir → Grafana) see
[`verify/README.md`](../verify/README.md).
