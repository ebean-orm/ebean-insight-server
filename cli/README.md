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
| `--namespace` | *required* | Kubernetes namespace. No built-in default — set per call or persist it (see [Configuration](#configuration)). |
| `--service` | *required* | Service to port-forward to. No built-in default — set per call or persist it. |
| `--target-port` | `8091` | Service port. |
| `--local-port` | `0` | Local port to bind; `0` picks a free ephemeral port. |
| `--context` | – | `kubectl` context to use. |
| `--ready-timeout` | `20` | Seconds to wait for the forward to become ready. |
| `--no-shared` | `false` | Ignore any running `insight forward` daemon; start a private forward. |
| `--insight-key` | `$INSIGHT_KEY` | API key sent as the `Insight-Key` header. Falls back to the `INSIGHT_KEY` env var. Not needed via port-forward. |

`--namespace` and `--service` are deployment-specific and have **no built-in
defaults** — supply them on the command line, or persist them once with
`insight config` (below). A port-forward command fails fast with a clear message
if neither a flag, config value, nor `--url` provides a target.

## Configuration

Persist any connection option in `~/.insight/config.properties` so you don't have
to pass it every time. Explicit flags always override the stored value, which in
turn overrides the built-in default. Manage it with `insight config`:

```bash
insight config set namespace dev-core
insight config set service ebean-insight
insight config list                 # insight-key is masked
insight config get namespace
insight config unset service
insight config path                 # prints the file location
```

Persistable keys: `url`, `namespace`, `service`, `target-port`, `local-port`,
`context`, `ready-timeout`, `insight-key`, `output`, `auth-domain`,
`auth-user-pool-id`, `auth-client-id`, `auth-scope`, `auth-redirect-port`.

Resolution precedence for every option: **explicit flag → config file →
built-in default** (the built-in default only exists for non-identifying values
such as `target-port`). For example, set JSON as your default output format:

```bash
insight config set output json     # every command now defaults to -o json
insight envs                       # JSON
insight envs -o text               # flag still overrides to plain text
```

## Authentication

Three independent mechanisms, depending on how the server is configured and how
you reach it:

- **Port-forward (default)** — auth *is* your Kubernetes access: the tunnel only
  works because your `kubectl`/EKS credentials and RBAC let you forward to the
  Service. No `Insight-Key` is required.
- **Static `--url`** (e.g. an ingress) — the `/v1` API expects an `Insight-Key`
  header. Provide it with `--insight-key <key>` or the `INSIGHT_KEY` env var:

  ```bash
  export INSIGHT_KEY=...               # or pass --insight-key each call
  insight plans --url https://insight.example.com
  ```

- **OAuth2 bearer token (`insight login`)** — when the server has JWT
  enforcement enabled (`insight.auth.enabled=true`, see
  [docs/auth.md](../docs/auth.md)), authenticate once via the Cognito Hosted UI
  and the CLI sends `Authorization: Bearer <token>` on every request (alongside
  any `Insight-Key`). This works over **both** port-forward and `--url`.

### OAuth2 login

One-time setup — point the CLI at your Cognito **public** app client (PKCE; no
client secret). The redirect port must match a callback URL registered on the
app client (`http://localhost:<port>/callback`):

```bash
insight config set auth-domain https://<your>.auth.<region>.amazoncognito.com
# or derive the domain from the user pool id instead:
#   insight config set auth-user-pool-id <region>_<poolId>
insight config set auth-client-id <public-app-client-id>
insight config set auth-scope insight/read          # optional (default default/default)
insight config set auth-redirect-port 9876          # optional (default 9876)
```

Then:

```bash
insight login      # opens the browser; completes via a loopback redirect
insight whoami     # show the cached identity + token expiry
insight logout     # remove the cached token (~/.insight/token.json)
```

`insight login` runs the OAuth2 Authorization-Code + PKCE flow: it starts a
short-lived loopback server on `auth-redirect-port`, opens your browser to the
Hosted UI, captures the redirected code, exchanges it for tokens and caches them
in `~/.insight/token.json` (owner-only `0600`). Subsequent commands load that
token and **silently refresh** it (via the refresh token) when it has expired.
When the cached access token cannot be refreshed, the server returns `401` and
you simply re-run `insight login`.

| Config key | Default | Meaning |
|------------|---------|---------|
| `auth-domain` | – | Cognito Hosted-UI domain (e.g. `https://app.auth.ap-southeast-2.amazoncognito.com`). |
| `auth-user-pool-id` | – | Alternative to `auth-domain`: derive the domain from the user pool id. `auth-domain` wins if both are set. |
| `auth-client-id` | – | Public app client id (PKCE, no secret). |
| `auth-scope` | `default/default` | Requested OAuth2 scope(s). |
| `auth-redirect-port` | `9876` | Loopback callback port. Must match a registered Cognito callback URL `http://localhost:<port>/callback`. |

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
| `insight metrics --app X [--label] [--plan-capable]` | List an app's metrics (ID, NAME, HASH, LOC); full SQL with `-o json`. |
| `insight top [--app] [--env] [--by total\|mean\|max\|count] [--since-minutes N \| --since-hours N] [--plan-capable] [-n N]` | Rank metrics by cost over a window. Omit `--app` to span all apps. |
| `insight missing-plans [--app] [--by total\|mean\|max\|count] [--since-minutes N \| --since-hours N] [--older-than-minutes N \| --older-than-hours N] [--capture [--yes] [--env]] [-n N]` | Plan-capable metrics with no recent plan, ranked by cost. `--capture` requests a plan for every listed row (capped by `-n`). |
| `insight plans [--app] [--env] [--label] [--hash] [--since-minutes N] [--since-hours N] [-n/--limit N]` | List recently captured query plans (tabular). |
| `insight pending [--app] [--env]` | List plan captures queued on the server awaiting delivery to the forwarder (in-memory, ephemeral). |
| `insight plan <planId> [--raw]` | Show one captured plan. `--raw` prints only the EXPLAIN plan text. |
| `insight capture <app> <hash>... [--stdin] [--env]` | Request a fresh plan capture for one or more metric hashes (space or comma separated). `--stdin` reads additional whitespace/comma/newline-separated hashes from standard input. |
| `insight config <set\|get\|unset\|list\|path>` | Manage persisted settings in `~/.insight/config.properties`. |
| `insight login [--timeout-seconds N]` | Authenticate via Cognito (OAuth2 + PKCE) and cache the bearer token. |
| `insight whoami` | Show the cached login identity and token expiry. |
| `insight logout` | Remove the cached bearer token. |

Every command supports `-h`/`--help`, and the root supports `-V`/`--version`.

## Output format

The data commands (`apps`, `envs`, `plans`, `plan`, `capture`) accept
`-o`/`--output` with `text` (default) or `json`:

```bash
insight envs -o json
insight plans -n 5 --output json | jq '.[].label'
```

JSON is emitted compact (one line, pipe to `jq` to pretty-print) and an empty
result is rendered as `[]`. For `plan`, `-o json` takes precedence over `--raw`.
Set `insight config set output json` to make JSON the default for every command.

## Examples

```bash
# One-time: persist your target so you don't repeat --namespace/--service
insight config set namespace dev-core
insight config set service ebean-insight

# Now these use the persisted dev-core/ebean-insight:8091 target
insight envs
insight apps
insight plans -n 5
insight plan 2 --raw

# Find the most expensive queries lacking a fresh plan, then capture them
insight missing-plans --app myapp --by total
insight capture myapp hashA hashB --env test            # capture several explicitly
insight capture myapp hashA,hashB,hashC --env test      # comma-separated also works
insight missing-plans --app myapp -n 10 -o json \
  | jq -r '.[].key' | insight capture myapp --stdin --env test   # pipe hashes
insight missing-plans --app myapp -n 10 --capture --yes --env test   # one-shot bulk capture

# Talk to a server directly instead of port-forwarding
insight plans --url http://localhost:8091 --app myk8s-service --since-hours 24

# Override the persisted target for a single call
insight plans --context my-eks --namespace staging-core --service ebean-insight
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
- `LoginCommand` / `LogoutCommand` / `WhoamiCommand` — OAuth2 (Cognito + PKCE)
  login, backed by `AuthConfig` (resolves the `auth-*` settings + builds
  `CognitoOidc`), `LoopbackReceiver` (loopback redirect capture),
  `BrowserLauncher` (AWT-free browser open), `TokenStore`/`TokenData`
  (`~/.insight/token.json`, `0600`) and `AuthSession` (bearer + silent refresh,
  injected by `Insight.open`).

The typed API clients and DTOs come from `ebean-insight-client` /
`ebean-insight-api` (generated from `api/src/main/openapi/v1.yaml`). The
port-forward machinery comes from `ebean-insight-forwarder`.
