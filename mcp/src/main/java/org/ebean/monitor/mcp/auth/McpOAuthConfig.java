package org.ebean.monitor.mcp.auth;

/**
 * Holds the OAuth2 configuration for the MCP server, resolved once at startup
 * from avaje-config and injected into consumers (e.g. {@link OAuthMetadataController}).
 *
 * @param issuer   OIDC issuer URL ({@code mcp.auth.issuer}); {@code null} or blank
 *                 when JWT auth is not configured.
 * @param clientId Optional pre-registered PKCE client ID hint
 *                 ({@code mcp.auth.client-id}) to advertise in the discovery document.
 * @param jwksUri  Optional explicit JWKS uri ({@code mcp.auth.jwks-uri}) override for
 *                 issuers whose JWKS endpoint isn't {@code <issuer>/.well-known/jwks.json}
 *                 (e.g. Microsoft Entra ID).
 */
record McpOAuthConfig(String issuer, String clientId, String jwksUri) {

  /** True when an OIDC issuer is configured and JWT auth is in use. */
  boolean enabled() {
    return issuer != null && !issuer.isBlank();
  }
}
