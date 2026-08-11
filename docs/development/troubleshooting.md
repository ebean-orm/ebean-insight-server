# Development troubleshooting

## The server will not start

First build the reactor:

```shell
mvn -q -pl server -am install -DskipTests
```

This resolves local `api` and `client` artifacts matching the current parent
version. If startup still fails, inspect the first configuration or database
connection error rather than the later shutdown messages.

## Port 8091 is already in use

Inspect the listener:

```shell
lsof -nP -iTCP:8091 -sTCP:LISTEN
```

Stop only the reported server PID with `kill <PID>`, then start the server
again. Do not use broad process-name termination commands.

## The browser shows old CSS or JavaScript

Rebuild and restart the server, then hard-refresh the browser. Confirm the
served file contains the change:

```shell
curl -s http://localhost:8091/static/style.css
curl -s http://localhost:8091/static/query-total.js
```

The files under `src/main/resources` are not a live-reload source for the
already-running process.

## A page is blank or has no chart

Check the page response and inspect the embedded JSON payload:

```shell
curl -s 'http://localhost:8091/ux/top?app=shop-app&range=30m' \
  | grep -E 'chart-data|top-mean-data|top-by-time-data'
```

Then check the browser console for JSON parsing errors, missing Chart.js
assets, or JavaScript exceptions. A chart with no labels intentionally renders
no chart.

## Expected data is missing

Re-run `SeedDemoData` after confirming PostgreSQL configuration. Verify the
selected application, environment, and range match the seeded values. For
metric-detail and query-hash pages, confirm the label/hash belongs to the
selected app and that the hash URL includes all required query parameters.

## A chart shows zeroes where it should have gaps

Inspect the server-produced JSON. An empty bucket should be `null`, not `0`.
Fix the controller/view data shaping first; do not hide the issue in Chart.js.
If nulls disappear during serialization, use a scoped Jsonb serializer for
that chart payload.

## Tests pass but the UX is wrong

The most common cause is validating only Java code. Run the JavaScript syntax
checks, rebuild/restart, and perform the manual checks in
[`testing.md`](testing.md). Pay particular attention to both themes, sparse
data, and navigation state.
