# Agent guidance

This repository contains the Ebean Insight server, its versioned API, generated
HTTP client, CLI, MCP server, and forwarding support. The existing `docs/`
directory is user-facing documentation; this file describes how to maintain
and modify the repository.

## Repository layout

| Module | Purpose |
| --- | --- |
| `api` | OpenAPI contract and generated API interfaces/DTOs. |
| `client` | Generated typed HTTP client for `/v1`. |
| `server` | Ingest, persistence, rollups, API controllers, and UX pages. |
| `forwarder` | Supervised local port-forward support. |
| `cli` | `insight` command-line client. |
| `mcp` | MCP server exposing the `/v1` API. |

For UX changes, trace the complete chain:

1. Controller in `server/src/main/java/org/ebean/monitor/web`.
2. View model in `server/src/main/java/org/ebean/monitor/web/view`.
3. Mustache template in `server/src/main/resources/ui`.
4. Static JavaScript/CSS in `server/src/main/resources/static`.

Important UX routes include `/ux/top`, `/ux/metric-detail`,
`/ux/query-hash`, and `/ux/query-plan`.

## Build and validation

Use the smallest relevant validation command, normally:

```shell
mvn -q -pl server -am package -DskipTests
```

If Maven cannot resolve the current reactor API/client version from Nexus,
install the reactor artifacts locally:

```shell
mvn -q -pl server -am install -DskipTests
```

Run focused tests where available:

```shell
mvn -q -pl server -am test -Dtest=V1ControllerTest
node --check server/src/main/resources/static/query-total.js
node --check server/src/main/resources/static/metric-detail.js
```

Do not commit, push, create branches, or rewrite history unless explicitly
requested.

## Implementation conventions

- Make narrow changes and preserve unrelated working-tree changes.
- Prefer constructor injection and avoid global mutable state.
- Keep generated API sources derived from the OpenAPI contract; edit
  `api/src/main/openapi/v1.yaml` rather than generated output.
- Preserve `null` chart values when a bucket has no data. Do not replace chart
  gaps with zeroes unless zero is the intended measurement.
- Use scoped Jsonb configuration when a UI chart must serialize null list
  elements; do not change general API null serialization casually.
- Escape JSON embedded in HTML script elements.
- Reuse existing chart, URL, formatting, theme, and view helpers.
- When deleting or renaming generated/controller classes, clean-build so stale
  generated test sources do not mask the result.

## Local server

See [`docs/development/local-server.md`](docs/development/local-server.md) for
build, start, restart, seed-data, and troubleshooting instructions.

The full maintenance guide index is
[`docs/development/README.md`](docs/development/README.md).
