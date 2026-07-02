package org.ebean.monitor.mcp.auth;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.core.jwt.JwtVerifyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Jex filter enforcing inbound MCP client authentication.
 * <p>
 * Requests must present either a matching {@code Authorization: Bearer} static
 * token (see {@link TokenStore}) or a valid JWT issued by the configured OIDC
 * provider (see {@link JwtVerifier}), except for permitted path prefixes —
 * {@code /health} (Kubernetes probes) and {@code /.well-known} (OAuth2
 * discovery). On success the resolved principal is bound to the
 * {@code security.principal} context attribute; otherwise the filter responds 401.
 * <p>
 * Static tokens are checked first (constant-time, no network); JWT verification
 * is the fallback when {@code jwtVerifier} is configured. This lets service
 * accounts use static tokens while human operators authenticate via PKCE/SSO.
 * <p>
 * When neither static tokens nor a JWT verifier is configured the filter is a
 * no-op — the server is open, protected only by network isolation.
 * <p>
 * When JWT auth is configured, 401 responses include a {@code WWW-Authenticate}
 * header with a {@code resource_metadata} pointer (RFC 6750 §3 / RFC 9728) so
 * compliant MCP clients (e.g. Copilot CLI) can discover the authorization server
 * and perform the PKCE login flow automatically.
 */
final class BearerAuthFilter implements HttpFilter {

  static final String ATTR_PRINCIPAL = "security.principal";

  private static final Logger log = LoggerFactory.getLogger(BearerAuthFilter.class);
  private static final String BEARER_ = "Bearer ";
  private static final int BEARER_LENGTH = BEARER_.length();

  private final TokenStore tokenStore;
  private final JwtVerifier jwtVerifier;
  private final String[] permittedPrefixes;

  /** {@code jwtVerifier} may be {@code null} when JWT auth is not configured. */
  BearerAuthFilter(TokenStore tokenStore, JwtVerifier jwtVerifier, String... permittedPrefixes) {
    this.tokenStore = tokenStore;
    this.jwtVerifier = jwtVerifier;
    this.permittedPrefixes = permittedPrefixes;
  }

  @Override
  public void filter(Context ctx, FilterChain chain) {
    if (!isAuthEnabled() || isPermitted(ctx.path())) {
      chain.proceed();
      return;
    }

    String header = ctx.header("Authorization");
    if (header != null && header.startsWith(BEARER_)) {
      String token = header.substring(BEARER_LENGTH);
      // Static token checked first — cheap, no network required.
      String principal = tokenStore.principalFor(token);
      if (principal != null) {
        ctx.attribute(ATTR_PRINCIPAL, principal);
        log.debug("MCP request authenticated as {}", principal);
        chain.proceed();
        return;
      }
      // Fall through to JWT verification when an issuer is configured.
      if (jwtVerifier != null) {
        try {
          var accessToken = jwtVerifier.verifyAccessToken(token);
          ctx.attribute(ATTR_PRINCIPAL, accessToken.sub());
          log.debug("MCP request authenticated via JWT as {}", accessToken.sub());
          chain.proceed();
          return;
        } catch (JwtVerifyException e) {
          log.debug("JWT verification failed: {}", e.getMessage());
        }
      }
    }
    if (jwtVerifier != null) {
      // RFC 6750 §3 + RFC 9728: advertise the resource metadata URL so MCP
      // clients (e.g. Copilot CLI) can discover the authorization server and
      // perform the PKCE login flow automatically.
      // X-Forwarded-Proto is preferred over ctx.scheme() since the ingress
      // terminates TLS and the pod-level connection is plain HTTP.
      String scheme = Optional.ofNullable(ctx.header("X-Forwarded-Proto")).orElse(ctx.scheme());
      ctx.header("WWW-Authenticate",
          "Bearer realm=\"ebean-insight-mcp\", resource_metadata=\""
          + scheme + "://" + ctx.host() + "/.well-known/oauth-protected-resource\"");
    }
    throw new HttpResponseException(401, "Unauthorized");
  }

  /** Auth is enforced when at least one credential type is configured. */
  boolean isAuthEnabled() {
    return tokenStore.enabled() || jwtVerifier != null;
  }

  private boolean isPermitted(String path) {
    for (String prefix : permittedPrefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
