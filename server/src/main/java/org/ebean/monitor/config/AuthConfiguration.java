package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.RequiresProperty;
import io.avaje.jex.spi.JexPlugin;
import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.jex.jwtfilter.JwtAuthFilter;

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
   */
  @Bean
  JwtAuthFilter jwtAuthFilter(JwtVerifier jwtVerifier) {
    return JwtAuthFilter.builder()
      .permit("/health")
      .permit("/api/ingest")
      .verifier(jwtVerifier)
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
