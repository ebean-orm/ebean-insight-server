# Installing ebean-insight-mcp

The **MCP server** exposes the ebean-insight [`/v1` API](../README.md#v1-api-agent--cli--tooling)
over the [Model Context Protocol](https://modelcontextprotocol.io/) so AI agents
(Claude Desktop, VS Code, etc.) can browse metrics and query plans and request
fresh captures. It is a thin, stateless translation layer in front of an
[ebean-insight-server](install-server.md) — deploy it alongside one.

To point an MCP client at a running server, see
[`connect-mcp-clients.md`](connect-mcp-clients.md).

Choose the path that fits your environment:

- [Kubernetes](#kubernetes) — recommended for production.
- [Docker / docker-compose](#docker--docker-compose) — quickest for a single
  host or evaluation.
- [Standalone native binary](#standalone-native-binary) — JVM-free run on a
  bare host (Linux x86\_64 binary attached to each tagged Release; build from
  source for other platforms).

The MCP server listens on port **8092** by default and serves a single
JSON-RPC endpoint at **`POST /mcp`** (plus open `/health/liveness` and
`/health/readiness` probes).

## Configuration

All settings come from environment variables (or `application.yaml`):

| Env var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8092` | HTTP listen port. |
| `INSIGHT_URL` | `http://localhost:8091` | Base URL of the ebean-insight-server `/v1` API. |
| `INSIGHT_API_KEY` | *(unset)* | Sent as `Authorization: Bearer <key>` to the insight server. Set when the server has auth enabled (matches the server's `insight.api.key`); leave unset when reaching it open over localhost / cluster DNS. |
| `MCP_TOKENS` | *(unset)* | Inbound client auth: comma-separated `name:value` bearer tokens (the name is an audit label), e.g. `claude:abc123,ide:def456`. Unset leaves the endpoint open — set it for any non-loopback exposure. |

Two distinct credentials are involved: **`MCP_TOKENS`** authenticates inbound
MCP clients to this server; **`INSIGHT_API_KEY`** is what this server presents
outbound to the insight server.

---

## Kubernetes

### Image

Published to Docker Hub:

```
docker.io/rbygrave/ebean-insight-mcp:<version>     # linux/amd64 native image
```

Pick a published tag (see
[Docker Hub tags](https://hub.docker.com/r/rbygrave/ebean-insight-mcp/tags))
matching the release you want.

### Minimal manifest set

Deploy in the **same namespace** as `ebean-insight` so the MCP server reaches
it over cluster DNS. Adjust `namespace`, image tag, and tokens for your
environment.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ebean-insight-mcp-secrets
  namespace: ebean-insight
type: Opaque
stringData:
  # Inbound MCP client tokens (rotate by listing both old,new).
  MCP_TOKENS: "claude:change-me-long-random,ide:another-long-random"
  # Outbound key to the insight server (omit if the server is open in-cluster).
  INSIGHT_API_KEY: "change-me-if-server-auth-enabled"

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ebean-insight-mcp
  namespace: ebean-insight
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ebean-insight-mcp
  template:
    metadata:
      labels:
        app: ebean-insight-mcp
    spec:
      containers:
        - name: ebean-insight-mcp
          image: docker.io/rbygrave/ebean-insight-mcp:<version>
          ports:
            - name: http
              containerPort: 8092
          env:
            - name: INSIGHT_URL
              value: http://ebean-insight:8091
            - name: MCP_TOKENS
              valueFrom: { secretKeyRef: { name: ebean-insight-mcp-secrets, key: MCP_TOKENS } }
            - name: INSIGHT_API_KEY
              valueFrom: { secretKeyRef: { name: ebean-insight-mcp-secrets, key: INSIGHT_API_KEY } }
          readinessProbe:
            httpGet: { path: /health/readiness, port: http }
            initialDelaySeconds: 2
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /health/liveness, port: http }
            initialDelaySeconds: 5
            periodSeconds: 30
          resources:
            requests: { cpu: 50m,  memory: 64Mi }
            limits:   { cpu: 500m, memory: 128Mi }

---
apiVersion: v1
kind: Service
metadata:
  name: ebean-insight-mcp
  namespace: ebean-insight
spec:
  selector:
    app: ebean-insight-mcp
  ports:
    - name: http
      port: 8092
      targetPort: http
```

Apply:

```bash
kubectl apply -f ebean-insight-mcp.yaml
kubectl -n ebean-insight rollout status deploy/ebean-insight-mcp
```

### Verify

```bash
kubectl -n ebean-insight port-forward svc/ebean-insight-mcp 8092:8092

# initialize handshake
curl -s -X POST http://localhost:8092/mcp \
  -H "Authorization: Bearer change-me-long-random" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

# list tools
curl -s -X POST http://localhost:8092/mcp \
  -H "Authorization: Bearer change-me-long-random" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

### Ingress

MCP clients are usually remote, so the MCP server is typically exposed via
Ingress (terminate TLS at the controller). **Always set `MCP_TOKENS`** when
exposing outside the cluster — the bearer token is the only thing protecting
the endpoint. A minimal ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ebean-insight-mcp
  namespace: ebean-insight
spec:
  ingressClassName: nginx
  rules:
    - host: insight-mcp.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ebean-insight-mcp
                port: { number: 8092 }
  tls:
    - hosts: [insight-mcp.example.com]
      secretName: ebean-insight-mcp-tls
```

---

## Docker / docker-compose

Run the MCP server next to a server (here reached open over the compose
network, so no `INSIGHT_API_KEY`):

```yaml
# docker-compose.yml (excerpt — add your insight + postgres services too)
services:
  insight-mcp:
    image: docker.io/rbygrave/ebean-insight-mcp:<version>
    depends_on: [insight]
    environment:
      INSIGHT_URL: http://insight:8091
      MCP_TOKENS: "claude:change-me"
    ports: ["8092:8092"]
```

```bash
docker compose up -d
curl -s -X POST http://localhost:8092/mcp \
  -H "Authorization: Bearer change-me" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Single container against an existing server:

```bash
docker run --rm -p 8092:8092 \
  -e INSIGHT_URL=http://host.docker.internal:8091 \
  -e MCP_TOKENS=claude:change-me \
  rbygrave/ebean-insight-mcp:<version>
```

---

## Standalone native binary

For tagged releases, the Linux x86\_64 MCP native binary is attached to the
corresponding [GitHub Release](https://github.com/ebean-orm/ebean-insight-server/releases)
as `ebean-insight-mcp-<version>-linux-x64.zip` (with a matching `.sha256`).
For other platforms, build the native image locally:

```bash
# Option A — download the linux-x64 binary from the latest Release
curl -L -o mcp.zip \
  https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/ebean-insight-mcp-<version>-linux-x64.zip
unzip mcp.zip
chmod +x ebean-insight-mcp-<version>-linux-x64/ebean-insight-mcp

# Option B — build from source (requires GraalVM JDK on JAVA_HOME)
# e.g. via SDKMAN: sdk install java 24-graal
git clone https://github.com/ebean-orm/ebean-insight-server.git
cd ebean-insight-server
mvn -pl mcp -am -Pnative,linux -DskipTests package   # or -Pnative,mac on macOS
./mcp/target/ebean-insight-mcp
```

Run it pointed at a server:

```bash
INSIGHT_URL=http://localhost:8091 \
MCP_TOKENS=claude:change-me \
./ebean-insight-mcp
```

A simple `systemd` unit on Linux:

```ini
# /etc/systemd/system/ebean-insight-mcp.service
[Unit]
Description=ebean-insight MCP server
After=network-online.target

[Service]
Type=simple
User=insight
EnvironmentFile=/etc/ebean-insight-mcp/env
ExecStart=/opt/ebean-insight-mcp/ebean-insight-mcp
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```
# /etc/ebean-insight-mcp/env
INSIGHT_URL=http://localhost:8091
INSIGHT_API_KEY=change-me-if-server-auth-enabled
MCP_TOKENS=claude:change-me-long-random
```

---

## Next steps

- **Connect an MCP client** — [`connect-mcp-clients.md`](connect-mcp-clients.md)
- **Install the server** — [`install-server.md`](install-server.md)
- **/v1 API reference** — [top-level README](../README.md#v1-api-agent--cli--tooling)
