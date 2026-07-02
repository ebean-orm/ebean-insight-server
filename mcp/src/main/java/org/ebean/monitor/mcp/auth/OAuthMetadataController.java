package org.ebean.monitor.mcp.auth;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.jex.http.Context;

import java.util.Optional;

/**
 * Serves the OAuth 2.0 Protected Resource Metadata document (RFC 9728) at
 * {@code GET /.well-known/oauth-protected-resource}.
 * <p>
 * When {@link BearerAuthFilter} is configured with a JWT verifier it adds a
 * {@code WWW-Authenticate} header to 401 responses with a
 * {@code resource_metadata} pointer to this endpoint. Compliant MCP clients
 * (e.g. Copilot CLI) fetch it to discover which authorization server (Cognito /
 * Entra) they should use for the PKCE login flow.
 * <p>
 * The {@code /.well-known} path prefix is on the bearer-auth filter's permit
 * list, so this endpoint is always publicly readable — even before a token is
 * obtained.
 * <p>
 * Returns {@code 404} when {@code mcp.auth.issuer} is not configured (OAuth2
 * not in use).
 */
@Controller
@Path("/.well-known")
public class OAuthMetadataController {

  private final McpOAuthConfig oauthConfig;

  public OAuthMetadataController(McpOAuthConfig oauthConfig) {
    this.oauthConfig = oauthConfig;
  }

  /**
   * RFC 9728 protected resource metadata.
   * <p>
   * Fields:
   * <ul>
   *   <li>{@code resource} — canonical MCP endpoint URL (derived from request host)</li>
   *   <li>{@code authorization_servers} — the OIDC issuer from {@link McpOAuthConfig}</li>
   *   <li>{@code bearer_methods_supported} — always {@code ["header"]}</li>
   *   <li>{@code scopes_supported} — always {@code ["openid"]}</li>
   *   <li>{@code client_id} — optional PKCE client ID hint; included when configured
   *       so clients can skip Dynamic Client Registration</li>
   * </ul>
   */
  @Get("/oauth-protected-resource")
  void oauthProtectedResource(Context ctx) {
    if (!oauthConfig.enabled()) {
      ctx.writeEmpty(404);
      return;
    }
    // X-Forwarded-Proto is set by the ingress when TLS is terminated upstream;
    // fall back to the raw connection scheme for direct/local access.
    String scheme = Optional.ofNullable(ctx.header("X-Forwarded-Proto")).orElse(ctx.scheme());
    String resource = scheme + "://" + ctx.host() + "/mcp";

    var sb = new StringBuilder(256);
    sb.append("{\"resource\":\"").append(resource).append('"');
    sb.append(",\"authorization_servers\":[\"").append(oauthConfig.issuer()).append("\"]");
    sb.append(",\"bearer_methods_supported\":[\"header\"]");
    sb.append(",\"scopes_supported\":[\"openid\"]");
    if (oauthConfig.clientId() != null && !oauthConfig.clientId().isBlank()) {
      sb.append(",\"client_id\":\"").append(oauthConfig.clientId()).append('"');
    }
    sb.append('}');
    ctx.status(200).contentType("application/json");
    ctx.write(sb.toString());
  }
}
