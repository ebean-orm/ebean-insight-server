package org.ebean.monitor.config;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import org.ebean.monitor.web.IngestKeyValidator;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@link IngestKeyValidator} from the {@code insight.ingest.key}
 * configuration.
 * <p>
 * The validator is always available so {@code IngestController} can inject it
 * unconditionally. When {@code insight.ingest.key} is unset (the default) the
 * validator is disabled and ingestion stays open — protected only by network
 * isolation. When set (single key, or comma separated list for rotation) ingest
 * requests must present a matching {@code Insight-Key} header or receive 401.
 * <p>
 * This is independent of {@code insight.auth.enabled}: the JWT auth filter
 * permits {@code /api/ingest} (forwarders are not interactive OAuth2 clients),
 * so this validator is what authenticates the ingest path.
 */
@Factory
final class IngestKeyConfiguration {

  @Bean
  IngestKeyValidator ingestKeyValidator() {
    return new IngestKeyValidator(parseKeys(Config.getNullable("insight.ingest.key")));
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
