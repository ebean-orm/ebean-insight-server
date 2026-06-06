package org.ebean.monitor.forward;

/**
 * Resolves the per-query auto-plan-capture threshold for a given (appName, hash).
 * Returns the global default when no per-query override is configured.
 */
public interface PlanThresholds {

  /**
   * Return the threshold (mean duration in micros) above which a plan capture
   * should be requested. Falls back to the global default when no override
   * exists for the given query.
   */
  long thresholdMicros(String appName, String hash);
}
