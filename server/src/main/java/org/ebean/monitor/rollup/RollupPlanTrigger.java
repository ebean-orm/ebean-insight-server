package org.ebean.monitor.rollup;

import io.avaje.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.web.V1QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Rollup-triggered automatic query-plan capture.
 *
 * <p>Called by {@link RollupService} after each M1 rollup. Evaluates rules
 * against freshly-rolled-up data and pushes capture requests into the delivery
 * queue (via {@link V1QueryService#autoPushCapture}) so the next forwarder poll
 * collects the plans.
 *
 * <h3>Rule: never-captured (every M10 rollup)</h3>
 * <p>Plan-capable metrics that had activity in the last 10 minutes and have
 * <em>never</em> had a query plan captured are automatically requested.
 *
 * <h3>Rule: stale plan (every M60 rollup)</h3>
 * <p>Plan-capable metrics that had activity in the last hour and whose most
 * recent captured plan is older than {@code autoplan.rollup.stalePlanDays}
 * days are automatically re-requested.
 *
 * <h3>Rule: regression (every M60 rollup)</h3>
 * <p>Plan-capable metrics whose mean execution time over the last hour has
 * increased by at least {@code autoplan.rollup.regressionRatio} times compared
 * to their mean over the prior {@code autoplan.rollup.regressionBaselineDays}
 * days are requested. Requires a minimum baseline mean of
 * {@code autoplan.rollup.regressionMinMicros} to filter noise from very fast queries.
 *
 * <p>All three rules share a per-(app, hash) cooldown to prevent duplicate requests.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code autoplan.rollup.enabled} — master switch, default {@code false}</li>
 *   <li>{@code autoplan.rollup.neverCapturedLimit} — max metrics per M10 sweep, default 20</li>
 *   <li>{@code autoplan.rollup.stalePlanLimit} — max metrics per M60 sweep, default 10</li>
 *   <li>{@code autoplan.rollup.stalePlanDays} — plan age threshold in days, default 7</li>
 *   <li>{@code autoplan.rollup.regressionLimit} — max metrics per M60 sweep, default 10</li>
 *   <li>{@code autoplan.rollup.regressionRatioPct} — mean ratio × 100 (e.g. 150 = 1.5×), default 150</li>
 *   <li>{@code autoplan.rollup.regressionMinMicros} — min baseline mean to avoid noise, default 10000</li>
 *   <li>{@code autoplan.rollup.regressionMinCount} — min calls in each window, default 5</li>
 *   <li>{@code autoplan.rollup.regressionBaselineDays} — prior-window length in days, default 7</li>
 *   <li>{@code autoplan.rollup.cooldownMinutes} — per-(app,hash) re-request cooldown, default 180</li>
 * </ul>
 */
@Singleton
public final class RollupPlanTrigger {

  private static final Logger log = LoggerFactory.getLogger(RollupPlanTrigger.class);

  private final boolean enabled;
  private final int neverCapturedLimit;
  private final int stalePlanLimit;
  private final int regressionLimit;
  private final long cooldownMillis;

  private final IntFunction<List<MissingPlanMetric>> neverCapturedQuery;
  private final IntFunction<List<MissingPlanMetric>> stalePlanQuery;
  private final IntFunction<List<RegressionPlanMetric>> regressionQuery;
  private final CaptureRequester captureRequester;
  private final LongSupplier clock;

  private final ConcurrentMap<String, Long> recentlyRequested = new ConcurrentHashMap<>();

  @Inject
  public RollupPlanTrigger(V1QueryService queryService) {
    this.clock = System::currentTimeMillis;
    this.enabled = Config.getBool("autoplan.rollup.enabled", false);
    this.neverCapturedLimit = Config.getInt("autoplan.rollup.neverCapturedLimit", 20);
    this.stalePlanLimit = Config.getInt("autoplan.rollup.stalePlanLimit", 10);
    this.regressionLimit = Config.getInt("autoplan.rollup.regressionLimit", 10);
    var cooldownMinutes = Config.getLong("autoplan.rollup.cooldownMinutes", 180);
    this.cooldownMillis = cooldownMinutes * 60_000L;
    final long stalePlanDays = Config.getLong("autoplan.rollup.stalePlanDays", 7);
    final long regressionRatioPct = Config.getLong("autoplan.rollup.regressionRatioPct", 150); // 150 = 1.5x
    final long regressionMinMicros = Config.getLong("autoplan.rollup.regressionMinMicros", 10_000);
    final int regressionMinCount = Config.getInt("autoplan.rollup.regressionMinCount", 5);
    final int regressionBaselineDays = Config.getInt("autoplan.rollup.regressionBaselineDays", 7);
    this.neverCapturedQuery = limit -> queryService.topMissingPlans("total", 10L, null, null, null, limit, null);
    this.stalePlanQuery = limit -> queryService.topMissingPlans("total", null, 1L, stalePlanDays * 24 * 60, null, limit, null);
    this.regressionQuery = limit -> queryService.topRegressionPlans(1, regressionBaselineDays, regressionRatioPct / 100.0, regressionMinMicros, regressionMinCount, limit);
    this.captureRequester = queryService::autoPushCapture;
    if (enabled) {
      log.info("rollup autoplan enabled neverCapturedLimit={} stalePlanLimit={} stalePlanDays={} " +
          "regressionLimit={} regressionRatioPct={} regressionMinMicros={} cooldownMinutes={}",
        neverCapturedLimit, stalePlanLimit, stalePlanDays,
        regressionLimit, regressionRatioPct, regressionMinMicros, cooldownMinutes);
    }
  }

  /** Package-private constructor for testing with stubs. */
  RollupPlanTrigger(IntFunction<List<MissingPlanMetric>> neverCapturedQuery,
                    IntFunction<List<MissingPlanMetric>> stalePlanQuery,
                    IntFunction<List<RegressionPlanMetric>> regressionQuery,
                    CaptureRequester captureRequester,
                    LongSupplier clock) {
    this.neverCapturedQuery = neverCapturedQuery;
    this.stalePlanQuery = stalePlanQuery;
    this.regressionQuery = regressionQuery;
    this.captureRequester = captureRequester;
    this.clock = clock;
    this.enabled = Config.getBool("autoplan.rollup.enabled", false);
    this.neverCapturedLimit = Config.getInt("autoplan.rollup.neverCapturedLimit", 20);
    this.stalePlanLimit = Config.getInt("autoplan.rollup.stalePlanLimit", 10);
    this.regressionLimit = Config.getInt("autoplan.rollup.regressionLimit", 10);
    this.cooldownMillis = Config.getLong("autoplan.rollup.cooldownMinutes", 180) * 60_000L;
  }

  /**
   * Evaluate auto-capture rules for the given rollup event time.
   * Called by {@link RollupService} after each M1 rollup.
   */
  void onRollup(Instant eventTime) {
    if (!enabled) return;
    int minute = eventTime.atZone(ZoneOffset.UTC).getMinute();
    if (minute % 10 == 0) {
      triggerNeverCaptured();
      if (minute % 60 == 0) {
        triggerStalePlans();
        triggerRegressionPlans();
      }
    }
  }

  private void triggerNeverCaptured() {
    int pushed = pushMissingCandidates(neverCapturedQuery.apply(neverCapturedLimit));
    if (pushed > 0) {
      log.info("autoplan rollup: requested {} never-captured plan capture(s)", pushed);
    }
  }

  private void triggerStalePlans() {
    int pushed = pushMissingCandidates(stalePlanQuery.apply(stalePlanLimit));
    if (pushed > 0) {
      log.info("autoplan rollup: requested {} stale-plan recapture(s)", pushed);
    }
  }

  private void triggerRegressionPlans() {
    int pushed = pushRegressionCandidates(regressionQuery.apply(regressionLimit));
    if (pushed > 0) {
      log.info("autoplan rollup: requested {} regression-detected plan capture(s)", pushed);
    }
  }

  private int pushMissingCandidates(List<MissingPlanMetric> candidates) {
    if (candidates.isEmpty()) return 0;
    final long now = clock.getAsLong();
    int pushed = 0;
    for (MissingPlanMetric m : candidates) {
      if (tryPush(m.app(), m.key(), m.label(), now)) pushed++;
    }
    return pushed;
  }

  private int pushRegressionCandidates(List<RegressionPlanMetric> candidates) {
    if (candidates.isEmpty()) return 0;
    final long now = clock.getAsLong();
    int pushed = 0;
    for (RegressionPlanMetric m : candidates) {
      if (tryPush(m.app(), m.key(), m.label(), now)) {
        log.debug("autoplan rollup regression: app={} key={} recentMean={}us baselineMean={}us",
          m.app(), m.key(), m.recentMeanMicros(), m.baselineMeanMicros());
        pushed++;
      }
    }
    return pushed;
  }

  /** Checks cooldown and pushes the capture request. Returns true if the request was pushed. */
  private boolean tryPush(String app, String key, String label, long now) {
    final String cooldownKey = app + "|" + key;
    final Long prev = recentlyRequested.get(cooldownKey);
    if (prev != null && (now - prev) < cooldownMillis) return false;
    recentlyRequested.put(cooldownKey, now);
    captureRequester.request(app, key, label);
    return true;
  }

  /** Functional interface for pushing + recording a capture request. Kept narrow for testability. */
  @FunctionalInterface
  interface CaptureRequester {
    void request(String appName, String metricKey, String metricLabel);
  }
}
