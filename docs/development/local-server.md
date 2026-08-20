# Local server development

This guide covers coding and maintaining the local server. Production
installation and deployment instructions remain in the surrounding user
documentation, especially [`../install-server.md`](../install-server.md).

## Prerequisites

- Java matching the repository compiler configuration.
- Maven.
- Docker for the local PostgreSQL container.
- Node.js for JavaScript syntax checks.

The server listens on port `8091` by default.

## Local PostgreSQL

On a fresh checkout, install the reactor artifacts first. The PostgreSQL
launcher uses the server test classpath, which includes the unreleased local
`api` and `client` modules:

```shell
mvn -q -pl server -am install -DskipTests
```

Then start the repository's local PostgreSQL container before seeding or
running the server in persist mode:

```shell
mvn -q -pl server \
  -Dexec.mainClass=main.StartPostgresDocker \
  -Dexec.classpathScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

This starts (or reuses) the `eb_insight` Docker container with PostgreSQL
available at `localhost:7432`, database `ebean_insight`, and the credentials
expected by `server/src/main/resources/application.yaml`. The local server and
the [demo-data seed](demo-data.md) use this same database.

If an existing `eb_insight` container cannot authenticate, it may have been
initialized previously with different credentials. Inspect it first; do not
delete or recreate the local container automatically because that discards its
data.

## Build

Build the server and its reactor dependencies:

```shell
mvn -q -pl server -am package -DskipTests
```

After a clean checkout, or when the current project version is not available
from the configured Nexus repository, install the reactor artifacts locally:

```shell
mvn -q -pl server -am install -DskipTests
```

The `-am` flag is important because the server depends on the local `api` and
`client` modules.

## Start

Start the server from the repository root:

```shell
mvn -q -pl server \
  -Dexec.mainClass=org.ebean.monitor.Application \
  -Dexec.classpathScope=runtime \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

For an interactive development shell, run the command in the foreground. A
detached process can be used when the terminal is needed for other work.

Check that the server is listening:

```shell
lsof -nP -iTCP:8091 -sTCP:LISTEN
curl -i 'http://localhost:8091/ux'
```

Useful local pages include:

```text
http://localhost:8091/ux
http://localhost:8091/ux/top?app=shop-app&range=4h
http://localhost:8091/ux/metric-detail?app=shop-app&range=4h&label=Report.generate&env=prod
```

## Stop and restart

Find the process first:

```shell
lsof -nP -iTCP:8091 -sTCP:LISTEN
```

Stop only the numeric PID reported for port `8091`:

```shell
kill <PID>
```

Rebuild and restart after changing Java classes, Mustache templates, or static
resources. Mustache templates are compiled into server-side generated code
during the Maven build, so the running process does not use template source
changes directly. A browser refresh alone is not sufficient when the running
process has loaded the previous build.

The normal restart loop is:

```shell
mvn -q -pl server -am package -DskipTests
# stop the existing PID
# run the start command again
```

## Static-resource changes

Changes under `server/src/main/resources/static` and
`server/src/main/resources/ui` are copied into `server/target` during the
build; UI templates are also compiled into generated server-side code.
Rebuild and restart the server after changing them. If a browser still shows
old JavaScript or CSS, perform a hard refresh and confirm the served resource:

```shell
curl -s http://localhost:8091/static/query-total.js
curl -s http://localhost:8091/static/metric-detail.css
```

For JavaScript-only changes, also run:

```shell
node --check server/src/main/resources/static/query-total.js
node --check server/src/main/resources/static/metric-detail.js
```

## Seed data

The development seed tool is `main.SeedDemoData` in
`server/src/test/java/main/SeedDemoData.java`. It creates the local
`shop-app`/`prod` data used by the UX examples, including historical rollups,
multiple query hashes, query families, plans, and long locations.

Run it with the test classpath:

```shell
mvn -q -pl server \
  -Dexec.mainClass=main.SeedDemoData \
  -Dexec.classpathScope=test \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

Re-seeding is intended to be safe. It refreshes seeded rollup and plan rows and
updates the stored location and SQL metadata for existing seeded metrics.

## Troubleshooting

- **Missing `ebean-insight-api` or `ebean-insight-client`:** run the reactor
  `install` command above.
- **Stale generated test source after deleting a class:** run
  `mvn -q -pl server -am clean package -DskipTests`.
- **Port already in use:** inspect port `8091`, then stop only its reported PID.
- **Old UI after a change:** rebuild, restart, hard-refresh, and inspect the
  served static resource with `curl`.
- **Seeded metadata looks unchanged:** re-run the seed tool after rebuilding;
  existing metric rows are updated by the current seed implementation.
