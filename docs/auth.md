# Authentication (JWT bearer)

By default the ebean-insight server is **unauthenticated** — every endpoint is
open. Access is instead controlled by the network (an internal-only Ingress /
VPC ALB) plus the `Insight-Key` header on the ingest path, and — for the
[`insight` CLI](install-cli.md) — Kubernetes RBAC via `kubectl port-forward`.

The server can optionally enforce **OAuth2 JWT bearer authentication** on its
HTTP endpoints. This validates an `Authorization: Bearer <token>` access token
(issued by an OIDC provider such as AWS Cognito or Microsoft Entra ID) on
every request, except a small permit-list.

> Built on [`avaje-oauth2-jex-jwtfilter`](https://github.com/avaje/avaje-oauth2).
> The filter verifies the token **issuer, expiry and signature** (via the
> issuer's JWKS). It does **not** currently check audience or scope — it is
> authentication, not fine-grained authorization.

---

The browser UI also supports an independent BFF-style OAuth login. It uses a
server-side session and an opaque cookie; access, refresh, and ID tokens never
leave the server or appear in browser cookies. This UI session is separate from
the `/v1` bearer-token API authentication described below.

## Enforcement model

When enabled, **every** request must present a valid bearer token **except**
these permitted path prefixes, which stay open:

| Permitted prefix | Why |
|------------------|-----|
| `/health`        | Kubernetes liveness/readiness probes must not require a token. |
| `/api/ingest`    | App forwarders authenticate with the `Insight-Key` header (see [Ingest key](#ingest-shared-secret-insight-key)), not a bearer token. |
| `/api/cli-config` | Public OAuth2 client settings fetched by `insight setup` before a token exists (see [CLI bootstrap config](#cli-bootstrap-config-apicli-config)). |
| `/auth` | Browser OAuth login, callback, and logout endpoints. |
| `/ux` and `/static` | Reserved for the separate UI session filter and public static assets. |

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

Configure the values the server returns via (Cognito):

```yaml
insight:
  cli:
    auth:
      domain: "https://my-app.auth.ap-southeast-2.amazoncognito.com"
      client-id: "<public-cognito-app-client-id>"
      scope: "openid"
```

Or for Microsoft Entra ID, set `tenant-id` instead of `domain` — the CLI derives
the v2.0 authorize/token endpoints from the tenant id:

```yaml
insight:
  cli:
    auth:
      tenant-id: "<entra-tenant-id>"
      client-id: "<entra-app-client-id>"
      scope: "api://<entra-app-client-id>/access_as_user"
```

Or via environment variables:

```
INSIGHT_CLI_AUTH_DOMAIN=https://my-app.auth.ap-southeast-2.amazoncognito.com
INSIGHT_CLI_AUTH_CLIENTID=<public-cognito-app-client-id>
INSIGHT_CLI_AUTH_SCOPE=openid
```

```
INSIGHT_CLI_AUTH_TENANTID=<entra-tenant-id>
INSIGHT_CLI_AUTH_CLIENTID=<entra-app-client-id>
INSIGHT_CLI_AUTH_SCOPE=api://<entra-app-client-id>/access_as_user
```

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `insight.cli.auth.domain` | `INSIGHT_CLI_AUTH_DOMAIN` | `""` | Cognito Hosted-UI domain. Not used for Entra. |
| `insight.cli.auth.tenant-id` | `INSIGHT_CLI_AUTH_TENANTID` | `""` | Microsoft Entra ID tenant id. When set, the CLI logs in against Entra's v2.0 endpoints instead of Cognito. |
| `insight.cli.auth.client-id` | `INSIGHT_CLI_AUTH_CLIENTID` | `""` | Public PKCE app client id (no secret). |
| `insight.cli.auth.scope` | `INSIGHT_CLI_AUTH_SCOPE` | `openid` | Requested OAuth2 scope(s). For Entra, requesting only `openid`/`profile` will **not** yield a JWT access token verifiable against the tenant's JWKS — the app registration must expose an API and this must include that scope, e.g. `api://<clientId>/access_as_user`. |

> These are **not secrets** — a public OAuth2 client id, Cognito Hosted-UI
> domain, or Entra tenant id are all visible in every OAuth2 redirect URL. Safe
> to expose from an unauthenticated endpoint.

> Entra ID only supports the PKCE (browser loopback) login flow here, not
> device code — `insight login --device` is rejected when `tenant-id` is
> configured.

Fields that are unset (blank) are returned as `null` in the JSON response; the CLI
will skip writing those keys and the user can set them manually with
`insight config set`.

---

## Configuration

Three properties, all under `insight.auth`:

```yaml
insight:
  auth:
    enabled: true
    # OIDC issuer. JWKS defaults to <issuer>/.well-known/jwks.json unless jwks-uri is set below.
    issuer: ""
    # Optional explicit JWKS URI override — required for providers (e.g. Entra ID)
    # whose JWKS endpoint does not live at <issuer>/.well-known/jwks.json.
    jwks-uri: ""
```

Or via environment variables:

```
INSIGHT_AUTH_ENABLED=true
INSIGHT_AUTH_ISSUER=https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123
```

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `insight.auth.enabled` | `INSIGHT_AUTH_ENABLED` | `false` | When `false` no auth beans are created and the server behaves exactly as before. |
| `insight.auth.issuer`  | `INSIGHT_AUTH_ISSUER`  | `""`    | Required when enabled. The token's `iss` claim must match. JWKS keys default to `<issuer>/.well-known/jwks.json` unless `jwks-uri` is set. |
| `insight.auth.jwks-uri` | `INSIGHT_AUTH_JWKSURI` | `""` | Optional explicit JWKS URI, overriding the `<issuer>/.well-known/jwks.json` default. Required for Microsoft Entra ID (see below). |

### Cognito issuer format

For an AWS Cognito user pool the issuer is:

```
https://cognito-idp.<region>.amazonaws.com/<userPoolId>
```

The server fetches the signing keys from
`https://cognito-idp.<region>.amazonaws.com/<userPoolId>/.well-known/jwks.json`
at startup (and refreshes on key rotation), so the server needs outbound network
access to that endpoint. No `jwks-uri` override is needed for Cognito.

### Microsoft Entra ID issuer format

For a Microsoft Entra ID (Azure AD) tenant, the v2.0 issuer is:

```
https://login.microsoftonline.com/<tenantId>/v2.0
```

Unlike Cognito, Entra's JWKS endpoint does **not** live at
`<issuer>/.well-known/jwks.json` — it is at a separate `discovery/v2.0/keys`
path, so `insight.auth.jwks-uri` **must** be set explicitly:

```yaml
insight:
  auth:
    enabled: false           # master switch — default OFF
    issuer: "https://login.microsoftonline.com/<tenantId>/v2.0"
    jwks-uri: "https://login.microsoftonline.com/<tenantId>/discovery/v2.0/keys"
```

```
INSIGHT_AUTH_ENABLED=true
INSIGHT_AUTH_ISSUER=https://login.microsoftonline.com/<tenantId>/v2.0
INSIGHT_AUTH_JWKSURI=https://login.microsoftonline.com/<tenantId>/discovery/v2.0/keys
```

The MCP server has its own equivalent `mcp.auth.jwks-uri` / `MCP_AUTH_JWKSURI`
property alongside its existing `mcp.auth.issuer` / `mcp.auth.client-id`.

---

## Enabling on Kubernetes

Add the settings to the workload's environment, e.g. in your manifest / Helm
values (Cognito):

```yaml
env:
  - name: INSIGHT_AUTH_ENABLED
    value: "true"
  - name: INSIGHT_AUTH_ISSUER
    value: "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_AbCdEf123"
```

Or for Microsoft Entra ID (note the extra `INSIGHT_AUTH_JWKSURI`):

```yaml
env:
  - name: INSIGHT_AUTH_ENABLED
    value: "true"
  - name: INSIGHT_AUTH_ISSUER
    value: "https://login.microsoftonline.com/<tenantId>/v2.0"
  - name: INSIGHT_AUTH_JWKSURI
    value: "https://login.microsoftonline.com/<tenantId>/discovery/v2.0/keys"
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

## Browser UI sessions (`/ux`)

Enable the first-party Authorization Code + PKCE browser flow independently of
`insight.auth.enabled`:

```yaml
insight:
  ui:
    auth:
      enabled: false # default; set true to protect /ux
      client-id: "<public-client-id>"
      # Cognito:
      user-pool-id: "ap-southeast-2_<userPoolId>"
      domain: "https://my-app.auth.ap-southeast-2.amazoncognito.com"
      # Or set issuer explicitly instead of user-pool-id:
      # issuer: "https://cognito-idp.ap-southeast-2.amazonaws.com/<userPoolId>"
      # Optional explicit JWKS URI:
      # jwks-uri: "https://.../.well-known/jwks.json"
      # Entra instead uses tenant-id (and may optionally set domain):
      # tenant-id: "<tenant-id>"
      scope: "openid profile email"
      redirect-uri: "https://insight.example.com/auth/callback"
      cookie-secure: true
      persistent-store: true
      # Base64-encoded 32-byte AES key; required with persistent-store.
      token-encryption-key: "<base64-encoded-32-byte-key>"
```

The `domain`, `tenant-id`, `client-id`, and `scope` values fall back to the
corresponding `insight.cli.auth.*` values, so existing CLI-style configuration
can be reused. `redirect-uri` should be the exact callback URL registered with
the provider. A client secret may be supplied as
`insight.ui.auth.client-secret` for a confidential client; PKCE is still used.

For a Cognito deployment, configure `user-pool-id`, `domain`, `client-id`, and
the exact callback URL. For Entra, configure `tenant-id`, `client-id`, and an
API scope exposed by the app registration (for example
`api://<clientId>/access_as_user`); `openid profile` alone does not produce the
JWT access token required by the server.

The corresponding environment variables use the `INSIGHT_UI_AUTH_` prefix,
for example `INSIGHT_UI_AUTH_ENABLED`, `INSIGHT_UI_AUTH_CLIENTID`,
`INSIGHT_UI_AUTH_REDIRECTURI`, and
`INSIGHT_UI_AUTH_TOKENENCRYPTIONKEY`.

When enabled, `/ux` and `/ux/top/data` require the UI session. HTML requests
redirect to `/auth/login`; the chart-data endpoint returns `401` so browser
JavaScript can handle the unauthenticated state. `/static` remains public.
`/auth/login` and `/auth/callback` are the login endpoints. Logout is performed
with `POST /auth/logout`.
The session cookie is `HttpOnly`, `SameSite=Lax`, `Path=/`, and Secure in
production by default (or whenever `cookie-secure` is true). Secure cookies use
the `__Host-` prefix. Logout removes the server-side session and expires the
cookie.

Persistent storage is enabled by default when UI auth is enabled. It stores
sessions and login transactions in the Insight database, hashes opaque session
and state identifiers, and encrypts OAuth secrets with AES-GCM. The key must be
stable across restarts and replicas. Set `persistent-store: false` for local
development to use the in-memory stores. Return paths are restricted to local
absolute paths to prevent open redirects. Access tokens are refreshed
server-side near expiry when a refresh token is available.
