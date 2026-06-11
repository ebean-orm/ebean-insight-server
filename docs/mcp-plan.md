# MCP server — plan

A new Maven module `mcp/` that exposes the `ebean-insight-server` `/v1` API
over the [Model Context Protocol](https://modelcontextprotocol.io/) so AI
agents (Claude Desktop, VS Code MCP, Codex, etc.) can drive the insight
tooling directly.

This document is the agreed plan; it does not yet describe shipped code.

---

## Architecture

```
┌─────────────────┐  Bearer <token>   ┌──────────────────┐  Insight-Key  ┌──────────────────────┐
│ MCP client      ├──────────────────▶│ ebean-insight-mcp├──────────────▶│ ebean-insight-server │
│ (agent / IDE)   │  HTTPS (JSON-RPC) │ (avaje-jex)      │  HTTP /v1     │ (existing)           │
└─────────────────┘                   └──────────────────┘               └──────────────────────┘
```

- **Separate Maven module + separate deployable.** The MCP server is its own
  jib image and native binary, deployed alongside `ebean-insight` (typically
  in the same namespace, talking via cluster DNS).
- **Reuses the existing `client` module** verbatim — MCP server is a thin
  protocol-translation layer over `InsightClient`.
- **Two distinct credentials:**
  - `MCP_TOKEN` (inbound) — `Authorization: Bearer <token>` from MCP client
  - `INSIGHT_KEY` (outbound) — header sent by MCP server to the insight server

## Tech stack

| Concern | Choice |
|---|---|
| HTTP server | **avaje-jex** (native-friendly, matches the existing server module) |
| MCP protocol | **Hand-rolled JSON-RPC 2.0** over avaje-jex + avaje-jsonb (Map-based dynamic JSON, reflection-free). The official `io.modelcontextprotocol.sdk:mcp` was evaluated and rejected for this module: SDK 2.0 pulls in Jackson + Reactor, a poor fit for a lean native-image avaje server, and our protocol surface (initialize, ping, tools, resources) is small. |
| JSON | **avaje-jsonb** (matches rest of stack) |
| Config | **avaje-config** (yaml + env override) |
| Build | Native image via GraalVM, Linux x86_64, mirroring `server/`'s native profile |
| Distribution | jib → `docker.io/rbygrave/ebean-insight-mcp`; native zip attached to GitHub Releases |

## Prerequisite — split `/v1` API auth on the insight server

The MCP server needs to authenticate against the existing insight server's
`/v1/*` endpoints. Today the auth model is:

| Path | Mechanism | Notes |
|---|---|---|
| `/api/ingest` | `Insight-Key` header → `IngestKeyValidator` | Multiple keys (rotation-friendly) |
| `/v1/*`, `/` | JWT Bearer (Cognito) when `insight.auth.enabled=true` | All-or-nothing, no shared-secret option |
| `/health` | open | k8s probes |

**Problem (motivation):**
- The CLI's `--insight-key` flag sent the `Insight-Key` header, but the server
  only validated that on `/api/ingest`. On `/v1` it was silently ignored — so
  in an auth-enabled deployment it never authenticated. (Resolved in Phase −1b
  by removing the dead flag; the CLI uses the OAuth2 JWT flow for `/v1`.)
- The MCP server needs programmatic shared-secret access to `/v1`.
- Reusing the **ingest key** for `/v1` would conflate two very different
  blast radii: ingest keys are distributed to every reporting app instance;
  read-side keys go to operators / agents.

**Proposed change to `ebean-insight-server`:**

Introduce a second shared-secret credential for `/v1` API access, separate
from the ingest key. Mirrors the existing ingest-key pattern (config +
validator + filter), independent of JWT.

| Aspect | Ingest key (existing) | API key (new) |
|---|---|---|
| Config | `insight.ingest.key` | `insight.api.key` |
| Multi-key (rotation) | Yes (comma-separated) | Yes (comma-separated) |
| Header | `Insight-Key` | `Authorization: Bearer <token>` |
| Used by | App forwarders pushing metrics in | MCP server (and any future programmatic reader) |
| Path | `/api/ingest` | `/v1/*` |

**Filter ordering (when both `insight.auth.enabled=true` and `insight.api.key` set):**

The api-key check happens **inside** `JwtAuthFilter` via a new
`bearerAuthoriser(BearerAuthoriser)` hook on the builder (see
[Phase −2](#phased-delivery) below — upstream `avaje-oauth2` change). Single
filter, single auth decision point:

```java
JwtAuthFilter.builder()
  .permit("/health")
  .permit("/api/ingest")
  .verifier(jwtVerifier)
  // BearerAuthoriser receives the bearer token (already stripped of "Bearer ").
  // Returns a principal name to accept, or null to fall through to JWT.
  .bearerAuthoriser(token -> apiKeyValidator.principalFor(token))
  .build();
```

`BearerAuthoriser` is a small functional interface in **`avaje-oauth2-core`**
(`String authorise(String token)`) — framework-agnostic, so the one type is
shared by both the Jex and Helidon filter variants. It is consulted only for
requests carrying an `Authorization: Bearer` header (the secret is presented
as a bearer token, matching how the CLI and MCP clients send it). A constant-
time comparison (`MessageDigest.isEqual`) should be used for the match.

Request handling order (the **final, permit-first** ordering):

1. **Permit paths first** — `/health` and `/api/ingest` proceed regardless of
   any headers (so a stale token riding along on an open path never causes a
   spurious 401, and probes skip auth work entirely).
2. **`bearerAuthoriser`** — for an `Authorization: Bearer` request, if
   configured and it returns a non-null principal (api-key matched), the
   request is authenticated and JWT verification is skipped.
3. **JWT verify** — otherwise the standard JWT path runs. Cognito JWTs (future
   browser UI login) continue to work on `/v1` unchanged.
4. Otherwise → 401.

A two-filters-in-chain approach (api-key filter then JWT filter) does not
work cleanly because JwtAuthFilter has no signal to skip when an upstream
filter has already authenticated the request. The upstream hook is both
simpler and reusable beyond this repo.

**When `insight.api.key` is unset (the default):** the authoriser is `null`,
JwtAuthFilter behaves exactly as today. The change is strictly additive.

> **Why bearer-only (not "any header")?** An earlier iteration passed the whole
> framework `Context` so the secret could come from any header. We dropped that:
> the real consumers send the secret as `Authorization: Bearer`, the bearer-token
> hook is framework-agnostic (one shared core type vs duplicated per-variant),
> and it keeps auth on a single credential channel.

**CLI follow-up (done — decided to *remove*, not repurpose):**
- The CLI's `--insight-key` only ever set the `Insight-Key` header, which `/v1`
  never validated (only `/api/ingest` does, and the CLI never calls ingest) —
  so it was dead on `/v1`.
- **Removed** `--insight-key` / `INSIGHT_KEY` / the `insight-key` config key and
  the `Insight-Key` request interceptor from the CLI entirely. The CLI's `/v1`
  auth when enabled is the existing OAuth2 JWT flow (`insight login` →
  `Authorization: Bearer <jwt>`).
- The shared-secret API key (`Authorization: Bearer <key>` against
  `insight.api.key`) is **not** wired into the CLI for now — it is intended for
  the MCP server. A CLI `--api-key` flag can be added later if wanted.

**Phasing:** ship the server change *before* MCP Phase 0 lands so the MCP
server has something to authenticate against. Worth a separate PR — it's
useful on its own.

## Confirmed decisions

- **Transport (MVP):** HTTP only (Streamable HTTP). Single `POST /mcp`
  endpoint, JSON-RPC in/out, optional SSE for streaming. No stdio in MVP.
- **Token model:** list of tokens, each with an optional name label, for
  rotation. Kept single-tenant — every valid token has the same authority.
- **MVP tool surface:** read-only + `capture` + plans-as-resources (full
  parity with the CLI).

## Tools to expose (MCP `tools` capability)

| Tool | Description | Input |
|---|---|---|
| `apps` | List known applications | — |
| `envs` | List known environments | — |
| `metrics` | List metrics for an app, with filters | `app`, `label?`, `planCapable?` |
| `top` | Top metrics over a recent window | `app?`, `env?`, `by?` (total/mean/max/count), `limit?`, `sinceMinutes?`, `planCapable?` |
| `plans` | List recently captured plans | `app?`, `env?`, `hash?`, `label?`, `sinceHours?`, `limit?` |
| `plan` | Fetch a single plan (sql + bind + plan text) | `id` |
| `missing-plans` | Plan-capable metrics with no recent plan | `app?`, `env?` |
| `capture` | **Write op:** request a fresh plan capture | `app`, `hash`, `env` |

Tool descriptions explicitly call out `capture` as a write operation that
triggers work in the target application.

## Resources (MCP `resources` capability)

- **List:** enumerate recent plan ids as `insight://plan/{id}` URIs.
- **Read:** fetch a plan and render as markdown (SQL block, bind values, plan
  text). Lets agents `read_resource` rather than `call_tool` when they want
  to attach plan content as context.
- **Subscriptions:** out of scope for MVP.

## Auth (Bearer token middleware)

- avaje-jex `before("/mcp/*")` filter:
  - Reject if `Authorization` missing → 401.
  - Constant-time compare against each configured token.
  - On match: log token name + bind to request attribute (for access log).
  - On miss: 401.
- `/health` endpoint is unauthenticated (k8s liveness/readiness).
- Tokens loaded from avaje-config, with two supported shapes:

```yaml
mcp:
  tokens:
    - name: claude-desktop
      value: ${MCP_TOKEN_CLAUDE_DESKTOP}
    - name: cli-agent
      value: ${MCP_TOKEN_CLI_AGENT}
```

…or via env var for simple cases:

```
MCP_TOKENS=claude-desktop:abc123,cli-agent:def456
```

## Module layout

```
mcp/
  pom.xml                                   # depends on client + api; jib + native profiles
  src/main/java/org/ebean/monitor/mcp/
    MCPApp.java                             # entrypoint; wires Jex + McpServer
    auth/BearerAuthFilter.java
    auth/TokenStore.java
    transport/JexStreamableHttpTransport.java
    tools/                                  # one class per tool
    resources/PlanResourceProvider.java
  src/main/resources/application.yaml
  src/main/resources/META-INF/native-image/ # reflect-config etc. as needed
```

## Phased delivery

| Phase | Where | Scope | Outcome |
|---|---|---|---|
| **−2** | upstream `avaje-oauth2` (`JwtAuthFilter`) | Add a `BearerAuthoriser` (`String authorise(String token)`) in `avaje-oauth2-core` + a `bearerAuthoriser(...)` builder hook on both filter variants, consulted before JWT for `Authorization: Bearer` requests (skips JWT when it returns a non-null principal). Also make permit-paths checked first. Tag a release. | Hook available; permit-first; no behaviour change when hook unset. **DONE — implemented + tested (jex 10, helidon 8, core 16).** |
| **−1** | `ebean-insight-server` (server module) | Add `insight.api.key` config + `ApiKeyValidator` + wire into `JwtAuthFilter` via the new `bearerAuthoriser(...)` hook (see [Prerequisite](#prerequisite--split-v1-api-auth-on-the-insight-server) above). Independent PR. | `/v1` accepts `Authorization: Bearer <api-key>` |
| **−1b** | `cli` | Remove the dead `Insight-Key` plumbing (`--insight-key` / `INSIGHT_KEY` / `insight-key` config key / request interceptor). CLI `/v1` auth stays as the existing OAuth2 JWT flow; the shared-secret api-key is left for the MCP server (no CLI `--api-key` yet). | **DONE** — CLI no longer carries dead Insight-Key auth; 96 cli tests pass. |
| **0** | new `mcp/` module | Module skeleton, parent pom, native + jib wiring | **DONE** — Jex server boots; uses Jex's built-in health (`/health/liveness` + `/health/readiness` → ok). JVM + GraalVM native both verified (native build ~27s, boots in ~2ms). |
| **1** | `mcp/` | Bearer auth filter (inbound `MCP_TOKEN` list) + `/health` + token store | **DONE** — `TokenStore` (`mcp.tokens` = `name:value,…`, rotation, constant-time), `BearerAuthFilter` (permit `/health`, 401 otherwise, binds `security.principal`), `McpAuthConfiguration` `@Factory`. 19 tests incl. real-HTTP integration (401 vs 200 vs permit). JVM + native verified. |
| **2** | `mcp/` | MCP SDK + Jex Streamable HTTP transport | **DONE** — *hand-rolled* JSON-RPC over Jex + avaje-jsonb (SDK 2.0 drags in Jackson+Reactor; protocol surface is small). `McpController` (`@Controller @Post /mcp`), `McpJsonRpc` (initialize/ping/notify/errors, Map-based dynamic JSON = native-safe), `McpServer` (version negotiation). `@InjectTest` + auto-wired `HttpClient` tests (handshake 200 / 401 / 202 notification / open health). JVM + native verified — native binary completes the real handshake. |
| **3** | `mcp/` | Read-only tools (`apps`, `envs`, `metrics`, `top`, `plans`, `plan`, `missing-plans`) | **DONE** — `tools/list` + `tools/call` over `InsightTools` (7 tools wrapping the typed `/v1` clients; per-tool JSON-schema; results as JSON text content; tool errors → `isError`). `InsightApiClients` factory builds the outbound client (Bearer `insight.api.key`). 50 tests — incl. a full `@InjectTest` integration test (`McpToolsIntegrationTest`) that boots the stack and overrides the 4 `/v1` API beans with field-injected test doubles, exercising HTTP→controller→JSON-RPC→tools→doubles. Also verified on the **native binary against live test central-insight** — real apps/envs/top data returned. |
| **4** | `mcp/` | `capture` tool | **DONE** — `capture` (app, hash, env→`PlansApi.requestPlanCapture`), description flags it as a WRITE op. 54 tests (unit + full `@InjectTest` integration). Verified on the **native binary against live test central-insight**: real capture returned `{"pending":1,...,"label":"orm.DMessage.findMessages"}` and the `plans` tool reads captures back. |
| **5** | `mcp/` | Plans-as-resources | **DONE** — `resources/list` + `resources/templates/list` + `resources/read` over `PlanResources` (plans as `insight://plan/{id}`, rendered as markdown with SQL/bind/plan). `resources` capability advertised. Also hardened `McpJsonRpc` dispatch: any upstream failure → JSON-RPC internal error (-32603), never a transport 500. 66 tests. Verified on the **native binary against live test central-insight** — listed 12 real plans, read one back as markdown. |
| **6** | `mcp/` + workflows | Native-image hardening + release wiring (`v*` tag → image + zip) | **DONE** — hardened native buildArgs already match server (Phase 0). `native-build.yml` extended: whole-reactor `-Pnative` build publishes `docker.io/rbygrave/ebean-insight-mcp` via jib alongside the server image; adds workflow-artifact upload + stages/attaches `ebean-insight-mcp-<tag>-linux-x64.zip` (+`.sha256`) to the GitHub Release. YAML validated. |
| **7** | `docs/` | `install-mcp.md`, `connect-mcp-clients.md`, README links | **DONE** — `install-mcp.md` (k8s/Docker/standalone, config env-var table, verify recipes), `connect-mcp-clients.md` (Claude Desktop/Code, VS Code, generic HTTP, stdio bridge, troubleshooting). README Install section + Modules table updated; cross-linked from `install-server.md`. `application.yaml` switched to `${ENV:default}` placeholders so the documented env vars resolve. |


## Open / deferred

- **MCP SDK fitness for native-image** — verify in Phase 2 spike. If reflection
  hints become unwieldy, fallback is hand-rolled JSON-RPC over Jex (protocol
  is small).
- **Token rotation without restart** — start with config-on-restart. Move to
  DynamoDB-backed token store later if demand arises.
- **Resource subscriptions / change notifications** — not in MVP.
- **Rate limiting** — add when needed.
- **Prompts capability** — not useful for this domain.

## Out of scope

- Multi-tenant authorization (different tokens with different rights)
- Embedding MCP into the existing `server` module (we want a separate
  blast-radius and image)
- stdio transport (MCP-over-subprocess for local dev). Easy to add later if
  someone wants it.

---

## Cross-references

- [`install-server.md`](install-server.md) — deployment patterns we mirror
- [`install-cli.md`](install-cli.md) — release/distribution flow we mirror
- [`deployment-modes.md`](deployment-modes.md) — server modes (the MCP server
  always speaks to a "forward+capture" mode insight server)
