package org.ebean.monitor.rollup;

/**
 * A plan-capable metric whose recent mean execution time has regressed
 * significantly compared to its historical baseline.
 *
 * @param app             application name
 * @param key             metric hash / key
 * @param label           human-readable label (coalesce(tags->>'label', name))
 * @param recentMeanMicros  mean execution time over the recent window (microseconds)
 * @param baselineMeanMicros mean execution time over the baseline window (microseconds)
 */
public record RegressionPlanMetric(
  String app,
  String key,
  String label,
  long recentMeanMicros,
  long baselineMeanMicros
) {}
