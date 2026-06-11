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
| MCP SDK | **`io.modelcontextprotocol.sdk:mcp`** (official Java SDK), with a custom Streamable HTTP transport adapter for Jex |
| JSON | **avaje-jsonb** (matches rest of stack) |
| Config | **avaje-config** (yaml + env override) |
| Build | Native image via GraalVM, Linux x86_64, mirroring `server/`'s native profile |
| Distribution | jib → `docker.io/rbygrave/ebean-insight-mcp`; native zip attached to GitHub Releases |

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

| Phase | Scope | Outcome |
|---|---|---|
| **0** | Module skeleton, parent pom, native + jib wiring | Empty Jex server runs, image publishes |
| **1** | Bearer auth filter + `/health` + token store | 401/200 verified end-to-end |
| **2** | MCP SDK + Jex Streamable HTTP transport | `initialize` JSON-RPC handshake works |
| **3** | Read-only tools (`apps`, `envs`, `metrics`, `top`, `plans`, `plan`, `missing-plans`) | Claude can browse insight data |
| **4** | `capture` tool | Claude can request fresh plans |
| **5** | Plans-as-resources | Claude can attach plan markdown as context |
| **6** | Native-image hardening + release wiring (`v*` tag → image + zip) | Same release flow as server/CLI |
| **7** | Docs (`install-mcp.md`, `connect-mcp-clients.md`) + README links | First external user can self-serve |

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
