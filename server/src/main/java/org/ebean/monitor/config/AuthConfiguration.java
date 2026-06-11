package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.RequiresProperty;
import io.avaje.jex.spi.JexPlugin;
import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.jex.jwtfilter.JwtAuthFilter;
import org.ebean.monitor.web.ApiKeyValidator;

/**
 * Wires JWT bearer authentication for the server.
 * <p>
 * All beans here are gated on {@code insight.auth.enabled=true}. When auth is
 * disabled (the default) no beans are produced, no filter is registered and the
 * server behaves exactly as before — all endpoints remain open.
 * <p>
 * When enabled, every request must present a valid {@code Authorization: Bearer}
 * JWT access token <em>except</em> the permitted prefixes:
 * <ul>
 *   <li>{@code /health} — Kubernetes liveness/readiness probes</li>
 *   <li>{@code /api/ingest} — app forwarders authenticated via the Insight-Key header</li>
 * </ul>
 * As an alternative to a JWT, a request may present a shared-secret API key as
 * {@code Authorization: Bearer <key>} when {@code insight.api.key} is configured
 * (see {@link ApiKeyValidator}); this is wired via the filter's bearerAuthoriser
 * hook and is what the CLI and MCP server use.
 * <p>
 * Note this locks the browser UI until a UI login flow exists.
 */
@Factory
@RequiresProperty(value = "insight.auth.enabled", equalTo = "true")
class AuthConfiguration {

  /**
   * JwtVerifier built from the configured Cognito issuer. The issuer drives
   * remote JWKS discovery at {@code <issuer>/.well-known/jwks.json}.
   */
  @Bean
  JwtVerifier jwtVerifier() {
    String issuer = Config.get("insight.auth.issuer");
    return JwtVerifier.builder()
      .issuer(issuer)
      .build();
  }

  /**
   * The Jex auth filter permitting health probes and Insight-Key ingestion.
   * <p>
   * When {@code insight.api.key} is configured the {@link ApiKeyValidator} is
   * supplied as the bearerAuthoriser, so a matching {@code Authorization: Bearer}
   * API key authenticates the request and JWT verification is skipped. When no
   * API key is configured the hook is left unset and behaviour is JWT-only.
   */
  @Bean
  JwtAuthFilter jwtAuthFilter(JwtVerifier jwtVerifier, ApiKeyValidator apiKeyValidator) {
    return JwtAuthFilter.builder()
      .permit("/health")
      .permit("/api/ingest")
      .verifier(jwtVerifier)
      .bearerAuthoriser(apiKeyValidator.enabled() ? apiKeyValidator::principalFor : null)
      .build();
  }

  /**
   * Registers the auth filter with Jex. {@code HttpFilter} beans are not
   * auto-collected by {@code configureWith}, so a JexPlugin performs the
   * registration.
   */
  @Bean
  JexPlugin authFilterPlugin(JwtAuthFilter jwtAuthFilter) {
    return jex -> jex.filter(jwtAuthFilter);
  }
}
