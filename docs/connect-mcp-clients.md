# Connecting MCP clients

This guide points AI agents / MCP clients at a running
[ebean-insight-mcp](install-mcp.md) server so they can browse metrics and query
plans and request captures.

The server speaks **Streamable HTTP** MCP at `POST /mcp` and authenticates
clients with a bearer token (one of the `name:value` entries in the server's
`MCP_TOKENS`). The token is sent as `Authorization: Bearer <value>`.

> Replace `https://insight-mcp.example.com/mcp` with your server URL (or
> `http://localhost:8092/mcp` when port-forwarding) and `<token>` with one of
> the configured token values.

## What the agent can do

Once connected, the server advertises **tools** and **resources**:

| Tool | Purpose |
|---|---|
| `apps` | List applications reporting to ebean-insight. |
| `envs` | List environments. |
| `metrics` | List an app's metrics (filter by label / plan-capable). |
| `top` | Top metrics by total/mean/max time or call count over a window. |
| `plans` | List recently captured query plans. |
| `plan` | Fetch one captured plan (SQL + bind + plan text). |
| `missing-plans` | Plan-capable metrics with no recent plan. |
| `capture` | **Write:** request a fresh query-plan capture for a metric. |

Captured plans are also exposed as **resources** (`insight://plan/{id}`) that an
agent can read directly as markdown.

---

## Claude Desktop / Claude Code

Claude reaches remote HTTP MCP servers via the `mcpServers` config with a
`"type": "http"` transport. Add to your Claude config (e.g. Claude Desktop
`claude_desktop_config.json`, or `claude mcp add` for Claude Code):

```json
{
  "mcpServers": {
    "ebean-insight": {
      "type": "http",
      "url": "https://insight-mcp.example.com/mcp",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

Claude Code equivalent:

```bash
claude mcp add --transport http ebean-insight https://insight-mcp.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

---

## VS Code (GitHub Copilot / MCP)

Add to `.vscode/mcp.json` in your workspace (or user settings). Prompt for the
token rather than hard-coding it:

```json
{
  "inputs": [
    { "id": "insight-token", "type": "promptString", "description": "ebean-insight MCP token", "password": true }
  ],
  "servers": {
    "ebean-insight": {
      "type": "http",
      "url": "https://insight-mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${input:insight-token}" }
    }
  }
}
```

---

## Other clients / generic HTTP

Any MCP client supporting the Streamable HTTP transport works — point it at the
`/mcp` URL and add the `Authorization: Bearer <token>` header. The server
implements protocol version `2025-06-18` (and negotiates older versions a
client may request).

For clients that only speak **stdio**, bridge with a generic stdio→HTTP proxy
such as [`mcp-remote`](https://github.com/geelen/mcp-remote):

```json
{
  "mcpServers": {
    "ebean-insight": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "https://insight-mcp.example.com/mcp",
        "--header", "Authorization: Bearer <token>"
      ]
    }
  }
}
```

---

## Local development (port-forward)

When the MCP server runs in Kubernetes you can connect a local client through a
port-forward:

```bash
kubectl -n ebean-insight port-forward svc/ebean-insight-mcp 8092:8092
```

then use `http://localhost:8092/mcp` as the URL.

---

## Troubleshooting

- **401 Unauthorized** — missing/wrong `Authorization: Bearer` token. Confirm
  the value matches one configured in the server's `MCP_TOKENS`.
- **Tool results say `Error: Http call failed with status: 401`** — the MCP
  server reached the insight server but was rejected: set `INSIGHT_API_KEY` (or
  the insight server has auth enabled and the key is wrong).
- **`Error: java.net.ConnectException`** in tool results — the MCP server can't
  reach `INSIGHT_URL`. Check the URL and network path.
- **Empty `plans` / `resources/list`** — no captures yet; use the `capture`
  tool (or the CLI) to request one, then retry.

See [`install-mcp.md`](install-mcp.md) for server configuration.
