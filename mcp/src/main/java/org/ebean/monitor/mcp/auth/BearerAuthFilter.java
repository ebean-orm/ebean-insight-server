package org.ebean.monitor.mcp.auth;

import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter;
import io.avaje.jex.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jex filter enforcing inbound MCP client authentication.
 * <p>
 * Requests must present a matching {@code Authorization: Bearer <token>} (see
 * {@link TokenStore}) except for permitted path prefixes — {@code /health}
 * (Kubernetes liveness/readiness probes). On success the resolved principal
 * (token label) is bound to the {@code security.principal} context attribute;
 * otherwise the filter responds 401.
 * <p>
 * When the {@link TokenStore} is disabled (no {@code mcp.tokens} configured) the
 * filter is a no-op and every request proceeds — the server is open, protected
 * only by network isolation.
 */
final class BearerAuthFilter implements HttpFilter {

  static final String ATTR_PRINCIPAL = "security.principal";

  private static final Logger log = LoggerFactory.getLogger(BearerAuthFilter.class);
  private static final String BEARER_ = "Bearer ";
  private static final int BEARER_LENGTH = BEARER_.length();

  private final TokenStore tokenStore;
  private final String[] permittedPrefixes;

  BearerAuthFilter(TokenStore tokenStore, String... permittedPrefixes) {
    this.tokenStore = tokenStore;
    this.permittedPrefixes = permittedPrefixes;
  }

  @Override
  public void filter(Context ctx, FilterChain chain) {
    if (!tokenStore.enabled() || isPermitted(ctx.path())) {
      chain.proceed();
      return;
    }

    String header = ctx.header("Authorization");
    if (header != null && header.startsWith(BEARER_)) {
      String principal = tokenStore.principalFor(header.substring(BEARER_LENGTH));
      if (principal != null) {
        ctx.attribute(ATTR_PRINCIPAL, principal);
        log.debug("MCP request authenticated as {}", principal);
        chain.proceed();
        return;
      }
    }
    throw new HttpResponseException(401, "Unauthorized");
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
