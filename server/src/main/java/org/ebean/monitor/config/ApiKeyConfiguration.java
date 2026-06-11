package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import org.ebean.monitor.web.ApiKeyValidator;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@link ApiKeyValidator} from the {@code insight.api.key}
 * configuration.
 * <p>
 * The validator is always available so {@code AuthConfiguration} can inject it
 * unconditionally. When {@code insight.api.key} is unset (the default) the
 * validator is disabled and {@code /v1} is JWT-only when {@code insight.auth.enabled=true}
 * (and fully open when auth is disabled). When set (single key, or comma
 * separated list for rotation) {@code /v1} additionally accepts a matching
 * {@code Authorization: Bearer <key>}.
 * <p>
 * This is the read-side counterpart to {@link IngestKeyConfiguration}: a
 * distinct shared secret for {@code /v1} API access (CLI, MCP server), separate
 * from the ingest key used by metric forwarders.
 */
@Factory
final class ApiKeyConfiguration {

  @Bean
  ApiKeyValidator apiKeyValidator() {
    return new ApiKeyValidator(parseKeys(Config.getNullable("insight.api.key")));
  }

  private static List<String> parseKeys(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
      .map(String::trim)
      .filter(s -> !s.isEmpty())
      .distinct()
      .toList();
  }
}
