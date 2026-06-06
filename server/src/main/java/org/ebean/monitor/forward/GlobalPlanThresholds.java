package org.ebean.monitor.forward;

/**
 * Threshold resolver that always returns the global default. Used in
 * forward-only mode where no DB-backed per-query thresholds exist.
 */
public final class GlobalPlanThresholds implements PlanThresholds {

  private final long defaultMicros;

  public GlobalPlanThresholds(long defaultMicros) {
    this.defaultMicros = defaultMicros;
  }

  @Override
  public long thresholdMicros(String appName, String hash) {
    return defaultMicros;
  }
}
