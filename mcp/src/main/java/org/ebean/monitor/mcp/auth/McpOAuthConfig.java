package org.ebean.monitor.mcp.auth;

/**
 * Holds the OAuth2 configuration for the MCP server, resolved once at startup
 * from avaje-config and injected into consumers (e.g. {@link OAuthMetadataController}).
 *
 * @param issuer   OIDC issuer URL ({@code mcp.auth.issuer}); {@code null} or blank
 *                 when JWT auth is not configured.
 * @param clientId Optional pre-registered PKCE client ID hint
 *                 ({@code mcp.auth.client-id}) to advertise in the discovery document.
 */
record McpOAuthConfig(String issuer, String clientId) {

  /** True when an OIDC issuer is configured and JWT auth is in use. */
  boolean enabled() {
    return issuer != null && !issuer.isBlank();
  }
}
