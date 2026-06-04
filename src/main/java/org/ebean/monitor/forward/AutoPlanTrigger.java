package org.ebean.monitor.forward;

import io.avaje.config.Config;
import jakarta.inject.Singleton;
import org.ebean.monitor.api.MetricData;
import org.ebean.monitor.api.MetricDbData;
import org.ebean.monitor.api.MetricRequest;
import org.ebean.monitor.web.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Inspects each ingested {@link MetricRequest} and, when an SQL/orm query
 * metric exceeds its threshold, pushes a {@code qp:<hash>} request via
 * {@link MessageService} so the client captures the plan on its next collect.
 *
 * <p>Threshold per query may be overridden via {@code DAppMetric.planThresholdMicros}
 * (when storage is enabled). Otherwise the global
 * {@code autoplan.defaultThresholdMicros} applies.
 *
 * <p>A per-(app+env+hash) cooldown prevents repeated requests for the same
 * query within {@code autoplan.cooldownMinutes}.
 */
@Singleton
public final class AutoPlanTrigger {

  private static final Logger log = LoggerFactory.getLogger(AutoPlanTrigger.class);

  private final boolean enabled;
  private final long cooldownMillis;
  private final int maxTracked;
  private final MessageService messageService;
  private final PlanThresholds thresholds;
  private final LongSupplier clock;
  private final ConcurrentMap<Key, Long> requestedAt = new ConcurrentHashMap<>();

  @jakarta.inject.Inject
  public AutoPlanTrigger(MessageService messageService, PlanThresholds thresholds) {
    this(messageService, thresholds, System::currentTimeMillis);
  }

  AutoPlanTrigger(MessageService messageService, PlanThresholds thresholds, LongSupplier clock) {
    this.messageService = messageService;
    this.thresholds = thresholds;
    this.clock = clock;
    this.enabled = Config.getBool("autoplan.enabled", false);
    this.cooldownMillis = Config.getLong("autoplan.cooldownMinutes", 180) * 60_000L;
    this.maxTracked = Config.getInt("autoplan.maxTracked", 5000);
    if (enabled) {
      log.info("autoplan enabled defaultThresholdMicros={} cooldownMinutes={}",
        Config.getLong("autoplan.defaultThresholdMicros", 100_000),
        Config.getLong("autoplan.cooldownMinutes", 180));
    }
  }

  public void onIngest(MetricRequest req) {
    if (!enabled || req == null || req.dbs == null || req.dbs.isEmpty()) {
      return;
    }
    final long now = clock.getAsLong();
    pruneIfFull(now);
    for (MetricDbData db : req.dbs) {
      if (db.metrics == null) continue;
      for (MetricData m : db.metrics) {
        evaluate(req, m, now);
      }
    }
  }

  private void evaluate(MetricRequest req, MetricData m, long now) {
    if (m.hash == null || m.count == null || m.count == 0L) {
      return;
    }
    final long mean = (m.mean != null) ? m.mean
      : (m.total != null ? (m.total / m.count) : 0L);
    if (mean <= 0L) {
      return;
    }
    final long threshold = thresholds.thresholdMicros(req.appName, m.hash);
    if (mean < threshold) {
      return;
    }
    final Key key = new Key(req.appName, req.environment, m.hash);
    final Long prev = requestedAt.get(key);
    if (prev != null && (now - prev) < cooldownMillis) {
      return;
    }
    requestedAt.put(key, now);
    messageService.pushMessage(req.appName, req.environment, "qp:" + m.hash);
    log.info("autoplan request appName={} env={} hash={} mean={}us threshold={}us",
      req.appName, req.environment, m.hash, mean, threshold);
  }

  private void pruneIfFull(long now) {
    if (requestedAt.size() < maxTracked) {
      return;
    }
    final long expiry = now - cooldownMillis;
    for (Map.Entry<Key, Long> e : requestedAt.entrySet()) {
      if (e.getValue() < expiry) {
        requestedAt.remove(e.getKey(), e.getValue());
      }
    }
    // If still over the cap after pruning expired entries, drop arbitrary ones.
    if (requestedAt.size() >= maxTracked) {
      var it = requestedAt.entrySet().iterator();
      while (it.hasNext() && requestedAt.size() >= maxTracked) {
        it.next();
        it.remove();
      }
    }
  }

  private record Key(String appName, String environment, String hash) {
    Key {
      Objects.requireNonNull(hash, "hash");
    }
  }
}
