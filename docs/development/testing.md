# Testing and validation

Use targeted validation first, then broaden it when a change crosses module or
runtime boundaries.

## Java tests

Run all server tests with the reactor dependencies:

```shell
mvn -q -pl server -am test
```

Run one focused class:

```shell
mvn -q -pl server -am test -Dtest=UIQueryTotalControllerTest
mvn -q -pl server -am test -Dtest=V1ControllerTest
mvn -q -pl server -am test -Dtest=PlanShapeTest
```

Tests that use database containers or integration behavior may need Docker and
the configured PostgreSQL/test-container environment.

## Build validation

For compile and packaging validation without running tests:

```shell
mvn -q -pl server -am package -DskipTests
```

If reactor artifacts are not available locally, use `install` as described in
[`local-server.md`](local-server.md).

## JavaScript validation

Run syntax checks for changed browser scripts:

```shell
node --check server/src/main/resources/static/dashboard-charts.js
node --check server/src/main/resources/static/query-total.js
node --check server/src/main/resources/static/metric-detail.js
```

Then exercise the affected page in a running local server. Confirm the
rendered HTML contains the expected JSON payload and that links preserve the
selected app, environment, range, label, and hash parameters.

## Manual UX checks

For chart or layout changes, check both light and dark themes and at least one
small and one wide time range. For chart changes also check:

- no-data and sparse-data buckets;
- tooltip values and units;
- legend toggles and hover synchronization;
- navigation from ranking bars, hashes, and plans;
- hard refresh after a server restart.

Do not treat a successful Java build as browser validation; the UX behavior is
split across server JSON shaping and client-side Chart.js code.
