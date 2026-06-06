package org.ebean.monitor.forward;

import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;

/**
 * Selects the {@link PlanThresholds} implementation based on whether
 * metric storage is enabled. In storage mode we use {@link DbPlanThresholds}
 * which honours per-query overrides from {@code DAppMetric.planThresholdMicros}.
 * In forward-only mode no DB-backed overrides are available, so the global
 * default applies to every query.
 */
@Factory
class PlanThresholdsFactory {

  @Bean
  PlanThresholds planThresholds() {
    long defaultMicros = Config.getLong("autoplan.defaultThresholdMicros", 100_000);
    boolean storeEnabled = Config.getBool("metrics.store.enabled", true);
    if (!storeEnabled) {
      return new GlobalPlanThresholds(defaultMicros);
    }
    long ttlMillis = Config.getLong("autoplan.thresholdsCacheTtlMillis", 5 * 60_000L);
    return new DbPlanThresholds(defaultMicros, ttlMillis);
  }
}
