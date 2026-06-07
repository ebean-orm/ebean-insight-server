# ebean-insight-forwarder

A small, dependency-light Java library that maintains a **stable local TCP
endpoint** to the `ebean-insight` server running inside Kubernetes, by
supervising a `kubectl port-forward` for you.

It exists so a CLI (or any client) can talk to the server **without ingress,
API keys, OAuth, or mTLS** — it simply reuses the cluster access the developer
already has (their `kubectl`/EKS credentials and RBAC). The tunnel *is* the
authentication.

## What problem it solves

A raw `kubectl port-forward` is fragile:

- It dies when the target pod is rolled, scaled, or evicted.
- It dies on transient network blips ("lost connection to pod").
- When it dies, the local port is gone and every client connection breaks.

`SupervisedForwarder` wraps that fragility. It pins **one** local port for its
whole lifetime and treats the underlying `kubectl` process as disposable:
whenever the forward drops, it transparently respawns a new one against a
freshly-resolved pod, **without changing the local URI**. Clients keep using the
same `http://127.0.0.1:<port>` and only ever notice a brief unavailability
window during a reconnect.

## Core concepts

| Type | Role |
|------|------|
| `Endpoint` | The seam a client depends on: `baseUri()`, `isReady()`, `awaitReady(Duration)`. |
| `StaticEndpoint` | Trivial `Endpoint` for a fixed URL (e.g. an ingress) — no forwarding. |
| `SupervisedForwarder` | An `Endpoint` that keeps a `kubectl port-forward` alive. Built via `SupervisedForwarder.builder()`. |
| `ForwardTarget` | What to forward to: `ForwardTarget.service(ns, name, port)` or `.deployment(...)`. |
| `ForwardEngine` | SPI that actually establishes one forward. Default is `KubectlForwardEngine`. |
| `KubectlForwardEngine` | Runs `kubectl ... port-forward svc/<name> <local>:<remote>` as a subprocess, parses readiness from stdout, and classifies drop/bind-conflict markers. |
| `ForwardState` / `ForwardStatus` | Lifecycle (`STARTING → READY → RECONNECTING → … → STOPPED/FAILED`) delivered to an optional `onStatus` listener. |
| `BackoffPolicy` / `ExponentialBackoff` | Full-jitter backoff between reconnect attempts. |
| `HealthCheck` / `TcpHealthCheck` | Optional readiness probe (TCP connect) on the local port. |

## Usage

```java
try (SupervisedForwarder fwd = SupervisedForwarder.builder()
        .target(ForwardTarget.service("dev-core", "central-insight", 8091))
        .onStatus(s -> log.debug("forward {}", s.state()))
        .build()) {

  URI base = fwd.start(Duration.ofSeconds(20));   // blocks until READY
  // base is e.g. http://127.0.0.1:61596 and stays stable for the lifetime
  // ... make HTTP calls against base ...
}   // close() tears down the kubectl child cleanly
```

The local port is pinned for the lifetime of the forwarder. If `localPort` is
`0` (the default) a free ephemeral port is chosen once at `start()`.

## How reconnection works

1. The supervisor starts a `kubectl port-forward` via the engine and waits for
   its `Forwarding from 127.0.0.1:<port> -> <remote>` line → state `READY`.
2. If the child exits or emits a drop marker (e.g. *lost connection to pod*,
   *error upgrading connection*), the supervisor moves to `RECONNECTING`,
   applies backoff, re-resolves the target, and starts a **new** child on the
   **same** local port.
3. A `BIND_CONFLICT` (local port already in use) causes a re-pick where
   applicable; a missing pod surfaces as `ForwardException.Kind.NO_POD`.
4. A non-retryable failure — `ForwardException.Kind.FATAL` — aborts supervision
   immediately rather than retrying for the whole ready-timeout window. The
   `KubectlForwardEngine` classifies kubectl's stderr on early exit: auth/config
   errors (expired credentials, *Unauthorized*, *getting credentials*, unknown
   `--context`) are fatal, while reachability errors (e.g. *unable to connect to
   the server*) stay retryable. On a fatal abort the engine surfaces kubectl's
   stderr tail (e.g. `kubectl exited (code 255): Token has expired …`) so the
   cause is obvious, the supervisor moves to `FAILED`, and `start(...)` returns
   in well under a second instead of after the full timeout.

A mid-session pod roll therefore looks like a ~1–3s blip to a client, after
which the same `baseUri()` works again — verified end-to-end against a live EKS
cluster.

## Design notes

- **GraalVM-friendly**: pure JDK only — `ProcessBuilder`, regex, virtual
  threads, `java.net` — no reflection, no fabric8/k8s client. This keeps it
  trivially native-image-compatible (the binary is produced by a consumer
  module such as `ebean-insight-cli`, which supplies the `main`).
- **Credentials flow for free**: the `kubectl` subprocess inherits the parent
  environment, so AWS/EKS exec credentials and `KUBECONFIG` just work.
- **No `pkill`**: children are reaped via `Process.destroy()` /
  `destroyForcibly()` on the specific process only.

This module is a **library** — it has no `main`. It is consumed by
`ebean-insight-cli`.

## Building / testing

```bash
mvn -pl forwarder test       # 13 unit tests (fake engine; no cluster needed)
```
