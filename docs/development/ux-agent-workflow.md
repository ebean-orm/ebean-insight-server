# UX agent workflow

Use this workflow for browser-facing changes in the `server` module. It gives
agents a repeatable local loop and complements the detailed references in
[`ui.md`](ui.md), [`local-server.md`](local-server.md), and
[`testing.md`](testing.md).

## 1. Locate the complete change surface

For each page behavior, trace the request through:

1. A controller in `server/src/main/java/org/ebean/monitor/web`.
2. Its view model in `server/src/main/java/org/ebean/monitor/web/view`.
3. The Mustache template in `server/src/main/resources/ui`.
4. The page CSS and JavaScript in `server/src/main/resources/static`.

For shared Chart.js behavior, use `dashboard-charts.js`; do not duplicate
formatting, tooltip, theme, range-selection, or URL-state helpers in a page
script. See [`ui.md`](ui.md) for page routes and chart conventions.

## 2. Prepare repeatable local data

Install the reactor artifacts before running a test-classpath launcher. This is
required on a fresh checkout because the current `api` and `client` versions
are not necessarily available from Nexus:

```shell
mvn -q -pl server -am install -DskipTests
```

Start the local PostgreSQL container:

```shell
mvn -q -pl server \
  -Dexec.mainClass=main.StartPostgresDocker \
  -Dexec.classpathScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

It creates or reuses Docker container `eb_insight` on `localhost:7432`, using
database `ebean_insight`. Do not use a production or shared database for UX
development. If an existing `eb_insight` container cannot authenticate, inspect
it rather than deleting it automatically; it may contain local data initialized
with older credentials.

Build the server after making a change:

```shell
mvn -q -pl server -am package -DskipTests
```

Seed the deterministic UX fixture:

```shell
mvn -q -pl server \
  -Dexec.mainClass=main.SeedDemoData \
  -Dexec.classpathScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

The seed safely refreshes `shop-app` in environment `prod`, including query,
datasource, JVM, and plan-oriented data. Re-run it after changing seed logic or
when a test window needs refreshed timestamps. See [`demo-data.md`](demo-data.md)
for its data coverage.

## 3. Run and restart the server

Start the server from the repository root:

```shell
mvn -q -pl server \
  -Dexec.mainClass=org.ebean.monitor.Application \
  -Dexec.classpathScope=runtime \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

Check it is ready:

```shell
lsof -nP -iTCP:8091 -sTCP:LISTEN
curl -i http://localhost:8091/ux
```

After changing Java, Mustache, CSS, or JavaScript, rebuild and restart. Static
resources and templates are packaged into the running server; a browser reload
does not use source files directly.

```shell
mvn -q -pl server -am package -DskipTests
lsof -nP -iTCP:8091 -sTCP:LISTEN
kill <reported-server-pid>
# run the start command above
```

Only stop the numeric PID reported for port `8091`; never use broad
process-name termination commands. Hard-refresh the browser after restarting.
When diagnosing stale assets, inspect what the server actually serves:

```shell
curl -s http://localhost:8091/static/query-total.js
curl -s http://localhost:8091/static/query-total.css
```

## 4. Exercise the affected UX

Start with the seeded dashboard:

```text
http://localhost:8091/ux/top?app=shop-app&env=prod&range=4h
```

Also use the relevant detail route where appropriate:

```text
http://localhost:8091/ux/metric-detail?app=shop-app&env=prod&range=4h&label=Report.generate
```

For dashboard work, verify the changed behavior in light and dark themes, normal
and compact layouts, and at least one short and one wide time range. Check
no-data and sparse-data windows when range navigation or empty-state handling
changes. For charts, check tooltips, units, legend behavior, range selection,
zoom, and URL state across reloads and range changes.

## 5. Validate before handing off

Run the smallest relevant checks:

```shell
mvn -q -pl server -am test -Dtest=UIQueryTotalControllerTest
node --check server/src/main/resources/static/query-total.js
```

Use the test class and browser script that match the change; do not run
unrelated suites by default. A successful Maven build alone is insufficient:
inspect the rendered page and browser behavior using the local server. Consult
[`testing.md`](testing.md) for the broader validation matrix and
[`troubleshooting.md`](troubleshooting.md) when startup, stale assets, or chart
data are unexpected.
