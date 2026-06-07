package org.ebean.monitor.ingest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Surfaces apps that report metrics or query plans without an environment.
 *
 * <p>Such data is bucketed under {@code no-environment} by {@link ProcessHeader},
 * which is easy to miss. This warns once per app (per JVM lifetime) so the
 * misconfiguration can be spotted and fixed in the reporting app.
 */
@Singleton
public class MissingEnvWarner {

  private static final Logger log = LoggerFactory.getLogger(MissingEnvWarner.class);

  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  /**
   * Warn (once per app) when {@code environment} is missing.
   *
   * @return true if a warning was emitted, false when the environment is present
   *         or the app was already warned about.
   */
  public boolean check(String appName, String environment) {
    if (environment != null && !environment.isBlank()) {
      return false;
    }
    String app = (appName == null || appName.isBlank()) ? "<unknown>" : appName;
    if (warned.add(app)) {
      log.warn("App '{}' reported no environment - its data is bucketed as 'no-environment'. "
        + "Configure the app's ebean-insight client via the app.environment property or the "
        + "OTEL deployment.environment.name resource attribute.", app);
      return true;
    }
    return false;
  }
}
