package org.ebean.monitor.usage;

import io.avaje.config.Config;
import io.avaje.inject.PostConstruct;
import io.avaje.inject.PreDestroy;
import io.ebean.Database;
import io.ebean.Transaction;
import org.ebean.monitor.Application;
import org.ebean.monitor.domain.DUserUsage;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Captures authenticated UI and API request usage in memory and periodically
 * persists detached minute aggregates.
 */
public final class UserUsageService {

  public static final String PRINCIPAL_ATTRIBUTE = "security.principal";
  private static final Logger log = LoggerFactory.getLogger(UserUsageService.class);
  private static final int MAX_USER_LENGTH = 200;
  private static final int MAX_METHOD_LENGTH = 10;
  private static final int MAX_PATH_LENGTH = 300;

  private final Database database;
  private final boolean forwardOnly;
  private final long flushSeconds;
  private final int maxAggregates;
  private final AtomicReference<Active> active = new AtomicReference<>(new Active());
  private final ScheduledExecutorService scheduler;

  public UserUsageService(Database database) {
    this(database, Application.isForwardOnly(),
      Config.getLong("insight.userUsage.flushSeconds", 60L),
      Config.getInt("insight.userUsage.maxAggregates", 10_000), true);
  }

  UserUsageService(Database database, boolean forwardOnly, long flushSeconds,
                   int maxAggregates) {
    this(database, forwardOnly, flushSeconds, maxAggregates, false);
  }

  private UserUsageService(Database database, boolean forwardOnly, long flushSeconds,
                           int maxAggregates, boolean schedule) {
    this.database = database;
    this.forwardOnly = forwardOnly;
    this.flushSeconds = Math.max(1L, flushSeconds);
    this.maxAggregates = Math.max(1, maxAggregates);
    this.scheduler = !forwardOnly && schedule
      ? Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("user-usage-flush").factory())
      : null;
  }

  @PostConstruct
  void start() {
    if (forwardOnly || scheduler == null) {
      if (forwardOnly) {
        log.info("forward-only mode - user usage persistence disabled");
      }
      return;
    }
    scheduler.scheduleAtFixedRate(this::flushSafely, flushSeconds, flushSeconds, TimeUnit.SECONDS);
    log.info("user usage capture enabled, flush interval={}s maxAggregates={}", flushSeconds, maxAggregates);
  }

  /**
   * Record an authenticated request after its handler has completed.
   */
  public void record(String user, String method, String path, long durationNanos) {
    if (forwardOnly || user == null || user.isBlank() || path == null || !isTrackedPath(path)) {
      return;
    }
    var key = newKey(user, method, path);

    Active current = active.get();
    UsageAggregate aggregate = current.values.get(key);
    if (aggregate == null) {
      if (current.size.incrementAndGet() > maxAggregates) {
        current.size.decrementAndGet();
        return;
      }
      UsageAggregate created = new UsageAggregate();
      UsageAggregate existing = current.values.putIfAbsent(key, created);
      if (existing != null) {
        current.size.decrementAndGet();
        aggregate = existing;
      } else {
        aggregate = created;
      }
    }
    aggregate.add(durationNanos);
  }

  @NonNull
  private static UsageKey newKey(String user, String method, String path) {
    return new UsageKey(
      Instant.now().truncatedTo(ChronoUnit.MINUTES),
      limit(user, MAX_USER_LENGTH),
      limit(method == null ? "UNKNOWN" : method, MAX_METHOD_LENGTH),
      limit(path, MAX_PATH_LENGTH));
  }

  /**
   * Atomically detach active aggregates and persist the detached snapshot.
   * Aggregates arriving during persistence belong to the next flush.
   */
  public void flush() {
    if (forwardOnly) {
      return;
    }
    Active detached = active.getAndSet(new Active());
    if (detached.values.isEmpty()) {
      return;
    }
    List<Snapshot> snapshots = detached.values.entrySet().stream()
      .map(entry -> entry.getValue().snapshot(entry.getKey()))
      .sorted(Comparator.comparing(Snapshot::minuteAt)
        .thenComparing(Snapshot::userId)
        .thenComparing(Snapshot::method)
        .thenComparing(Snapshot::path))
      .toList();
    try (Transaction transaction = database.beginTransaction()) {
      transaction.setBatchSize(500);
      for (Snapshot snapshot : snapshots) {
        DUserUsage bean = new DUserUsage();
        bean.setMinuteAt(snapshot.minuteAt());
        bean.setUserId(snapshot.userId());
        bean.setMethod(snapshot.method());
        bean.setPath(snapshot.path());
        bean.setRequestCount(snapshot.count());
        bean.setTotalMicros(snapshot.totalMicros());
        bean.setMaxMicros(snapshot.maxMicros());
        database.save(bean, transaction);
      }
      transaction.commit();
    } catch (RuntimeException e) {
      log.warn("failed to persist {} user usage aggregate(s); detached data was dropped", snapshots.size(), e);
    }
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdown();
      try {
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    flush();
  }

  static boolean isTrackedPath(String path) {
    if ("/ux/app-config".equals(path) || path.startsWith("/ux/app-config/")) {
      return false;
    }
    return "/v1".equals(path) || path.startsWith("/v1/")
      || "/ux".equals(path) || path.startsWith("/ux/");
  }

  int activeAggregateCount() {
    return active.get().values.size();
  }

  long activeRequestCount() {
    return active.get().values.values().stream()
      .mapToLong(UsageAggregate::count)
      .sum();
  }

  private void flushSafely() {
    try {
      flush();
    } catch (RuntimeException e) {
      log.warn("user usage flush failed", e);
    }
  }

  private static String limit(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  record UsageKey(Instant minuteAt, String userId, String method, String path) {
  }

  record Snapshot(Instant minuteAt, String userId, String method, String path,
                  long count, long totalMicros, long maxMicros) {
  }

  private static final class Active {
    private final ConcurrentHashMap<UsageKey, UsageAggregate> values = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
  }

  private static final class UsageAggregate {
    private final LongAdder count = new LongAdder();
    private final LongAdder totalMicros = new LongAdder();
    private final AtomicLong maxMicros = new AtomicLong();

    void add(long durationNanos) {
      long durationMicros = TimeUnit.NANOSECONDS.toMicros(Math.max(0L, durationNanos));
      count.increment();
      totalMicros.add(durationMicros);
      maxMicros.accumulateAndGet(durationMicros, Math::max);
    }

    Snapshot snapshot(UsageKey key) {
      return new Snapshot(key.minuteAt(), key.userId(), key.method(), key.path(),
        count.sum(), totalMicros.sum(), maxMicros.get());
    }

    long count() {
      return count.sum();
    }
  }
}
