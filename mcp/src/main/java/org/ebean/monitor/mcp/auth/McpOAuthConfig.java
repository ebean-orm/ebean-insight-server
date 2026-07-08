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
 * @param scope    OAuth scope(s) advertised as {@code scopes_supported}
 *                 ({@code mcp.auth.scope}), space-separated. Defaults to {@code openid}.
 *                 <p>
 *                 For providers (e.g. Microsoft Entra ID) that mint the access token's
 *                 audience/format from the requested scope, this <strong>must</strong> be
 *                 the resource's own exposed API scope (e.g.
 *                 {@code api://<client-id>/access_as_user}) — not the bare {@code openid}
 *                 default — otherwise the client may receive a token for a different
 *                 (default) audience whose issuer/version doesn't match {@link #issuer()},
 *                 causing {@link io.avaje.oauth2.core.jwt.JwtVerifier} to reject it.
 */
record McpOAuthConfig(String issuer, String clientId, String jwksUri, String scope) {

  /** True when an OIDC issuer is configured and JWT auth is in use. */
  boolean enabled() {
    return issuer != null && !issuer.isBlank();
  }
}
