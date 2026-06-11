package org.ebean.monitor.mcp.auth;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.spi.JexPlugin;

/**
 * Wires inbound MCP client authentication.
 * <p>
 * Builds the {@link TokenStore} from {@code mcp.tokens} and registers the
 * {@link BearerAuthFilter} with Jex (HttpFilter beans are not auto-collected, so
 * a {@link JexPlugin} performs the registration — same pattern as the server's
 * auth wiring). {@code /health} is permitted so Kubernetes probes work without a
 * token.
 * <p>
 * The filter is always registered; when {@code mcp.tokens} is unset the store is
 * disabled and the filter is a no-op (server open).
 */
@Factory
final class McpAuthConfiguration {

  @Bean
  TokenStore tokenStore() {
    return new TokenStore(Config.getNullable("mcp.tokens"));
  }

  @Bean
  JexPlugin mcpAuthPlugin(TokenStore tokenStore) {
    BearerAuthFilter filter = new BearerAuthFilter(tokenStore, "/health");
    return jex -> jex.filter(filter);
  }
}
