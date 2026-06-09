package org.ebean.monitor.cli;

import org.jspecify.annotations.Nullable;

/**
 * Resolves command-level defaults (app, env) from {@code ~/.insight/config.properties}
 * when not supplied on the command line.
 *
 * <p>Connection settings live in {@link ConnectionOptions}; these are the
 * frequently-repeated query scoping options that users persist with
 * {@code insight config set app <app>} / {@code insight config set env <env>}.
 */
final class ConfigDefaults {

  private ConfigDefaults() {
  }

  @Nullable
  static String appOrNull() {
    return blankToNull(new InsightConfig().get("app"));
  }

  @Nullable
  static String envOrNull() {
    return blankToNull(new InsightConfig().get("env"));
  }

  @Nullable
  private static String blankToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
