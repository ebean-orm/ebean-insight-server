# ebean-insight-cli

A command line tool for the `ebean-insight` `/v1` API — list applications and
environments, browse captured DB query plans, and request fresh plan captures.

It is built with [picocli](https://picocli.info) and is designed to compile to a
**single GraalVM native-image binary** (`insight`) so it can be installed and run
without a JVM.

## How it connects

The CLI reaches the server one of three ways, resolved in this order:

1. **Static URL** — pass `--url http://host:port` (e.g. an ingress or a
   port-forward you manage yourself). When set, everything below is ignored.
2. **A running `insight forward` daemon** — if one is active for the same target
   (namespace/service/port), short commands automatically reuse its tunnel
   (fast, no per-command startup). See [Daemon mode](#daemon-mode). Use
   `--no-shared` to bypass it.
3. **Per-command port-forward (fallback)** — otherwise the CLI starts and
   supervises its own `kubectl port-forward` (via `ebean-insight-forwarder`) for
   the duration of the command, reusing **your existing cluster access**
   (EKS/`kubectl` credentials + RBAC) as authentication, then tears it down on
   exit. No API keys or OAuth required.

Connection options (shared by every subcommand):

| Option | Default | Meaning |
|--------|---------|---------|
| `--url` | – | Static base URL. When set, the port-forward options below are ignored. |
| `--namespace` | `dev-core` | Kubernetes namespace. |
| `--service` | `central-insight` | Service to port-forward to. |
| `--target-port` | `8091` | Service port. |
| `--local-port` | `0` | Local port to bind; `0` picks a free ephemeral port. |
| `--context` | – | `kubectl` context to use. |
| `--ready-timeout` | `20` | Seconds to wait for the forward to become ready. |
| `--no-shared` | `false` | Ignore any running `insight forward` daemon; start a private forward. |
| `--insight-key` | `$INSIGHT_KEY` | API key sent as the `Insight-Key` header. Falls back to the `INSIGHT_KEY` env var. Not needed via port-forward. |

## Authentication

Two independent mechanisms, depending on how you reach the server:

- **Port-forward (default)** — auth *is* your Kubernetes access: the tunnel only
  works because your `kubectl`/EKS credentials and RBAC let you forward to the
  Service. No `Insight-Key` is required.
- **Static `--url`** (e.g. an ingress) — the `/v1` API expects an `Insight-Key`
  header. Provide it with `--insight-key <key>` or the `INSIGHT_KEY` env var:

  ```bash
  export INSIGHT_KEY=...               # or pass --insight-key each call
  insight --url https://insight.example.com plans
  ```

## Daemon mode

Each short command otherwise pays the ~1–3s cost of establishing (and then
tearing down) a `kubectl port-forward`. Running a **daemon** keeps one supervised
tunnel open so every command becomes fast and stateless:

```bash
insight forward            # holds the tunnel open; Ctrl-C to stop
# ... in another shell, these reuse it automatically (no per-command startup):
insight envs
insight plans -n 5
```

`insight forward` (alias `daemon`):

- starts a supervised `kubectl port-forward` and **keeps it alive** across pod
  rolls / connection drops (this is where the forwarder's reconnect logic earns
  its keep);
- advertises its stable local URL in `~/.insight/forward.properties` so other
  commands discover and reuse it (skip with `--no-register`);
- cleans up that advert and reaps the `kubectl` child on Ctrl-C / SIGTERM.

Discovery is target-scoped and self-healing: an advert is only reused when the
namespace/service/port match and the daemon is both alive (pid check) and
reachable (TCP probe); a stale advert is ignored and removed, falling back to a
per-command forward.

## Commands

| Command | Description |
|---------|-------------|
| `insight forward` (alias `daemon`) | Hold a supervised port-forward open for other commands to reuse. `--no-register` to not advertise it. |
| `insight apps [--active-within-minutes N \| --active-within-hours N]` | List known applications. |
| `insight envs` | List known environments. |
| `insight plans [--app] [--env] [--label] [--hash] [--since-minutes N] [--since-hours N] [-n/--limit N]` | List recently captured query plans (tabular). |
| `insight plan <planId> [--raw]` | Show one captured plan. `--raw` prints only the EXPLAIN plan text. |
| `insight capture <app> <hash> [--env]` | Request a fresh plan capture for a metric hash. |

Every command supports `-h`/`--help`, and the root supports `-V`/`--version`.

## Examples

```bash
# Uses the default supervised port-forward to dev-core/central-insight:8091
insight envs
insight apps
insight plans -n 5
insight plan 2 --raw

# Talk to a server directly instead of port-forwarding
insight --url http://localhost:8091 plans --app ebean-insight --since-hours 24

# Target a different namespace / context
insight --context my-eks --namespace staging-core plans
```

## Running

During development (JVM):

```bash
mvn -pl cli,forwarder,api,client -am -DskipTests install
java -cp "cli/target/classes:$(mvn -o -q -pl cli dependency:build-classpath \
  -Dmdep.outputFile=/dev/stdout | tail -1)" \
  org.ebean.monitor.cli.InsightCli envs
```

As a native binary:

```bash
mvn -pl cli -Pnative -DskipTests package   # produces cli/target/insight
./cli/target/insight envs
```

The `native` profile uses `org.graalvm.buildtools:native-maven-plugin` with the
picocli `picocli-codegen` annotation processor (which emits the reachability
metadata picocli needs under native-image). Build it with a GraalVM JDK on the
`JAVA_HOME`. The build passes `--install-exit-handlers` so the `insight forward`
daemon's shutdown hook (which clears its advert and reaps the `kubectl` child)
still runs on Ctrl-C / SIGTERM in the native binary.

## Module layout

- `InsightCli` — root `@Command` + `main`.
- `ConnectionOptions` — shared `--url` / port-forward mixin.
- `ForwardRegistry` — reads/writes the daemon advert (`~/.insight/forward.properties`);
  target-scoped, pid- and reachability-checked discovery.
- `ForwardCommand` — the `insight forward` daemon (supervised tunnel + advert + clean shutdown).
- `Insight` — resolves the `Endpoint` (`--url` → daemon advert → `StaticEndpoint`,
  else a per-command `SupervisedForwarder`), builds the avaje `HttpClient`
  (`JsonbBodyAdapter`) and the generated typed clients (`PlansApiHttpClient`,
  `AppsApiHttpClient`, `EnvsApiHttpClient`), and owns the forwarder lifecycle
  (`AutoCloseable`).
- `PlansCommand`, `PlanCommand`, `CaptureCommand`, `AppsCommand`, `EnvsCommand`.

The typed API clients and DTOs come from `ebean-insight-client` /
`ebean-insight-api` (generated from `api/src/main/openapi/v1.yaml`). The
port-forward machinery comes from `ebean-insight-forwarder`.
