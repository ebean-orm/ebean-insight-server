# Installing ebean-insight-server

This guide covers running the **server** in production. For mode-specific
configuration (persist vs forward-only, env-var reference, healthy-startup logs)
see [`deployment-modes.md`](deployment-modes.md).

Choose the path that fits your environment:

- [Kubernetes](#kubernetes) — recommended for production.
- [Docker / docker-compose](#docker--docker-compose) — quickest for a single
  host or evaluation.
- [Standalone native binary](#standalone-native-binary) — JVM-free run on a
  bare host (Linux x86\_64 binary attached to each tagged Release; build from
  source for other platforms).

The server listens on port **8091** by default and persists to **PostgreSQL**
in the default *persist* mode.

---

## Kubernetes

### Image

Published to Docker Hub:

```
docker.io/rbygrave/ebean-insight:<version>     # linux/amd64 native image
```

Pick a published tag (see
[Docker Hub tags](https://hub.docker.com/r/rbygrave/ebean-insight/tags))
matching the release you want.

### Minimal manifest set

Adjust `namespace`, image tag, Postgres endpoint, and `Insight-Key` for your
environment. This example runs in *persist* mode against an existing Postgres.

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ebean-insight

---
apiVersion: v1
kind: Secret
metadata:
  name: ebean-insight-secrets
  namespace: ebean-insight
type: Opaque
stringData:
  DB_USER: ebean_insight
  DB_PASS: ebean_insight
  INSIGHT_KEY: "change-me-long-random-string"

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ebean-insight
  namespace: ebean-insight
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ebean-insight
  template:
    metadata:
      labels:
        app: ebean-insight
    spec:
      containers:
        - name: ebean-insight
          image: docker.io/rbygrave/ebean-insight:<version>
          ports:
            - name: http
              containerPort: 8091
          env:
            - name: DB_URL
              value: postgres.databases.svc.cluster.local:5432
            - name: DB_USER
              valueFrom: { secretKeyRef: { name: ebean-insight-secrets, key: DB_USER } }
            - name: DB_PASS
              valueFrom: { secretKeyRef: { name: ebean-insight-secrets, key: DB_PASS } }
            - name: INSIGHT_KEY
              valueFrom: { secretKeyRef: { name: ebean-insight-secrets, key: INSIGHT_KEY } }
          readinessProbe:
            httpGet: { path: /health, port: http }
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /health, port: http }
            initialDelaySeconds: 30
            periodSeconds: 30
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits:   { cpu: "1",  memory: 512Mi }

---
apiVersion: v1
kind: Service
metadata:
  name: ebean-insight
  namespace: ebean-insight
spec:
  selector:
    app: ebean-insight
  ports:
    - name: http
      port: 8091
      targetPort: http
```

Apply:

```bash
kubectl apply -f ebean-insight.yaml
kubectl -n ebean-insight rollout status deploy/ebean-insight
```

### Verify

```bash
kubectl -n ebean-insight port-forward svc/ebean-insight 8091:8091
curl -H "Insight-Key: change-me-long-random-string" http://localhost:8091/v1/envs
```

### Ingress (optional)

If you expose the server outside the cluster, terminate TLS at your ingress
controller and keep the `Insight-Key` header **secret** — anyone holding it can
read metrics and request query-plan captures. A minimal ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ebean-insight
  namespace: ebean-insight
spec:
  ingressClassName: nginx
  rules:
    - host: insight.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ebean-insight
                port: { number: 8091 }
  tls:
    - hosts: [insight.example.com]
      secretName: ebean-insight-tls
```

### RBAC for CLI users (port-forward auth)

The [`insight` CLI](install-cli.md) reaches the server via either a static
`--url` (Ingress) or — by default — a supervised `kubectl port-forward` that
reuses your cluster RBAC as auth (no `Insight-Key` needed). For the
port-forward path, each operator needs at minimum:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: ebean-insight-portforward
  namespace: ebean-insight
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get, list]
  - apiGroups: [""]
    resources: [pods/portforward]
    verbs: [create]
  - apiGroups: [""]
    resources: [services]
    verbs: [get, list]
```

Bind via `RoleBinding` to the user/group/ServiceAccount that runs `insight`.

### Postgres

The server requires PostgreSQL when running in *persist* mode (the default).
Use any reachable instance — managed (RDS/Aurora, Cloud SQL) or in-cluster.
Schema migrations run at startup via `io.ebean.migration` and are idempotent.

To skip Postgres entirely, run in *forward-only* mode — see
[`deployment-modes.md`](deployment-modes.md).

---

## Docker / docker-compose

### Persist mode (with Postgres)

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: ebean_insight
      POSTGRES_PASSWORD: ebean_insight
      POSTGRES_DB: ebean_insight
    ports: ["9432:5432"]

  insight:
    image: docker.io/rbygrave/ebean-insight:<version>
    depends_on: [postgres]
    environment:
      DB_URL: postgres:5432
      DB_USER: ebean_insight
      DB_PASS: ebean_insight
      INSIGHT_KEY: change-me
    ports: ["8091:8091"]
```

```bash
docker compose up -d
curl -H "Insight-Key: change-me" http://localhost:8091/v1/envs
```

### Forward-only mode (no Postgres)

```bash
docker run --rm -p 8091:8091 \
  -e METRICS_STORE_ENABLED=false \
  -e PLANS_STORE_ENABLED=false \
  -e FORWARD_OTEL_ENABLED=true \
  -e FORWARD_OTEL_ENDPOINT=http://alloy:4318 \
  -e INSIGHT_KEY=change-me \
  rbygrave/ebean-insight:<version>
```

See [`deployment-modes.md`](deployment-modes.md) for the full forwarder-tuning
matrix and expected startup logs.

---

## Standalone native binary

For tagged releases, the Linux x86\_64 server native binary is attached to
the corresponding [GitHub Release](https://github.com/ebean-orm/ebean-insight-server/releases)
as `ebean-insight-server-<version>-linux-x64.zip` (with a matching
`.sha256`). For other platforms, build the native image locally:

```bash
# Option A — download the linux-x64 binary from the latest Release
curl -L -o server.zip \
  https://github.com/ebean-orm/ebean-insight-server/releases/latest/download/ebean-insight-server-<version>-linux-x64.zip
unzip server.zip
chmod +x ebean-insight-server-<version>-linux-x64/ebean-insight

# Option B — build from source (requires GraalVM JDK on JAVA_HOME)
# e.g. via SDKMAN: sdk install java 24-graal
git clone https://github.com/ebean-orm/ebean-insight-server.git
cd ebean-insight-server
mvn -pl server -am -Pnative,linux -DskipTests package   # or -Pnative,mac on macOS
./server/target/ebean-insight -Dprops.file=server/application.yaml
```

A simple `systemd` unit on Linux:

```ini
# /etc/systemd/system/ebean-insight.service
[Unit]
Description=ebean-insight server
After=network-online.target

[Service]
Type=simple
User=insight
EnvironmentFile=/etc/ebean-insight/env
ExecStart=/opt/ebean-insight/ebean-insight -Dprops.file=/etc/ebean-insight/application.yaml
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```
# /etc/ebean-insight/env
DB_URL=localhost:5432
DB_USER=ebean_insight
DB_PASS=ebean_insight
INSIGHT_KEY=change-me
```

---

## Next steps

- **Mode/config reference** — [`deployment-modes.md`](deployment-modes.md)
- **CLI install** — [`install-cli.md`](install-cli.md)
- **/v1 API reference** — [top-level README](../README.md#v1-api-agent--cli--tooling)
- **Verify with a real OTLP stack** — [`verify/README.md`](../verify/README.md)
