# Authentication (JWT bearer)

By default the ebean-insight server is **unauthenticated** — every endpoint is
open. Access is instead controlled by the network (an internal-only Ingress /
VPC ALB) plus the `Insight-Key` header on the ingest path, and — for the
[`insight` CLI](install-cli.md) — Kubernetes RBAC via `kubectl port-forward`.

The server can optionally enforce **OAuth2 JWT bearer authentication** on its
HTTP endpoints. This validates an `Authorization: Bearer <token>` access token
(issued by an OIDC provider such as AWS Cognito) on every request, except a
small permit-list.

> Built on [`avaje-oauth2-jex-jwtfilter`](https://github.com/avaje/avaje-oauth2).
> The filter verifies the token **issuer, expiry and signature** (via the
> issuer's JWKS). It does **not** currently check audience or scope — it is
> authentication, not fine-grained authorization.

---

## Enforcement model

When enabled, **every** request must present a valid bearer token **except**
these permitted path prefixes, which stay open:

| Permitted prefix | Why |
|------------------|-----|
| `/health`        | Kubernetes liveness/readiness probes must not require a token. |
| `/api/ingest`    | App forwarders authenticate with the `Insight-Key` header (see [Ingest key](#ingest-shared-secret-insight-key)), not a bearer token. |
| `/api/cli-config` | Public OAuth2 client settings fetched by `insight setup` before a token exists (see [CLI bootstrap config](#cli-bootstrap-config-apicli-config)). |

Everything else is protected, including:

- `/v1/*` — the versioned API used by the `insight` CLI and tooling.

A request that is missing a token, or presents an invalid/expired one, on a
protected path receives **HTTP 401**.

---

## Ingest shared secret (`Insight-Key`)

The JWT filter deliberately **permits `/api/ingest`** because app forwarders are
not interactive OAuth2 clients. That path is instead authenticated by a shared
secret: every forwarder sends an `Insight-Key` header, validated directly in the
ingest controller (no filter).

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `insight.ingest.key` | `INSIGHT_INGEST_KEY` | `""` | When set, all `/api/ingest` requests (metrics, plans, ack) must present a matching `Insight-Key` header or receive **401**. When unset (default) ingestion stays **open** — protected only by network isolation, exactly as before. |

The value may be a **single key** or a **comma separated list** (`old,new`) to
support zero-downtime key rotation: configure both, roll forwarders onto the new
key, then drop the old one.

> This is independent of `insight.auth.enabled` — you can enforce the ingest key
> with or without JWT auth on the rest of the server.

---

## CLI bootstrap config (`/api/cli-config`)

`GET /api/cli-config` returns the public OAuth2 client settings the CLI needs to
authenticate. It is always unauthenticated (even when `insight.auth.enabled=true`)
because the CLI calls it before a token exists.

This powers `insight setup <url>`, which fetches these settings automatically so
users only need to provide the server URL:

```bash
insight setup https://central-insight.example.com   # one command bootstraps everything
```

Configure the values the server returns via:

```yaml
insight:
  cli:
    auth:
      domain: "https://my-app.auth.ap-southeast-2.amazoncognito.com"
      client-id: "<public-cognito-app-client-id>"
      scope: "openid"
```

Or via environment variables:

```
INSIGHT_CLI_AUTH_DOMAIN=https://my-app.auth.ap-southeast-2.amazoncognito.com
INSIGHT_CLI_AUTH_CLIENT_ID=<public-cognito-app-client-id>
INSIGHT_CLI_AUTH_SCOPE=openid
```

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `insight.cli.auth.domain` | `INSIGHT_CLI_AUTH_DOMAIN` | `""` | Cognito Hosted-UI domain. |
| `insight.cli.auth.client-id` | `INSIGHT_CLI_AUTH_CLIENT_ID` | `""` | Public PKCE app client id (no secret). |
| `insight.cli.auth.scope` | `INSIGHT_CLI_AUTH_SCOPE` | `openid` | Requested OAuth2 scope(s). |

> These are **not secrets** — a Cognito PKCE client id and Hosted-UI domain are
> visible in every OAuth2 redirect URL. Safe to expose from an unauthenticated
> endpoint.

Fields that are unset (blank) are returned as `null` in the JSON response; the CLI
will skip writing those keys and the user can set them manually with
`insight config set`.

---

## Configuration

Two properties, both under `insight.auth`:

```yaml
insight:
  auth:
    enabled: false           # master switch — default OFF
    # OIDC issuer. JWKS is discovered at <issuer>/.well-known/jwks.json
    issuer: ""
```

Or via environment variables:

```
INSIGHT_AUTH_ENABLED=true
INSIGHT_AUTH_ISSUER=https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123
```

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `insight.auth.enabled` | `INSIGHT_AUTH_ENABLED` | `false` | When `false` no auth beans are created and the server behaves exactly as before. |
| `insight.auth.issuer`  | `INSIGHT_AUTH_ISSUER`  | `""`    | Required when enabled. The token's `iss` claim must match. JWKS keys are fetched from `<issuer>/.well-known/jwks.json`. |

### Cognito issuer format

For an AWS Cognito user pool the issuer is:

```
https://cognito-idp.<region>.amazonaws.com/<userPoolId>
```

The server fetches the signing keys from
`https://cognito-idp.<region>.amazonaws.com/<userPoolId>/.well-known/jwks.json`
at startup (and refreshes on key rotation), so the server needs outbound network
access to that endpoint.

---

## Enabling on Kubernetes

Add the two settings to the workload's environment, e.g. in your manifest /
Helm values:

```yaml
env:
  - name: INSIGHT_AUTH_ENABLED
    value: "true"
  - name: INSIGHT_AUTH_ISSUER
    value: "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123"
```

---

## Rollout sequencing (important)

Enabling enforcement **immediately requires a bearer token from every client**,
including `insight` CLI users who connect via `kubectl port-forward`. Turning it
on before clients can obtain a token will lock them out.

Recommended order:

1. **Configure the CLI bootstrap endpoint** so operators can self-configure.
   Set `insight.cli.auth.domain`, `insight.cli.auth.client-id`, and
   `insight.cli.auth.scope` on the server (see
   [CLI bootstrap config](#cli-bootstrap-config-apicli-config)). Then each
   operator runs one command:
   ```bash
   insight setup https://<insight-host>   # fetches auth config + opens browser login
   ```
2. Enable on a **non-production** environment first
   (`INSIGHT_AUTH_ENABLED=true`) and validate:
   - probes stay green (`/health/*` permitted),
   - app ingestion keeps flowing (`/api/ingest` permitted, `Insight-Key`),
   - CLI calls succeed with a token and return `401` without one.
3. Promote to the remaining environments.

Because the default is OFF, you can deploy the auth-capable server everywhere
first and flip the flag per environment once login is in place.

---

## Verifying

With auth enabled:

```bash
# Health is permitted (no token) — expect 200
curl -fsS https://<host>/health/liveness

# Protected endpoint without a token — expect 401
curl -s -o /dev/null -w '%{http_code}\n' https://<host>/v1/apps

# Protected endpoint with a valid token — expect 200
curl -fsS -H "Authorization: Bearer $TOKEN" https://<host>/v1/apps

# Ingest is permitted via Insight-Key (no bearer) — unchanged
curl -fsS -H "Insight-Key: <key>" ... https://<host>/api/ingest
```

A `401` on a protected path with no/invalid token, and a `200` on `/health` and
with a valid token, confirms enforcement is active.
