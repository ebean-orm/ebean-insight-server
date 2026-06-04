# Verify Forwarder against local Alloy + Mimir + Grafana

End-to-end smoke test for `forward.otel.*`. Spins up:

| Service  | Port  | Purpose                                                      |
|----------|-------|--------------------------------------------------------------|
| Alloy    | 4318  | OTLP HTTP receiver — where ebean-insight-server forwards     |
| Alloy UI | 12345 | Live pipeline view                                           |
| Mimir    | 9009  | Prometheus-compatible TSDB (single-binary, OTLP enabled)     |
| Grafana  | 3000  | UI to query metrics (anonymous Admin, Mimir datasource auto) |

Pipeline: `ebean-insight-server` → Alloy `otelcol.receiver.otlp` → `deltatocumulative` → `batch` → `otelcol.exporter.otlphttp` → Mimir `/otlp` → query via Grafana.

## 1. Start the stack

```bash
cd verify
docker compose up -d
docker compose logs -f alloy   # optional, to watch incoming requests
```

Wait ~10 s, then sanity check:

```bash
curl -fsS http://localhost:9009/ready                  # Mimir ready
curl -fsS http://localhost:12345/-/ready               # Alloy ready
open http://localhost:3000                             # Grafana (anonymous Admin)
```

## 2. Run ebean-insight-server with forwarding enabled

From the repo root, set `forward.otel.*` config and run. Easiest is via env vars
(avaje-config maps `forward.otel.enabled` → `FORWARD_OTEL_ENABLED`):

```bash
export FORWARD_OTEL_ENABLED=true
export FORWARD_OTEL_ENDPOINT=http://localhost:4318
export FORWARD_OTEL_NAMESPACE=consolidation
mvn -q -DskipTests spring-boot:run    # or however the server is normally run locally
```

(or just edit `src/main/resources/application.yaml` for the run.)

## 3. Send a sample ingest

```bash
./send-sample.sh         # one POST
./send-sample.sh 6       # six POSTs, 5 s apart (gives deltatocumulative time to build a series)
```

The script POSTs `sample-metric-request.json` to
`http://localhost:8091/api/ingest/metrics`, patching `eventTime` to the current
millis each time. Override the URL with `INSIGHT_URL=...`.

## 4. Verify in Grafana

Open <http://localhost:3000/explore>. With the Mimir datasource selected,
try these queries:

```promql
# All metrics for our verify app — confirms resource attributes carried through
{service_name="verify-app"}

# Counter from the timed metric (deltatocumulative converted -> _total)
app_requests_count_total{service_name="verify-app"}

# Gauge passthrough
jvm_memory_used{service_name="verify-app"}

# DB metric — db name carried as attribute
txn_main_count_total{db="db"}
```

You should also see resource labels: `service_namespace="consolidation"`,
`business_domain="ingestion"`, `deployment_environment_name="local"`, etc.
(Prom-style label names use underscores; OTLP attribute keys with dots become
underscores in Mimir.)

## 5. Inspect Alloy live

<http://localhost:12345> — see receivers, processors, exporters, with live
counters for accepted/refused metrics. If Alloy shows `refused_metric_points`,
check its container logs for the rejection reason.

## 6. Tear down

```bash
docker compose down -v
```

## Notes

- **Delta vs cumulative**: forwarder emits DELTA. Alloy's
  `otelcol.processor.deltatocumulative` converts to cumulative for Mimir. If we
  later decide to emit cumulative directly from the forwarder, drop that
  processor and the result should be identical in Grafana.
- **Reserved keys**: `service.name`, `service.version`, `service.instance.id`,
  `deployment.environment{,.name}` come from the dedicated MetricRequest fields,
  not from `resAttrs`. The sample request still puts `service.namespace` in
  `resAttrs` (allowed) and overrides `forward.otel.namespace`.
- **Single tenant**: Mimir runs with `multitenancy_enabled: false`, so no
  `X-Scope-OrgID` header is needed from Alloy or Grafana.
