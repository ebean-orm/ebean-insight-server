package org.ebean.monitor.forward;

import org.ebean.monitor.domain.query.QDAppMetric;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DB-backed per-query threshold resolver. Reads {@code planThresholdMicros}
 * from {@code DAppMetric} and caches results (including misses) for a short
 * TTL so high-frequency ingest does not hammer the DB. CLI / UI threshold
 * changes propagate within {@code ttlMillis}.
 */
final class DbPlanThresholds implements PlanThresholds {

  private record Key(String appName, String hash) {}

  private final long defaultMicros;
  private final long ttlMillis;
  private final ConcurrentMap<Key, Entry> cache = new ConcurrentHashMap<>();
  private final java.util.function.LongSupplier clock;

  DbPlanThresholds(long defaultMicros, long ttlMillis) {
    this(defaultMicros, ttlMillis, System::currentTimeMillis);
  }

  DbPlanThresholds(long defaultMicros, long ttlMillis, java.util.function.LongSupplier clock) {
    this.defaultMicros = defaultMicros;
    this.ttlMillis = ttlMillis;
    this.clock = clock;
  }

  @Override
  public long thresholdMicros(String appName, String hash) {
    if (appName == null || hash == null) {
      return defaultMicros;
    }
    long now = clock.getAsLong();
    Key key = new Key(appName, hash);
    Entry e = cache.get(key);
    if (e != null && (now - e.loadedAt) < ttlMillis) {
      return e.value < 0 ? defaultMicros : e.value;
    }
    long resolved = lookup(appName, hash);
    cache.put(key, new Entry(now, resolved));
    return resolved < 0 ? defaultMicros : resolved;
  }

  private long lookup(String appName, String hash) {
    Long override = new QDAppMetric()
      .select(QDAppMetric.alias().planThresholdMicros)
      .app.name.eq(appName)
      .key.eq(hash)
      .findSingleAttribute();
    return override == null ? -1L : override;
  }

  private record Entry(long loadedAt, long value) {}
}
