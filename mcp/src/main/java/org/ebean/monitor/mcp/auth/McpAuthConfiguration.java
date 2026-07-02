package org.ebean.monitor.mcp.auth;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.spi.JexPlugin;
import io.avaje.oauth2.core.jwt.JwtVerifier;

/**
 * Wires inbound MCP client authentication.
 * <p>
 * Produces:
 * <ul>
 *   <li>{@link TokenStore} — static bearer token auth from {@code mcp.tokens}</li>
 *   <li>{@link McpOAuthConfig} — OIDC issuer + client-id hint, consumed by
 *       {@link OAuthMetadataController}</li>
 *   <li>A {@link JexPlugin} that registers {@link BearerAuthFilter} with Jex,
 *       permitting {@code /health} (k8s probes) and {@code /.well-known}
 *       (OAuth2 discovery)</li>
 * </ul>
 * When neither tokens nor an issuer is configured the filter is a no-op (open server).
 */
@Factory
final class McpAuthConfiguration {

  @Bean
  TokenStore tokenStore() {
    return new TokenStore(Config.getNullable("mcp.tokens"));
  }

  @Bean
  McpOAuthConfig mcpOAuthConfig() {
    return new McpOAuthConfig(
        Config.getNullable("mcp.auth.issuer"),
        Config.getNullable("mcp.auth.client-id"));
  }

  @Bean
  JexPlugin mcpAuthPlugin(TokenStore tokenStore, McpOAuthConfig oauthConfig) {
    JwtVerifier verifier = oauthConfig.enabled()
        ? JwtVerifier.builder().issuer(oauthConfig.issuer()).build()
        : null;
    var filter = new BearerAuthFilter(tokenStore, verifier, "/health", "/.well-known");
    return jex -> jex.filter(filter);
  }
}
