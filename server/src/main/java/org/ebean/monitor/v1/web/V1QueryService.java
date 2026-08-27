package org.ebean.monitor.v1.web;

import io.avaje.inject.Component;
import io.avaje.jex.http.BadRequestException;
import io.avaje.jex.http.NotFoundException;
import io.ebean.DB;
import io.ebean.SqlQuery;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DCaptureRequest;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.DQueryPlanChange;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.ebean.monitor.domain.query.QDEnv;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.ebean.monitor.domain.query.QDQueryPlanChange;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.model.MetricTimeseriesTopSeries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.rollup.RegressionPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.PendingPlan;
import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.model.TopGroup;
import org.ebean.monitor.web.MessageService;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backing service for the {@code /v1} API. Owns the aggregation queries
 * (top-N, stats, missing-plans, app-summary) and natural-key lookups.
 *
 * <p>Aggregation queries use raw SQL via {@link DB#sqlQuery(String)} because
 * the rollup tables do not have a one-to-one query-bean form for the
 * grouped output.
 */
@Component
public final class V1QueryService {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int DEFAULT_SERIES_LIMIT = 8;
  private static final int MAX_SERIES_LIMIT = 20;

  /** Sentinel {@code grp} value for the synthetic "Other" remainder series in {@link #getTopAppMetricsTimeseries}. */
  private static final String OTHER_SENTINEL = "__other__";

  /** Defensive cap on unbounded control-plane scans (apps, envs). */
  private static final int MAX_ROWS_GUARD = 1000;
  private static final long DEFAULT_TOP_WINDOW_MINUTES = 60L;
  private static final long DEFAULT_ACTIVE_WINDOW_MINUTES = 60L;

  // Window thresholds (minutes) for selecting the coarsest timed rollup table
  // that still covers the requested window. Aggregation queries SUM over the
  // whole window, so the bucket granularity within the window is irrelevant;
  // this just bounds the number of rows scanned. Results are approximate at the
  // bucket boundary / leading edge (the newest partial bucket may be excluded).
  // Boundaries align with partition retention (see CleanupPartitions): m1/m10
  // 30d, m60 120d, d1 1000d.
  private static final long M1_MAX_MINUTES = 3L * 60;          // 3 hours
  private static final long M10_MAX_MINUTES = 2L * 24 * 60;    // 2 days
  private static final long M60_MAX_MINUTES = 120L * 24 * 60;  // 120 days

  // Partition retention per rollup (CleanupPartitions defaults): m1 30d, m10
  // 100d, m60 500d, d1 1000d. Each timeseries tier below selects a table only for
  // windows far shorter than its retention, so retention never constrains it.
  // Upper bound on buckets returned for a single-hash timeseries (see
  // timeseriesTableFor). The trend chart down-samples to ~180 columns, so any
  // count at/above that renders full width; this keeps the chart a consistent
  // width across windows while bounding payload size.
  private static final long TS_MAX_BUCKETS = 720L;

  private static final Set<String> ORDER_BY_KEYS = Set.of("total", "mean", "max", "count", "value");
  private static final java.util.regex.Pattern SAFE_TAG_KEY = java.util.regex.Pattern.compile("[a-zA-Z0-9_.\\-]+");

  // How long a requested-but-not-yet-collected capture stays visible in the
  // pending view. Covers the app's ~5 minute bind-collection window with margin;
  // beyond this a never-collected request (query never executed) drops off.
  private static final long PENDING_STALE_MINUTES = 15L;

  private final MessageService messageService;

  public V1QueryService(MessageService messageService) {
    this.messageService = messageService;
  }

  // ---------------------------------------------------------------------------
  // Apps
  // ---------------------------------------------------------------------------

  public List<App> listApps(@Nullable Long activeWithinMinutes, @Nullable Long activeWithinHours) {
    final TimeWindow window = TimeWindow.of(activeWithinMinutes, activeWithinHours, 0L);
    if (!window.hasFrom()) {
      return new QDApp()
        .orderBy().name.asc()
        .setMaxRows(MAX_ROWS_GUARD)
        .findList()
        .stream()
        .map(V1QueryService::toApp)
        .toList();
    }
    final String sql = """
      select distinct a.id, a.name
      from ebean_insight.app a
      join ebean_insight.timed_m1 t on t.app_id = a.id
      where t.event_time > :from
      order by a.name
      limit :guard
      """;
    return DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("guard", MAX_ROWS_GUARD)
      .mapTo((rs, i) -> new App(rs.getLong("id"), rs.getString("name")))
      .findList();
  }

  public AppSummary getApp(String appName) {
    final DApp app = requireApp(appName);
    final String sql = """
      select
        (select count(*) from ebean_insight.app_metric m where m.app_id = :appId) as metric_count,
        (select count(*) from ebean_insight.query_plan p where p.app_id = :appId) as plan_count,
        (select max(t.event_time) from ebean_insight.timed_m1 t where t.app_id = :appId) as last_report_at
      """;
    return DB.sqlQuery(sql)
      .setParameter("appId", app.getId())
      .mapTo((rs, i) -> toAppSummary(rs, app))
      .findOne();
  }

  public boolean isDatasourcePoolDashboardEnabled(String appName) {
    final DApp app = findApp(appName);
    return app != null && app.isDatasourcePoolDashboardEnabled();
  }

  public boolean isWebApiDashboardEnabled(String appName) {
    final DApp app = findApp(appName);
    return app != null && app.isWebApiDashboardEnabled();
  }

  public boolean isJvmDashboardEnabled(String appName) {
    final DApp app = findApp(appName);
    return app != null && app.isJvmDashboardEnabled();
  }

  public void setDashboardConfig(String appName, boolean datasourcePool, boolean webApi, boolean jvm) {
    final DApp app = findApp(appName);
    if (app == null) {
      return;
    }
    app.setDatasourcePoolDashboardEnabled(datasourcePool);
    app.setWebApiDashboardEnabled(webApi);
    app.setJvmDashboardEnabled(jvm);
    DB.save(app);
  }

  public List<Env> listAppEnvs(String appName) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return DB.sqlQuery("""
        select distinct e.name
        from ebean_insight.timed_m1 t
        join ebean_insight.env e on e.id = t.env_id
        where t.app_id = :appId
        order by e.name
        """)
      .setParameter("appId", app.getId())
      .mapTo((rs, _) -> new Env(rs.getString("name")))
      .findList();
  }

  private static AppSummary toAppSummary(ResultSet rs, DApp app) throws SQLException {
    return AppSummary.builder()
      .id((long) app.getId())
      .name(app.getName())
      .lastReportAt(toInstant(rs.getTimestamp("last_report_at")))
      .metricCount(rs.getLong("metric_count"))
      .planCount(rs.getLong("plan_count"))
      .build();
  }

  // ---------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------

  public List<AppMetric> listAppMetrics(String appName, @Nullable String name, @Nullable String label,
                                        @Nullable String kind, @Nullable String type,
                                        @Nullable Boolean planCapable, @Nullable Integer limit) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    final QDAppMetric q = new QDAppMetric().app.eq(app);
    q.name.eqIfNotBlank(name);
    if (label != null && !label.isBlank()) {
      q.raw("tags ->> 'label' = ?", label);
    }
    if (kind != null && !kind.isBlank()) {
      q.raw("tags ->> 'kind' = ?", kind);
    }
    if (type != null && !type.isBlank()) {
      q.raw("tags ->> 'type' = ?", type);
    }
    if (planCapable != null) {
      q.planCapable.eq(planCapable);
    }
    return q
      .orderBy().name.asc()
      .setMaxRows(clampLimit(limit))
      .findList()
      .stream()
      .map(this::toAppMetric)
      .toList();
  }

  public List<AppMetric> getMetricByHash(String appName, String hash) {
    final DApp app = findApp(appName);
    if (app == null || hash == null || hash.isBlank()) {
      return List.of();
    }
    final DAppMetric metric = new QDAppMetric()
      .app.eq(app)
      .key.eq(hash.trim())
      .findOne();
    return metric == null ? List.of() : List.of(toAppMetric(metric));
  }

  public List<AppMetricStats> getMetricStatsByHash(String appName, String hash,
                                                   @Nullable Long sinceMinutes,
                                                   @Nullable Long sinceHours,
                                                   @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return getMetricStatsByHash(appName, hash, env, window);
  }

  /** Metric statistics for a hash over an absolute time window. */
  public List<AppMetricStats> getMetricStatsByHash(String appName, String hash,
                                                   @Nullable String env,
                                                   Instant from, Instant to) {
    return getMetricStatsByHash(appName, hash, env, TimeWindow.between(from, to));
  }

  private List<AppMetricStats> getMetricStatsByHash(String appName, String hash,
                                                    @Nullable String env, TimeWindow window) {
    final DApp app = findApp(appName);
    if (app == null || hash == null || hash.isBlank()) {
      return List.of();
    }
    final DAppMetric metric = new QDAppMetric()
      .app.eq(app)
      .key.eq(hash.trim())
      .findOne();
    if (metric == null) {
      return List.of();
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return List.of();
    }
    final long minutes = window.minutes();
    final String table = timedTableFor(minutes);
    final SqlQuery query = DB.sqlQuery(("""
        select
          coalesce(sum(t.count), 0) as count,
          coalesce(sum(t.total), 0) as total,
          coalesce(max(t.max), 0)   as max
        from %s t
        where t.metric_id = :metricId
          and t.event_time > :from
        """
      + (envId == null ? "" : "  and t.env_id = :envId\n")).formatted(table))
      .setParameter("metricId", metric.getId())
      .setParameter("from", window.from());
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    final AppMetricStats stats = query
      .mapTo((rs, _) -> toAppMetricStats(rs, app, metric, minutes))
      .findOne();
    return List.of(stats);
  }

  private AppMetricStats toAppMetricStats(ResultSet rs, DApp app, DAppMetric metric, long minutes) throws SQLException {
    final long count = rs.getLong("count");
    final long total = rs.getLong("total");
    final long max = rs.getLong("max");
    final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
    return AppMetricStats.builder()
      .id((long) metric.getId())
      .app(app.getName())
      .name(metric.getName())
      .label(displayLabel(metric.getName(), metric.getTags()))
      .tags(tagsToStringMap(metric.getTags()))
      .key(metric.getKey())
      .loc(metric.getLoc())
      .planCapable(metric.isPlanCapable())
      .count(count)
      .totalMicros(total)
      .meanMicros(mean)
      .maxMicros(max)
      .windowMinutes(minutes)
      .build();
  }

  /**
   * Per-bucket time-series for a single metric (raw additive components per
   * bucket — count, total, max). Mean is derived client-side. Bucket resolution
   * follows {@link #timeseriesTableFor(long)} so the chart stays a consistent
   * width across windows while long windows stay cheap.
   *
   * <p>The series is dense: every bucket boundary across the window is returned,
   * with empty buckets reported as explicit zeros, so the consumer's time axis
   * stays continuous even for sparse metrics.
   */
  public MetricTimeseries getMetricTimeseries(String appName, String hash,
                                              @Nullable Long sinceMinutes,
                                              @Nullable Long sinceHours,
                                              @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    final long minutes = window.minutes();
    final String table = timeseriesTableFor(minutes);
    final long bucketMinutes = bucketMinutesFor(table);
    final DApp app = findApp(appName);
    if (app == null || hash == null || hash.isBlank()) {
      return emptyTimeseries(appName, hash, minutes, bucketMinutes);
    }
    final DAppMetric metric = new QDAppMetric()
      .app.eq(app)
      .key.eq(hash.trim())
      .findOne();
    if (metric == null) {
      return emptyTimeseries(appName, hash, minutes, bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseries(app.getName(), hash, minutes, bucketMinutes);
    }
    // Generate a dense grid of bucket boundaries across the whole window (epoch
    // arithmetic keeps it timezone-independent and aligned to the same UTC
    // boundaries the timed tables store) and LEFT JOIN the metric so empty
    // buckets come back as explicit zeros rather than being dropped. This keeps
    // the client trend chart's time axis honest for sparse metrics.
    final long stepSeconds = bucketMinutes * 60L;
    final SqlQuery query = DB.sqlQuery(("""
        with grid as (
          select to_timestamp(s) as event_time
          from generate_series(
                 (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
                 (cast(floor(extract(epoch from now()) / :step) as bigint)) * :step,
                 :step
               ) as s
        )
        select
          grid.event_time           as event_time,
          coalesce(sum(t.count), 0) as count,
          coalesce(sum(t.total), 0) as total,
          coalesce(max(t.max), 0)   as max
        from grid
        left join %s t
          on t.event_time = grid.event_time
         and t.metric_id = :metricId
         and t.event_time > :from
        """
      + (envId == null ? "" : "     and t.env_id = :envId\n")
      + """
        group by grid.event_time
        order by grid.event_time asc
        """).formatted(table))
      .setParameter("metricId", metric.getId())
      .setParameter("from", window.from())
      .setParameter("step", stepSeconds);
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    final List<MetricTimeBucket> buckets = query
      .mapTo((rs, _) -> new MetricTimeBucket(
        toInstant(rs.getTimestamp("event_time")),
        rs.getLong("count"),
        rs.getLong("total"),
        rs.getLong("max")))
      .findList();
    return MetricTimeseries.builder()
      .app(app.getName())
      .hash(metric.getKey())
      .label(displayLabel(metric.getName(), metric.getTags()))
      .windowMinutes(minutes)
      .bucketMinutes(bucketMinutes)
      .buckets(buckets)
      .build();
  }

  private static MetricTimeseries emptyTimeseries(@Nullable String app, @Nullable String hash,
                                                  long minutes, long bucketMinutes) {
    return MetricTimeseries.builder()
      .app(app)
      .hash(hash)
      .windowMinutes(minutes)
      .bucketMinutes(bucketMinutes)
      .buckets(List.of())
      .build();
  }

  static long bucketMinutesFor(String table) {
    return switch (table) {
      case "ebean_insight.timed_m1" -> 1L;
      case "ebean_insight.timed_m10" -> 10L;
      case "ebean_insight.timed_m60" -> 60L;
      default -> 1440L;
    };
  }

  /**
   * Bucket step for the "Top" stacked-bar chart ({@link #getTopAppMetricsTimeseries}).
   * Windows over 3h but up to 4h use 2-minute bars, windows over 4h but within
   * the {@code timed_m1} table's range (up to 12h, see {@link #timeseriesTableFor})
   * use 5-minute bars. Windows over 24h sourced from {@code timed_m10} use
   * 20-minute bars to keep the chart near 150 columns; longer windows already
   * fall onto the 60-minute table via {@link #bucketMinutesFor}.
   */
  static long topBucketMinutesFor(long windowMinutes, String table) {
    if ("ebean_insight.timed_m1".equals(table)) {
      if (windowMinutes > 240L) {
        return 5L;
      }
      if (windowMinutes > 180L) {
        return 2L;
      }
    }
    if ("ebean_insight.timed_m10".equals(table) && windowMinutes > 1440L) {
      return 20L;
    }
    return bucketMinutesFor(table);
  }

  /** Dense per-bucket time-series for a single {@code label}, aggregated across every underlying hash sharing it. */
  public record LabelTimeseries(String app, String label, long windowMinutes, long bucketMinutes,
                                 List<MetricTimeBucket> buckets) {
  }

  /**
   * Per-bucket time-series for a single {@code label} tag, summed across every
   * underlying metric identity (hash) sharing that label — the drill-down
   * counterpart to {@link #getTopAppMetricsTimeseries}'s top-N-labels view.
   * Reuses the same dense grid × group SQL as that method with a single
   * label as the "group".
   */
  public LabelTimeseries getLabelTimeseries(String appName, String label,
                                            @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                            @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return getLabelTimeseries(appName, label, window, env);
  }

  /** Dense label time-series over an absolute time window. */
  public LabelTimeseries getLabelTimeseries(String appName, String label,
                                            @Nullable String env, Instant from, Instant to) {
    return getLabelTimeseries(appName, label, TimeWindow.between(from, to), env);
  }

  private LabelTimeseries getLabelTimeseries(String appName, String label,
                                             TimeWindow window, @Nullable String env) {
    final long minutes = window.minutes();
    final String table = timeseriesTableFor(minutes);
    final long bucketMinutes = bucketMinutesFor(table);
    final DApp app = findApp(appName);
    if (app == null || isBlank(label)) {
      return new LabelTimeseries(appName, label, minutes, bucketMinutes, List.of());
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return new LabelTimeseries(app.getName(), label, minutes, bucketMinutes, List.of());
    }
    final Map<String, List<MetricTimeBucket>> byGroup = denseBucketsByGroup(
      app, table, window, null, null, null, null, envId, List.of(new RankedLabel(label, 0L, 0L)));
    return new LabelTimeseries(app.getName(), label, minutes, bucketMinutes,
      byGroup.getOrDefault(label, List.of()));
  }

  /**
   * Per-bucket time-series for the hashes belonging to a single label,
   * including an {@code Other} remainder when the hash limit is exceeded.
   */
  public MetricTimeseriesTop getLabelHashTimeseries(String appName, String label, String name,
                                                    @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                    @Nullable Integer seriesLimit, @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return getLabelHashTimeseries(appName, label, name, window, seriesLimit, env);
  }

  /** Per-hash label time-series over an absolute time window. */
  public MetricTimeseriesTop getLabelHashTimeseries(String appName, String label, String name,
                                                    @Nullable Integer seriesLimit, @Nullable String env,
                                                    Instant from, Instant to) {
    return getLabelHashTimeseries(appName, label, name, TimeWindow.between(from, to), seriesLimit, env);
  }

  private MetricTimeseriesTop getLabelHashTimeseries(String appName, String label, String name,
                                                     TimeWindow window, @Nullable Integer seriesLimit,
                                                     @Nullable String env) {
    final long minutes = window.minutes();
    final String table = timeseriesTableFor(minutes);
    final long bucketMinutes = topBucketMinutesFor(minutes, table);
    final DApp app = findApp(appName);
    if (app == null || isBlank(label)) {
      return emptyTimeseriesTop(appName, minutes, bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseriesTop(app.getName(), minutes, bucketMinutes);
    }

    final List<TopGroup> hashGroups = topAppMetrics(appName, "hash", name, label, null, null,
      "total", minutes, null, seriesLimit, null, env);
    if (hashGroups.isEmpty()) {
      return emptyTimeseriesTop(app.getName(), minutes, bucketMinutes);
    }
    final List<RankedLabel> ranked = hashGroups.stream()
      .map(g -> new RankedLabel(g.key(), 1L, g.totalMicros()))
      .toList();
    final Map<String, List<MetricTimeBucket>> bucketsByHash =
      denseBucketsByHash(app, table, bucketMinutes, window, name, label, envId, ranked);

    final List<MetricTimeseriesTopSeries> series = new ArrayList<>(ranked.size() + 1);
    for (RankedLabel hash : ranked) {
      series.add(MetricTimeseriesTopSeries.builder()
        .group(hash.label())
        .other(false)
        .hashCount(hash.hashCount())
        .totalMicros(hash.total())
        .buckets(bucketsByHash.getOrDefault(hash.label(), List.of()))
        .build());
    }
    final List<MetricTimeBucket> otherBuckets = bucketsByHash.get(OTHER_SENTINEL);
    if (otherBuckets != null && otherBuckets.stream().anyMatch(b -> b.total() > 0L)) {
      series.add(MetricTimeseriesTopSeries.builder()
        .group("Other")
        .other(true)
        .hashCount(0L)
        .totalMicros(otherBuckets.stream().mapToLong(MetricTimeBucket::total).sum())
        .buckets(otherBuckets)
        .build());
    }
    return MetricTimeseriesTop.builder()
      .app(app.getName())
      .windowMinutes(minutes)
      .bucketMinutes(bucketMinutes)
      .series(series)
      .build();
  }

  public List<TopGroup> topAppMetrics(String appName, @Nullable String by, @Nullable String name,
                                      @Nullable String label, @Nullable String kind, @Nullable String type,
                                      @Nullable String orderBy,
                                      @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                      @Nullable Integer limit, @Nullable Boolean planCapable,
                                      @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return topAppMetrics(appName, by, name, label, kind, type, orderBy, window, limit, planCapable, env);
  }

  /**
   * Ranked application metrics over an absolute time window. This overload is
   * used by the dashboard's custom chart selection; the public API continues
   * to expose relative since-minute / since-hour windows.
   */
  public List<TopGroup> topAppMetrics(String appName, @Nullable String by, @Nullable String name,
                                      @Nullable String label, @Nullable String kind, @Nullable String type,
                                      @Nullable String orderBy,
                                      @Nullable Integer limit, @Nullable Boolean planCapable,
                                      @Nullable String env, Instant from, Instant to) {
    return topAppMetrics(appName, by, name, label, kind, type, orderBy,
      TimeWindow.between(from, to), limit, planCapable, env);
  }

  private List<TopGroup> topAppMetrics(String appName, @Nullable String by, @Nullable String name,
                                       @Nullable String label, @Nullable String kind, @Nullable String type,
                                       @Nullable String orderBy, TimeWindow window,
                                       @Nullable Integer limit, @Nullable Boolean planCapable,
                                       @Nullable String env) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runTopQuery(app, by, name, label, kind, type, orderBy, window, planCapable, env, false, clampLimit(limit));
  }

  public List<TopGroup> topMetrics(@Nullable String by, @Nullable String name,
                                   @Nullable String label, @Nullable String kind, @Nullable String type,
                                   @Nullable String orderBy,
                                   @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                   @Nullable Integer limit, @Nullable Boolean planCapable,
                                   @Nullable String env, @Nullable Boolean allApps) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return runTopQuery(null, by, name, label, kind, type, orderBy, window, planCapable, env, allApps, clampLimit(limit));
  }

  /**
   * Every label in the dot-hierarchy "family" rooted at {@code root} - the
   * root itself (an exact match) plus every label that starts with
   * {@code root + "."} - each with its own aggregated timer stats over the
   * window. Labels follow Ebean's {@code Type.method[.fetchPath...]}
   * convention: the root is the originating finder query and each further
   * dot segment is one additional lazy-loaded fetch path, so this powers a
   * "query family" tree on the metric-detail page relating a label to its
   * ancestor/descendant fetch-path queries.
   */
  public List<TopGroup> topLabelFamily(String appName, String root, @Nullable String name,
                                       long windowMinutes, @Nullable String env, @Nullable Integer limit) {
    final DApp app = findApp(appName);
    if (app == null || isBlank(root)) {
      return List.of();
    }
    final TimeWindow window = TimeWindow.of(windowMinutes, null, DEFAULT_TOP_WINDOW_MINUTES);
    return runLabelFamilyQuery(app, root, name, window, env, clampLimit(limit));
  }

  /** Query-family groups over an absolute time window. */
  public List<TopGroup> topLabelFamily(String appName, String root, @Nullable String name,
                                       @Nullable String env, @Nullable Integer limit,
                                       Instant from, Instant to) {
    final DApp app = findApp(appName);
    if (app == null || isBlank(root)) {
      return List.of();
    }
    return runLabelFamilyQuery(app, root, name, TimeWindow.between(from, to), env, clampLimit(limit));
  }

  /**
   * Per-bucket time-series of the top-N labels for an application, plus a
   * synthetic "Other" series summing every metric outside the top-N labels
   * (including metrics with no {@code label} tag). The stacked total across
   * all returned series reconstructs the application's overall query time for
   * every bucket.
   *
   * <p>Implemented as up to three queries against the same rollup table: (1)
   * rank labels by the requested {@code orderBy} over the whole window to
   * pick the top-N, (2) a dense grid × (top-N ∪ "Other") cross join to fetch
   * per-bucket values for every returned series in one pass, and (3), only
   * when an "Other" series is emitted, a single scalar count of all distinct
   * metric identities in the window (to report a meaningful {@code hashCount}
   * for "Other" without an expensive NOT IN scan).
   */
  public MetricTimeseriesTop getTopAppMetricsTimeseries(String appName, @Nullable String name,
                                                        @Nullable String kind, @Nullable String type,
                                                        @Nullable String orderBy,
                                                        @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                        @Nullable Integer seriesLimit,
                                                        @Nullable Boolean planCapable, @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return getTopAppMetricsTimeseries(appName, name, kind, type, orderBy, window,
      seriesLimit, planCapable, env);
  }

  /**
   * Per-bucket top application metrics over an absolute time window. This
   * overload is used by the dashboard's custom chart selection.
   */
  public MetricTimeseriesTop getTopAppMetricsTimeseries(String appName, @Nullable String name,
                                                        @Nullable String kind, @Nullable String type,
                                                        @Nullable String orderBy,
                                                        @Nullable Integer seriesLimit,
                                                        @Nullable Boolean planCapable, @Nullable String env,
                                                        Instant from, Instant to) {
    return getTopAppMetricsTimeseries(appName, name, kind, type, orderBy,
      TimeWindow.between(from, to), seriesLimit, planCapable, env);
  }

  private MetricTimeseriesTop getTopAppMetricsTimeseries(String appName, @Nullable String name,
                                                         @Nullable String kind, @Nullable String type,
                                                         @Nullable String orderBy, TimeWindow window,
                                                         @Nullable Integer seriesLimit,
                                                         @Nullable Boolean planCapable, @Nullable String env) {
    final long minutes = window.minutes();
    final String table = timeseriesTableFor(minutes);
    final long bucketMinutes = topBucketMinutesFor(minutes, table);
    final DApp app = findApp(appName);
    if (app == null) {
      return emptyTimeseriesTop(appName, minutes, bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseriesTop(app.getName(), minutes, bucketMinutes);
    }
    final String sortKey = resolveOrderBy(orderBy);
    if ("value".equals(sortKey)) {
      throw new BadRequestException(
        "orderBy 'value' is not applicable to /metrics/top/timeseries (timer metrics only)");
    }
    final int limit = clampSeriesLimit(seriesLimit);

    final List<RankedLabel> ranked = rankLabels(app, table, window, name, kind, type, planCapable, envId, sortKey, limit);
    if (ranked.isEmpty()) {
      return emptyTimeseriesTop(app.getName(), minutes, bucketMinutes);
    }

    final Map<String, List<MetricTimeBucket>> bucketsByGroup =
      denseBucketsByGroup(app, table, bucketMinutes, window, name, kind, type, planCapable, envId, ranked);

    final List<MetricTimeseriesTopSeries> series = new ArrayList<>(ranked.size() + 1);
    long rankedHashCount = 0L;
    for (RankedLabel r : ranked) {
      rankedHashCount += r.hashCount();
      series.add(MetricTimeseriesTopSeries.builder()
        .group(r.label())
        .other(false)
        .hashCount(r.hashCount())
        .totalMicros(r.total())
        .buckets(bucketsByGroup.getOrDefault(r.label(), List.of()))
        .build());
    }
    final List<MetricTimeBucket> otherBuckets = bucketsByGroup.get(OTHER_SENTINEL);
    if (otherBuckets != null) {
      long otherTotal = 0L;
      for (MetricTimeBucket b : otherBuckets) {
        otherTotal += b.total();
      }
      if (otherTotal > 0L) {
        final long grandHashCount = grandHashCount(app, table, window, name, kind, type, planCapable, envId);
        series.add(MetricTimeseriesTopSeries.builder()
          .group("Other")
          .other(true)
          .hashCount(Math.max(0L, grandHashCount - rankedHashCount))
          .totalMicros(otherTotal)
          .buckets(otherBuckets)
          .build());
      }
    }
    return MetricTimeseriesTop.builder()
      .app(app.getName())
      .windowMinutes(minutes)
      .bucketMinutes(bucketMinutes)
      .series(series)
      .build();
  }

  /**
   * Per-bucket gauge time-series grouped by a metric tag. The datasource-pool
   * dashboard uses this for {@code datasource.pool.size} grouped by
   * {@code type}; gauge totals represent the summed reading for each bucket.
   */
  public MetricTimeseriesTop getGaugeTimeseries(String appName, String name, String by,
                                                @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                @Nullable String env, Instant from, Instant to) {
    if ("pod".equals(by)) {
      return getPodGaugeTimeseries(appName, name, sinceMinutes, sinceHours, env, from, to);
    }
    final TimeWindow window = from == null
      ? TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES)
      : TimeWindow.between(from, to);
    final String table = gaugeTableFor(window.minutes());
    final long bucketMinutes = gaugeBucketMinutesFor(window.minutes(), table);
    final DApp app = findApp(appName);
    if (app == null) {
      return emptyTimeseriesTop(appName, window.minutes(), bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }

    final List<TopGroup> groups = topAppMetrics(appName, by, name, null, null, null,
      "value", window.minutes(), null, 10, null, env);
    if (groups.isEmpty()) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }
    final String values = groups.stream()
      .map(group -> "(:g" + groups.indexOf(group) + ")")
      .collect(java.util.stream.Collectors.joining(", "));
    final String sql = """
      with grid as (
        select to_timestamp(s) as event_time
        from generate_series(
          (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
          (cast(floor(extract(epoch from cast(:to as timestamptz)) / :step) as bigint)) * :step,
          :step
        ) as s
      ),
      groups (grp) as (values %s),
      data as (
        select to_timestamp(
                 cast(floor(extract(epoch from t.event_time) / :step) as bigint) * :step
               ) as event_time,
               m.tags ->> :by as grp,
               sum(t.total)::bigint as total,
               max(t.max)::bigint as max
        from %s t
        join ebean_insight.app_metric m on m.id = t.metric_id
        where t.event_time > :from
          and t.event_time <= :to
          and m.app_id = :appId
          and m.name = :name
          and m.tags ->> :by is not null
          %s
        group by 1, 2
      )
      select grid.event_time as event_time, groups.grp as grp,
             coalesce(data.total, 0) as total,
             coalesce(data.max, 0) as max
      from grid cross join groups
      left join data on data.event_time = grid.event_time and data.grp = groups.grp
      order by grid.event_time, groups.grp
      """.formatted(values, table, envId == null ? "" : "and t.env_id = :envId");
    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("step", bucketMinutes * 60L)
      .setParameter("appId", app.getId())
      .setParameter("name", name)
      .setParameter("by", by);
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    for (int i = 0; i < groups.size(); i++) {
      query.setParameter("g" + i, groups.get(i).group());
    }
    final Map<String, List<MetricTimeBucket>> buckets = new LinkedHashMap<>();
    for (TopGroup group : groups) {
      buckets.put(group.group(), new ArrayList<>());
    }
    query.mapTo((rs, _) -> {
      buckets.get(rs.getString("grp")).add(MetricTimeBucket.builder()
        .eventTime(toInstant(rs.getTimestamp("event_time")))
        .count(1L)
        .total(rs.getLong("total"))
        .max(rs.getLong("max"))
        .build());
      return null;
    }).findList();
    final List<MetricTimeseriesTopSeries> series = groups.stream()
      .map(group -> MetricTimeseriesTopSeries.builder()
        .group(group.group())
        .other(false)
        .hashCount(group.hashCount())
        .totalMicros(buckets.get(group.group()).stream().mapToLong(MetricTimeBucket::total).sum())
        .buckets(buckets.get(group.group()))
        .build())
      .toList();
    return MetricTimeseriesTop.builder()
      .app(app.getName())
      .windowMinutes(window.minutes())
      .bucketMinutes(bucketMinutes)
      .series(series)
      .build();
  }

  /**
   * The most recently reported gauge value for each pod at or before {@code to}.
   *
   * <p>This is used for pod configuration gauges such as a CPU limit, which are
   * reported once rather than with every collection interval.
   */
  public Map<String, Long> getLatestPodGaugeValues(String appName, String name,
                                                    @Nullable String env, @Nullable Instant to) {
    final DApp app = findApp(appName);
    if (app == null) {
      return Map.of();
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return Map.of();
    }

    final String envPredicate = envId == null ? "" : "and g.env_id = :envId";
    final SqlQuery query = DB.sqlQuery("""
        select distinct on (g.pod_id) p.name as pod, g.value
        from ebean_insight.gauge_entry g
        join ebean_insight.app_metric m on m.id = g.metric_id
        join ebean_insight.app_pod p on p.id = g.pod_id
        where g.event_time <= :to
          and g.app_id = :appId
          and m.name = :name
          %s
        order by g.pod_id, g.event_time desc
        """.formatted(envPredicate))
      .setParameter("to", to == null ? Instant.now() : to)
      .setParameter("appId", app.getId())
      .setParameter("name", name);
    if (envId != null) {
      query.setParameter("envId", envId);
    }

    final Map<String, Long> values = new LinkedHashMap<>();
    query.mapTo((rs, _) -> {
      values.put(rs.getString("pod"), rs.getBigDecimal("value").longValue());
      return null;
    }).findList();
    return Map.copyOf(values);
  }

  /** Per-bucket gauge time-series grouped by application pod from raw gauge samples. */
  private MetricTimeseriesTop getPodGaugeTimeseries(String appName, String name,
                                                    @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                    @Nullable String env, Instant from, Instant to) {
    final TimeWindow window = from == null
      ? TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES)
      : TimeWindow.between(from, to);
    final String table = gaugeTableFor(window.minutes());
    final long bucketMinutes = gaugeBucketMinutesFor(window.minutes(), table);
    final DApp app = findApp(appName);
    if (app == null) {
      return emptyTimeseriesTop(appName, window.minutes(), bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }

    final String envPredicate = envId == null ? "" : "and g.env_id = :envId";
    final SqlQuery groupQuery = DB.sqlQuery("""
        select distinct p.name as grp
        from ebean_insight.gauge_entry g
        join ebean_insight.app_metric m on m.id = g.metric_id
        join ebean_insight.app_pod p on p.id = g.pod_id
        where g.event_time > :from
          and g.event_time <= :to
          and g.app_id = :appId
          and m.name = :name
          %s
        order by grp
        """.formatted(envPredicate))
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("appId", app.getId())
      .setParameter("name", name);
    if (envId != null) {
      groupQuery.setParameter("envId", envId);
    }
    final List<String> groups = groupQuery.mapTo((rs, _) -> rs.getString("grp")).findList();
    if (groups.isEmpty()) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }

    final String groupValues = groups.stream()
      .map(group -> "(:g" + groups.indexOf(group) + ")")
      .collect(java.util.stream.Collectors.joining(", "));
    final String sql = """
      with grid as (
        select to_timestamp(s) as event_time
        from generate_series(
          (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
          (cast(floor(extract(epoch from cast(:to as timestamptz)) / :step) as bigint)) * :step,
          :step
        ) as s
      ),
      groups (grp) as (values %s),
      data as (
        select to_timestamp(
                 cast(floor(extract(epoch from g.event_time) / :step) as bigint) * :step
               ) as event_time,
               p.name as grp,
               max(g.value)::numeric as total
        from ebean_insight.gauge_entry g
        join ebean_insight.app_metric m on m.id = g.metric_id
        join ebean_insight.app_pod p on p.id = g.pod_id
        where g.event_time > :from
          and g.event_time <= :to
          and g.app_id = :appId
          and m.name = :name
          %s
        group by 1, 2
      )
      select grid.event_time, groups.grp, data.total
      from grid cross join groups
      left join data on data.event_time = grid.event_time and data.grp = groups.grp
      order by grid.event_time, groups.grp
      """.formatted(groupValues, envPredicate);
    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("step", bucketMinutes * 60L)
      .setParameter("appId", app.getId())
      .setParameter("name", name);
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    for (int i = 0; i < groups.size(); i++) {
      query.setParameter("g" + i, groups.get(i));
    }

    final Map<String, List<MetricTimeBucket>> buckets = new LinkedHashMap<>();
    for (String group : groups) {
      buckets.put(group, new ArrayList<>());
    }
    query.mapTo((rs, _) -> {
      final var value = rs.getBigDecimal("total");
      final long total = value == null ? 0L : value.longValue();
      buckets.get(rs.getString("grp")).add(MetricTimeBucket.builder()
        .eventTime(toInstant(rs.getTimestamp("event_time")))
        .count(value == null ? 0L : 1L)
        .total(total)
        .max(total)
        .build());
      return null;
    }).findList();
    final List<MetricTimeseriesTopSeries> series = groups.stream()
      .map(group -> {
        final List<MetricTimeBucket> groupBuckets = buckets.get(group);
        return MetricTimeseriesTopSeries.builder()
          .group(group)
          .other(false)
          .hashCount(1L)
          .totalMicros(groupBuckets.stream().mapToLong(MetricTimeBucket::total).sum())
          .buckets(groupBuckets)
          .build();
      })
      .toList();
    return MetricTimeseriesTop.builder()
      .app(app.getName())
      .windowMinutes(window.minutes())
      .bucketMinutes(bucketMinutes)
      .series(series)
      .build();
  }

  /** Per-bucket datasource-pool wait and acquire timings grouped by pool type. */
  public MetricTimeseriesTop getDatasourcePoolTimingTimeseries(String appName,
                                                               @Nullable Long sinceMinutes,
                                                               @Nullable Long sinceHours,
                                                               @Nullable String env,
                                                               Instant from, Instant to) {
    final TimeWindow window = from == null
      ? TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES)
      : TimeWindow.between(from, to);
    final String table = timeseriesTableFor(window.minutes());
    final long bucketMinutes = topBucketMinutesFor(window.minutes(), table);
    final DApp app = findApp(appName);
    if (app == null) {
      return emptyTimeseriesTop(appName, window.minutes(), bucketMinutes);
    }
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }

    final List<String> metricNames = List.of(
      "datasource.pool.wait",
      "datasource.pool.acquire");
    final SqlQuery groupQuery = DB.sqlQuery("""
        select distinct m.tags ->> 'type' as grp
        from %s t
        join ebean_insight.app_metric m on m.id = t.metric_id
        where t.event_time > :from
          and t.event_time <= :to
          and m.app_id = :appId
          and m.name in (:waitName, :acquireName)
          and m.tags ->> 'type' is not null
          %s
        order by grp
        """.formatted(table, envId == null ? "" : "and t.env_id = :envId"))
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("appId", app.getId())
      .setParameter("waitName", metricNames.get(0))
      .setParameter("acquireName", metricNames.get(1));
    if (envId != null) {
      groupQuery.setParameter("envId", envId);
    }
    final List<String> groups = groupQuery
      .mapTo((rs, _) -> rs.getString("grp"))
      .findList();
    if (groups.isEmpty()) {
      return emptyTimeseriesTop(app.getName(), window.minutes(), bucketMinutes);
    }

    final String groupValues = groups.stream()
      .map(group -> "(:g" + groups.indexOf(group) + ")")
      .collect(java.util.stream.Collectors.joining(", "));
    final String sql = """
      with grid as (
        select to_timestamp(s) as event_time
        from generate_series(
          (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
          (cast(floor(extract(epoch from cast(:to as timestamptz)) / :step) as bigint)) * :step,
          :step
        ) as s
      ),
      groups (grp) as (values %s),
      metric_names (metric_name, metric_label) as (
        values (:waitName, 'Wait'), (:acquireName, 'Acquire')
      ),
      data as (
        select to_timestamp(
                 cast(floor(extract(epoch from t.event_time) / :step) as bigint) * :step
               ) as event_time,
               m.name as metric_name,
               m.tags ->> 'type' as grp,
               sum(t.total)::bigint as total
        from %s t
        join ebean_insight.app_metric m on m.id = t.metric_id
        where t.event_time > :from
          and t.event_time <= :to
          and m.app_id = :appId
          and m.name in (:waitName, :acquireName)
          %s
        group by 1, 2, 3
      )
      select grid.event_time, metric_names.metric_name, metric_names.metric_label,
             groups.grp, coalesce(data.total, 0) as total
      from grid
      cross join metric_names
      cross join groups
      left join data on data.event_time = grid.event_time
        and data.metric_name = metric_names.metric_name
        and data.grp = groups.grp
      order by grid.event_time, metric_names.metric_name, groups.grp
      """.formatted(groupValues, table, envId == null ? "" : "and t.env_id = :envId");
    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("step", bucketMinutes * 60L)
      .setParameter("appId", app.getId())
      .setParameter("waitName", metricNames.get(0))
      .setParameter("acquireName", metricNames.get(1));
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    for (int i = 0; i < groups.size(); i++) {
      query.setParameter("g" + i, groups.get(i));
    }

    final Map<String, List<MetricTimeBucket>> buckets = new LinkedHashMap<>();
    for (String metricName : metricNames) {
      for (String group : groups) {
        buckets.put(metricName + "|" + group, new ArrayList<>());
      }
    }
    query.mapTo((rs, _) -> {
      final String key = rs.getString("metric_name") + "|" + rs.getString("grp");
      buckets.get(key).add(MetricTimeBucket.builder()
        .eventTime(toInstant(rs.getTimestamp("event_time")))
        .count(1L)
        .total(rs.getLong("total"))
        .max(rs.getLong("total"))
        .build());
      return null;
    }).findList();

    final List<MetricTimeseriesTopSeries> series = new ArrayList<>(metricNames.size() * groups.size());
    for (String metricName : metricNames) {
      final String metricLabel = "datasource.pool.wait".equals(metricName) ? "Wait" : "Acquire";
      for (String group : groups) {
        final List<MetricTimeBucket> groupBuckets = buckets.get(metricName + "|" + group);
        series.add(MetricTimeseriesTopSeries.builder()
          .group(metricLabel + " · " + group)
          .other(false)
          .hashCount(1L)
          .totalMicros(groupBuckets.stream().mapToLong(MetricTimeBucket::total).sum())
          .buckets(groupBuckets)
          .build());
      }
    }
    return MetricTimeseriesTop.builder()
      .app(app.getName())
      .windowMinutes(window.minutes())
      .bucketMinutes(bucketMinutes)
      .series(series)
      .build();
  }

  private static MetricTimeseriesTop emptyTimeseriesTop(@Nullable String app, long minutes, long bucketMinutes) {
    return MetricTimeseriesTop.builder()
      .app(app)
      .windowMinutes(minutes)
      .bucketMinutes(bucketMinutes)
      .series(List.of())
      .build();
  }

  private record RankedLabel(String label, long hashCount, long total) {}

  /** Rank labels over the whole window by the requested sort key, returning the top {@code limit}. */
  private List<RankedLabel> rankLabels(DApp app, String table, TimeWindow window,
                                       @Nullable String name, @Nullable String kind, @Nullable String type,
                                       @Nullable Boolean planCapable, @Nullable Integer envId,
                                       String sortKey, int limit) {
    final String sql = ("""
      select
        m.tags ->> 'label'         as grp,
        count(distinct m.id)       as hash_count,
        coalesce(sum(t.total), 0)  as agg_total
      from %s t
      join ebean_insight.app_metric m on m.id = t.metric_id
      where t.event_time > :from
        and m.app_id = :appId
      """
      + (window.hasTo() ? "  and t.event_time <= :to\n" : "")
      + (isBlank(name) ? "" : "  and m.name = :name\n")
      + (isBlank(kind) ? "" : "  and m.tags ->> 'kind' = :kind\n")
      + (isBlank(type) ? "" : "  and m.tags ->> 'type' = :type\n")
      + (planCapable == null ? "" : "  and m.plan_capable = :planCapable\n")
      + (envId == null ? "" : "  and t.env_id = :envId\n")
      + """
        and m.tags ->> 'label' is not null
      group by grp
      order by %s desc
      limit :limit
      """).formatted(table, orderByExpression(sortKey));

    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("appId", app.getId())
      .setParameter("limit", limit);
    if (window.hasTo()) {
      query.setParameter("to", window.to());
    }
    bindCommonFilters(query, name, kind, type, planCapable, envId);
    return query
      .mapTo((rs, _) -> new RankedLabel(rs.getString("grp"), rs.getLong("hash_count"), rs.getLong("agg_total")))
      .findList();
  }

  /**
   * Dense grid (every bucket boundary across the window) × (top-N labels ∪
   * "Other") cross join, so every returned series has a value for every
   * bucket (empty combinations reported as explicit zeros).
   */
  private Map<String, List<MetricTimeBucket>> denseBucketsByGroup(DApp app, String table, TimeWindow window,
                                                                  @Nullable String name, @Nullable String kind,
                                                                  @Nullable String type, @Nullable Boolean planCapable,
                                                                  @Nullable Integer envId, List<RankedLabel> ranked) {
    return denseBucketsByGroup(app, table, bucketMinutesFor(table), window, name, kind, type, planCapable, envId, ranked);
  }

  /**
   * As above, but with an explicit {@code bucketMinutes} step decoupled from the
   * source table's native row granularity — rows are truncated (floor-divided)
   * to the requested step and summed, so a coarser step (e.g. 5-minute bars
   * sourced from the 1-minute {@code timed_m1} table) correctly aggregates
   * multiple underlying rows into one bucket rather than only matching rows
   * that happen to land exactly on a step boundary.
   */
  private Map<String, List<MetricTimeBucket>> denseBucketsByGroup(DApp app, String table, long bucketMinutes,
                                                                  TimeWindow window,
                                                                  @Nullable String name, @Nullable String kind,
                                                                  @Nullable String type, @Nullable Boolean planCapable,
                                                                  @Nullable Integer envId, List<RankedLabel> ranked) {
    final long stepSeconds = bucketMinutes * 60L;
    final String inList = namedGroupList(ranked.size());
    final String valuesList = groupsValuesList(ranked.size());

    final String sql = ("""
      with grid as (
        select to_timestamp(s) as event_time
        from generate_series(
               (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
               (cast(floor(extract(epoch from cast(:to as timestamptz)) / :step) as bigint)) * :step,
               :step
             ) as s
      ),
      groups (grp) as (
        values %s
      ),
      data as (
        select
          to_timestamp((cast(floor(extract(epoch from t.event_time) / :step) as bigint)) * :step) as event_time,
          case when m.tags ->> 'label' in (%s) then m.tags ->> 'label' else '%s' end as grp,
          sum(t.count) as cnt,
          sum(t.total) as tot,
          max(t.max)   as mx
        from %s t
        join ebean_insight.app_metric m on m.id = t.metric_id
        where t.event_time > :from
          and t.event_time <= :to
          and m.app_id = :appId
        """
      + (isBlank(name) ? "" : "    and m.name = :name\n")
      + (isBlank(kind) ? "" : "    and m.tags ->> 'kind' = :kind\n")
      + (isBlank(type) ? "" : "    and m.tags ->> 'type' = :type\n")
      + (planCapable == null ? "" : "    and m.plan_capable = :planCapable\n")
      + (envId == null ? "" : "    and t.env_id = :envId\n")
      + """
        group by 1, grp
      )
      select
        grid.event_time       as event_time,
        groups.grp            as grp,
        coalesce(data.cnt, 0) as count,
        coalesce(data.tot, 0) as total,
        coalesce(data.mx, 0)  as max
      from grid
      cross join groups
      left join data on data.event_time = grid.event_time and data.grp = groups.grp
      order by grid.event_time asc, groups.grp asc
      """).formatted(valuesList, inList, OTHER_SENTINEL, table);

    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("to", window.to())
      .setParameter("step", stepSeconds)
      .setParameter("appId", app.getId());
    for (int i = 0; i < ranked.size(); i++) {
      query.setParameter("g" + i, ranked.get(i).label());
    }
    bindCommonFilters(query, name, kind, type, planCapable, envId);

    final Map<String, List<MetricTimeBucket>> result = new LinkedHashMap<>();
    final List<BucketRow> rows = query
      .mapTo((rs, _) -> new BucketRow(
        rs.getString("grp"),
        toInstant(rs.getTimestamp("event_time")),
        rs.getLong("count"),
        rs.getLong("total"),
        rs.getLong("max")))
      .findList();
    for (BucketRow row : rows) {
      result.computeIfAbsent(row.grp(), k -> new ArrayList<>())
        .add(new MetricTimeBucket(row.eventTime(), row.count(), row.total(), row.max()));
    }
    return result;
  }

  private Map<String, List<MetricTimeBucket>> denseBucketsByHash(DApp app, String table, long bucketMinutes,
                                                                 TimeWindow window, String name, String label,
                                                                 @Nullable Integer envId, List<RankedLabel> ranked) {
    final long stepSeconds = bucketMinutes * 60L;
    final String inList = namedGroupList(ranked.size());
    final String valuesList = groupsValuesList(ranked.size());
    final String sql = ("""
      with grid as (
        select to_timestamp(s) as event_time
        from generate_series(
               (cast(floor(extract(epoch from cast(:from as timestamptz)) / :step) as bigint) + 1) * :step,
               (cast(floor(extract(epoch from now()) / :step) as bigint)) * :step,
               :step
             ) as s
      ),
      groups (grp) as (
        values %s
      ),
      data as (
        select
          to_timestamp((cast(floor(extract(epoch from t.event_time) / :step) as bigint)) * :step) as event_time,
          case when m.key in (%s) then m.key else '%s' end as grp,
          sum(t.count) as cnt,
          sum(t.total) as tot,
          max(t.max)   as mx
        from %s t
        join ebean_insight.app_metric m on m.id = t.metric_id
        where t.event_time > :from
          and m.app_id = :appId
          and m.tags ->> 'label' = :label
          and m.name = :name
        """
      + (envId == null ? "" : "    and t.env_id = :envId\n")
      + """
        group by 1, grp
      )
      select
        grid.event_time       as event_time,
        groups.grp            as grp,
        coalesce(data.cnt, 0) as count,
        coalesce(data.tot, 0) as total,
        coalesce(data.mx, 0)  as max
      from grid
      cross join groups
      left join data on data.event_time = grid.event_time and data.grp = groups.grp
      order by grid.event_time asc, groups.grp asc
      """).formatted(valuesList, inList, OTHER_SENTINEL, table);

    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("step", stepSeconds)
      .setParameter("appId", app.getId())
      .setParameter("label", label)
      .setParameter("name", name);
    for (int i = 0; i < ranked.size(); i++) {
      query.setParameter("g" + i, ranked.get(i).label());
    }
    if (envId != null) {
      query.setParameter("envId", envId);
    }

    final Map<String, List<MetricTimeBucket>> result = new LinkedHashMap<>();
    final List<BucketRow> rows = query
      .mapTo((rs, _) -> new BucketRow(
        rs.getString("grp"),
        toInstant(rs.getTimestamp("event_time")),
        rs.getLong("count"),
        rs.getLong("total"),
        rs.getLong("max")))
      .findList();
    for (BucketRow row : rows) {
      result.computeIfAbsent(row.grp(), k -> new ArrayList<>())
        .add(new MetricTimeBucket(row.eventTime(), row.count(), row.total(), row.max()));
    }
    return result;
  }

  private record BucketRow(String grp, Instant eventTime, long count, long total, long max) {}

  /** Scalar count of all distinct metric identities matching the filters over the whole window (used for "Other" hashCount). */
  private long grandHashCount(DApp app, String table, TimeWindow window,
                             @Nullable String name, @Nullable String kind, @Nullable String type,
                             @Nullable Boolean planCapable, @Nullable Integer envId) {
    final String sql = ("""
      select coalesce(count(distinct m.id), 0) as hash_count
      from %s t
      join ebean_insight.app_metric m on m.id = t.metric_id
      where t.event_time > :from
        and m.app_id = :appId
      """
      + (isBlank(name) ? "" : "  and m.name = :name\n")
      + (isBlank(kind) ? "" : "  and m.tags ->> 'kind' = :kind\n")
      + (isBlank(type) ? "" : "  and m.tags ->> 'type' = :type\n")
      + (planCapable == null ? "" : "  and m.plan_capable = :planCapable\n")
      + (envId == null ? "" : "  and t.env_id = :envId\n"))
      .formatted(table);

    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("appId", app.getId());
    bindCommonFilters(query, name, kind, type, planCapable, envId);
    final Long result = query.mapTo((rs, _) -> rs.getLong("hash_count")).findOne();
    return result == null ? 0L : result;
  }

  private static void bindCommonFilters(SqlQuery query, @Nullable String name, @Nullable String kind,
                                        @Nullable String type, @Nullable Boolean planCapable,
                                        @Nullable Integer envId) {
    if (!isBlank(name)) {
      query.setParameter("name", name);
    }
    if (!isBlank(kind)) {
      query.setParameter("kind", kind);
    }
    if (!isBlank(type)) {
      query.setParameter("type", type);
    }
    if (planCapable != null) {
      query.setParameter("planCapable", planCapable);
    }
    if (envId != null) {
      query.setParameter("envId", envId);
    }
  }

  /** {@code :g0, :g1, ...} named-parameter list for the top-N label values (used in the "Other" case expression). */
  private static String namedGroupList(int n) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(":g").append(i);
    }
    return sb.toString();
  }

  /** {@code (:g0), (:g1), ..., ('__other__')} VALUES list for the dense-grid groups CTE. */
  private static String groupsValuesList(int n) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append("(:g").append(i).append("), ");
    }
    sb.append("('").append(OTHER_SENTINEL).append("')");
    return sb.toString();
  }

  private static int clampSeriesLimit(@Nullable Integer seriesLimit) {
    if (seriesLimit == null) {
      return DEFAULT_SERIES_LIMIT;
    }
    return Math.max(1, Math.min(MAX_SERIES_LIMIT, seriesLimit));
  }

  /**
   * Ranked {@code top} query, aggregated at the level chosen by {@code by}:
   * {@code hash} (individual metrics), {@code name} (families), or any tag key
   * ({@code label} (default), {@code type}, {@code kind}, {@code db}, ...).
   *
   * <p>{@code orderBy=value} ranks gauge metrics (peak over the window) from the
   * {@code gauge_*} rollups; all other order keys rank timer metrics from the
   * {@code timed_*} rollups. When grouping by a tag, rows that do not carry that
   * tag are excluded (so a family that lacks the tag yields an empty list).
   */
  private List<TopGroup> runTopQuery(@Nullable DApp app, @Nullable String by, @Nullable String name,
                                     @Nullable String label, @Nullable String kind, @Nullable String type,
                                     @Nullable String orderBy,
                                     TimeWindow window, @Nullable Boolean planCapable,
                                     @Nullable String env, @Nullable Boolean allApps, int limit) {
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return List.of();
    }
    final String byKey = resolveBy(by);
    final boolean byHash = "hash".equals(byKey);
    final boolean byName = "name".equals(byKey);
    final boolean byTag = !byHash && !byName;

    final String sortKey = resolveOrderBy(orderBy);
    final boolean gauge = "value".equals(sortKey);
    final long minutes = window.minutes();
    final String table = gauge ? gaugeTableFor(minutes) : timedTableFor(minutes);

    // byKey is validated to a safe identifier charset by resolveBy, so the tag
    // expression is safe to inline. It must be inlined (not bound) so the
    // GROUP BY expression matches the SELECT expression textually (Postgres
    // cannot match parameterised group-by expressions to the select list).
    final String tagExpr = "m.tags ->> '" + byKey + "'";
    final String groupExpr = byHash ? "m.id" : byName ? "m.name" : tagExpr;
    final String grpSelect = byHash ? "m.key" : byName ? "m.name" : tagExpr;

    // Per-app by default: a name/tag shared by several apps yields one row per
    // app (app_count collapses to 1 so the APP is populated). An app-scoped
    // query is inherently single-app. Only allApps=true rolls a group up across
    // applications into a single cross-app row (APP blank).
    final boolean perApp = app != null || !Boolean.TRUE.equals(allApps);
    final String groupCols = perApp ? groupExpr + ", m.app_id" : groupExpr;

    final String sql = ("""
      select
        %s as grp,
        min(m.name)              as name,
        max(m.tags ->> 'label')  as label_tag,
        count(distinct m.app_id) as app_count,
        min(a.name)              as app_name,
        count(distinct m.id)     as hash_count,
        bool_or(m.plan_capable)  as plan_capable,
        min(m.key)               as mkey,
        min(m.loc)               as mloc,
        min(m.sql)               as msql,
        coalesce(sum(t.count), 0) as agg_count,
        coalesce(sum(t.total), 0) as agg_total,
        coalesce(max(t.max), 0)   as agg_max
      from %s t
      join ebean_insight.app_metric m on m.id = t.metric_id
      join ebean_insight.app a        on a.id = m.app_id
      where t.event_time > :from
      """
      + (window.hasTo() ? "  and t.event_time <= :to\n" : "")
      + (app == null ? "" : "  and a.id = :appId\n")
      + (isBlank(name) ? "" : "  and m.name = :name\n")
      + (isBlank(label) ? "" : "  and m.tags ->> 'label' = :label\n")
      + (isBlank(kind) ? "" : "  and m.tags ->> 'kind' = :kind\n")
      + (isBlank(type) ? "" : "  and m.tags ->> 'type' = :type\n")
      + (planCapable == null ? "" : "  and m.plan_capable = :planCapable\n")
      + (envId == null ? "" : "  and t.env_id = :envId\n")
      + (byTag ? "  and " + tagExpr + " is not null\n" : "")
      + """
      group by %s
      order by %s desc
      limit :limit
      """).formatted(grpSelect, table, groupCols, orderByExpression(sortKey));

    final SqlQuery sqlQuery = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("limit", limit);
    if (window.hasTo()) {
      sqlQuery.setParameter("to", window.to());
    }
    if (app != null) {
      sqlQuery.setParameter("appId", app.getId());
    }
    if (!isBlank(name)) {
      sqlQuery.setParameter("name", name);
    }
    if (!isBlank(label)) {
      sqlQuery.setParameter("label", label);
    }
    if (!isBlank(kind)) {
      sqlQuery.setParameter("kind", kind);
    }
    if (!isBlank(type)) {
      sqlQuery.setParameter("type", type);
    }
    if (planCapable != null) {
      sqlQuery.setParameter("planCapable", planCapable);
    }
    if (envId != null) {
      sqlQuery.setParameter("envId", envId);
    }
    final String byKeyFinal = byKey;
    return sqlQuery.mapTo((rs, _) -> toTopGroup(rs, byKeyFinal, byHash, gauge, minutes)).findList();
  }

  /**
   * Every distinct label for {@code app} that is {@code root} itself or
   * starts with {@code root + "."}, each aggregated as one row (same shape
   * as {@link #runTopQuery} with {@code by=label}). Used to build the
   * "query family" tree relating a label to its dot-hierarchy ancestor and
   * descendant fetch-path queries.
   */
  private List<TopGroup> runLabelFamilyQuery(DApp app, String root, @Nullable String name,
                                             TimeWindow window, @Nullable String env, int limit) {
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return List.of();
    }
    final long minutes = window.minutes();
    final String table = timedTableFor(minutes);
    final String tagExpr = "m.tags ->> 'label'";

    final String sql = ("""
      select
        %s as grp,
        min(m.name)              as name,
        max(m.tags ->> 'label')  as label_tag,
        count(distinct m.app_id) as app_count,
        min(a.name)              as app_name,
        count(distinct m.id)     as hash_count,
        bool_or(m.plan_capable)  as plan_capable,
        min(m.key)               as mkey,
        min(m.loc)               as mloc,
        min(m.sql)               as msql,
        coalesce(sum(t.count), 0) as agg_count,
        coalesce(sum(t.total), 0) as agg_total,
        coalesce(max(t.max), 0)   as agg_max
      from %s t
      join ebean_insight.app_metric m on m.id = t.metric_id
      join ebean_insight.app a        on a.id = m.app_id
      where t.event_time > :from
        and a.id = :appId
        and (%s = :root or %s like :rootLike)
      """
      + (isBlank(name) ? "" : "  and m.name = :name\n")
      + (envId == null ? "" : "  and t.env_id = :envId\n")
      + """
      group by %s, m.app_id
      order by %s desc
      limit :limit
      """).formatted(tagExpr, table, tagExpr, tagExpr, tagExpr, orderByExpression("total"));

    final SqlQuery sqlQuery = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("appId", app.getId())
      .setParameter("root", root)
      .setParameter("rootLike", root + ".%")
      .setParameter("limit", limit);
    if (!isBlank(name)) {
      sqlQuery.setParameter("name", name);
    }
    if (envId != null) {
      sqlQuery.setParameter("envId", envId);
    }
    return sqlQuery.mapTo((rs, _) -> toTopGroup(rs, "label", false, false, minutes)).findList();
  }

  private static TopGroup toTopGroup(ResultSet rs, String byKey, boolean byHash,
                                     boolean gauge, long minutes) throws SQLException {
    final String grp = rs.getString("grp");
    final String name = rs.getString("name");
    final long appCount = rs.getLong("app_count");
    final var b = TopGroup.builder()
      .group(grp)
      .name(name)
      .app(appCount == 1L ? rs.getString("app_name") : null)
      .hashCount(rs.getLong("hash_count"))
      .planCapable(rs.getBoolean("plan_capable"))
      .label(topGroupLabel(byKey, byHash, grp, name, rs.getString("label_tag")))
      .windowMinutes(minutes);
    if (byHash) {
      b.key(rs.getString("mkey")).loc(rs.getString("mloc")).sql(rs.getString("msql"));
    }
    final long max = rs.getLong("agg_max");
    if (gauge) {
      b.value((double) max);
    } else {
      final long count = rs.getLong("agg_count");
      final long total = rs.getLong("agg_total");
      b.count(count)
        .totalMicros(total)
        .meanMicros(count == 0L ? 0L : Math.floorDiv(total, count))
        .maxMicros(max);
    }
    return b.build();
  }

  @Nullable
  private static String topGroupLabel(String byKey, boolean byHash, @Nullable String grp,
                                      String name, @Nullable String labelTag) {
    if (byHash) {
      return labelTag != null ? labelTag : name;
    }
    if ("label".equals(byKey)) {
      return grp;
    }
    return null;
  }

  public List<MissingPlanMetric> listMissingPlans(String appName, @Nullable String orderBy,
                                                  @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                  @Nullable Long olderThanMinutes,
                                                  @Nullable Long olderThanHours,
                                                  @Nullable Integer limit, @Nullable String env) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runMissingPlansQuery(app, orderBy, sinceMinutes, sinceHours,
      olderThanMinutes, olderThanHours, env, clampLimit(limit));
  }

  public List<MissingPlanMetric> topMissingPlans(@Nullable String orderBy,
                                                 @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                 @Nullable Long olderThanMinutes,
                                                 @Nullable Long olderThanHours,
                                                 @Nullable Integer limit, @Nullable String env) {
    return runMissingPlansQuery(null, orderBy, sinceMinutes, sinceHours,
      olderThanMinutes, olderThanHours, env, clampLimit(limit));
  }

  private List<MissingPlanMetric> runMissingPlansQuery(@Nullable DApp app, @Nullable String orderBy,
                                                       @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                       @Nullable Long olderThanMinutes,
                                                       @Nullable Long olderThanHours,
                                                       @Nullable String env, int limit) {
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return List.of();
    }
    final String sortKey = resolveOrderBy(orderBy);
    final TimeWindow costWindow = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    final TimeWindow freshness = TimeWindow.of(olderThanMinutes, olderThanHours, 0L);
    final boolean hasFreshness = freshness.hasFrom();
    final long minutes = costWindow.minutes();
    final String table = timedTableFor(minutes);
    final String sql = ("""
      select
        m.id              as metric_id,
        a.name            as app_name,
        coalesce(m.tags ->> 'label', m.name) as label,
        m.key             as key,
        m.loc             as loc,
        m.sql             as sql,
        agg.last_captured as last_captured,
        coalesce(agg.capture_count, 0) as capture_count,
        coalesce(sum(t.count), 0) as agg_count,
        coalesce(sum(t.total), 0) as agg_total,
        coalesce(max(t.max), 0)   as agg_max
      from ebean_insight.app_metric m
      join ebean_insight.app a on a.id = m.app_id
      left join %s t on t.metric_id = m.id and t.event_time > :from"""
      + (envId == null ? "" : " and t.env_id = :envId")
      + """

      left join (
        select metric_id,
               max(when_captured) as last_captured,
               count(*)           as capture_count
        from ebean_insight.query_plan
        group by metric_id
      ) agg on agg.metric_id = m.id
      where m.plan_capable = true
      """
      + (app == null ? "" : "  and m.app_id = :appId\n")
      + """
        and (
          agg.last_captured is null
      """
      + (hasFreshness ? "      or agg.last_captured < :threshold\n" : "")
      + """
        )
      group by m.id, a.name, m.name, m.key, m.loc, m.sql, agg.last_captured, agg.capture_count
      having coalesce(sum(t.count), 0) > 0
      order by %s desc, m.name asc
      limit :limit
      """).formatted(table, orderByExpression(sortKey));

    final SqlQuery query = DB.sqlQuery(sql)
      .setParameter("from", costWindow.from())
      .setParameter("limit", limit);
    if (app != null) {
      query.setParameter("appId", app.getId());
    }
    if (hasFreshness) {
      query.setParameter("threshold", freshness.from());
    }
    if (envId != null) {
      query.setParameter("envId", envId);
    }
    return query
      .mapTo((rs, _) -> toMissingPlanMetric(rs, minutes))
      .findList();
  }

  private static MissingPlanMetric toMissingPlanMetric(ResultSet rs, long windowMinutes) throws SQLException {
    final long count = rs.getLong("agg_count");
    final long total = rs.getLong("agg_total");
    final long max = rs.getLong("agg_max");
    final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
    return MissingPlanMetric.builder()
      .id(rs.getLong("metric_id"))
      .app(rs.getString("app_name"))
      .label(rs.getString("label"))
      .key(rs.getString("key"))
      .loc(rs.getString("loc"))
      .lastCapturedAt(toInstant(rs.getTimestamp("last_captured")))
      .captureCount(rs.getLong("capture_count"))
      .sql(rs.getString("sql"))
      .count(count)
      .totalMicros(total)
      .meanMicros(mean)
      .maxMicros(max)
      .windowMinutes(windowMinutes)
      .build();
  }

  // ---------------------------------------------------------------------------
  // Regression plan detection
  // ---------------------------------------------------------------------------

  /**
   * Returns plan-capable metrics whose mean execution time over the last
   * {@code recentHours} hours has regressed by at least {@code ratio} times
   * compared to their mean over the prior {@code baselineDays} days.
   *
   * <p>A minimum baseline mean of {@code minMicros} prevents noise from very
   * fast queries with large percentage swings. A minimum of {@code minCount}
   * calls in both windows filters single-execution spikes.
   */
  public List<RegressionPlanMetric> topRegressionPlans(int recentHours, int baselineDays,
                                                       double ratio, long minMicros,
                                                       int minCount, int limit) {
    final Instant now = Instant.now();
    final Instant recentFrom = now.minus(Duration.ofHours(recentHours));
    final Instant baselineFrom = recentFrom.minus(Duration.ofDays(baselineDays));
    final String sql = """
      WITH recent AS (
        SELECT metric_id,
               SUM(total) / NULLIF(SUM(count), 0) AS mean
        FROM ebean_insight.timed_m60
        WHERE event_time > :recentFrom
        GROUP BY metric_id
        HAVING SUM(count) >= :minCount
      ),
      baseline AS (
        SELECT metric_id,
               SUM(total) / NULLIF(SUM(count), 0) AS mean
        FROM ebean_insight.timed_m60
        WHERE event_time > :baselineFrom AND event_time <= :recentFrom
        GROUP BY metric_id
        HAVING SUM(count) >= :minCount
      )
      SELECT m.id              AS metric_id,
             a.name            AS app_name,
             COALESCE(m.tags ->> 'label', m.name) AS label,
             m.key             AS key,
             r.mean            AS recent_mean,
             b.mean            AS baseline_mean
      FROM recent r
      JOIN baseline b ON b.metric_id = r.metric_id
      JOIN ebean_insight.app_metric m ON m.id = r.metric_id
      JOIN ebean_insight.app a ON a.id = m.app_id
      WHERE m.plan_capable = true
        AND b.mean > :minMicros
        AND r.mean > b.mean * :ratio
      ORDER BY (r.mean::double precision / NULLIF(b.mean, 1)) DESC, m.name ASC
      LIMIT :limit
      """;
    return DB.sqlQuery(sql)
      .setParameter("recentFrom", recentFrom)
      .setParameter("baselineFrom", baselineFrom)
      .setParameter("minCount", minCount)
      .setParameter("minMicros", minMicros)
      .setParameter("ratio", ratio)
      .setParameter("limit", limit)
      .mapTo((rs, _) -> new RegressionPlanMetric(
        rs.getString("app_name"),
        rs.getString("key"),
        rs.getString("label"),
        rs.getLong("recent_mean"),
        rs.getLong("baseline_mean")
      ))
      .findList();
  }

  // ---------------------------------------------------------------------------
  // Plans
  // ---------------------------------------------------------------------------

  public List<QueryPlanSummary> listPlans(@Nullable String app, @Nullable String env,
                                          @Nullable String label, @Nullable String hash,
                                          @Nullable String kind, @Nullable String type,
                                          @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                          @Nullable Integer limit) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, 0L);
    final DApp resolved;
    if (app != null && !app.isBlank()) {
      resolved = findApp(app);
      if (resolved == null) {
        return List.of();
      }
    } else {
      resolved = null;
    }
    return runPlanSummaryQuery(resolved, env, label, hash, kind, type, window, clampLimit(limit));
  }

  /**
   * Returns the SQL stored for an application metric identified by its label
   * and query hash.
   */
  @Nullable
  public String getMetricSql(String appName, String label, String hash) {
    final String sql = """
      select m.sql
      from ebean_insight.app_metric m
      join ebean_insight.app a on a.id = m.app_id
      where a.name = :app
        and m.key = :hash
        and m.tags ->> 'label' = :label
      limit 1
      """;
    return DB.sqlQuery(sql)
      .setParameter("app", appName)
      .setParameter("hash", hash)
      .setParameter("label", label)
      .mapTo((rs, _) -> rs.getString("sql"))
      .findList()
      .stream()
      .findFirst()
      .orElse(null);
  }

  public QueryPlan getPlan(long planId) {
    final DQueryPlan plan = new QDQueryPlan().id.eq((int) planId).findOne();
    if (plan == null) {
      throw new NotFoundException("No query plan with id " + planId);
    }
    return toQueryPlan(plan);
  }

  /**
   * The owning app name for a captured query plan. Kept separate from
   * {@link #getPlan(long)}/the public {@code QueryPlan} API model (which is
   * generated from the OpenAPI contract and intentionally doesn't carry an
   * app name) - used only by the UI drill-down page to link back to
   * {@code /ux/metric-detail} for the plan's app.
   */
  public String getPlanAppName(long planId) {
    final DQueryPlan plan = new QDQueryPlan().id.eq((int) planId).findOne();
    if (plan == null) {
      throw new NotFoundException("No query plan with id " + planId);
    }
    return plan.app().getName();
  }

  public PendingResponse requestPlanCapture(String appName, String hash, @Nullable String env) {
    if (hash == null || hash.isBlank()) {
      throw new BadRequestException("hash is required");
    }
    final DApp app = requireApp(appName);
    final DAppMetric metric = new QDAppMetric()
      .app.eq(app)
      .key.eq(hash.trim())
      .findOne();
    if (metric == null) {
      throw new NotFoundException("No metric for app=" + appName + " hash=" + hash);
    }
    if (!metric.isPlanCapable()) {
      throw new BadRequestException(
        "Metric '" + metric.getName() + "' is not plan-capable; only orm.*, dto.* and sql.query.* metrics support plan capture");
    }
    final boolean anyEnv = (env == null || env.isBlank());
    final String envName = anyEnv ? null : env.trim();
    final String message = "qp:" + metric.getKey();
    final String routeEnv = anyEnv ? MessageService.ANY_ENV : envName;
    final int pending = messageService.pushMessage(app.getName(), routeEnv, message);
    recordCaptureRequest(app, envName, metric);
    return new PendingResponse(pending, app.getName(), anyEnv ? MessageService.ANY_ENV : envName, metric.getName());
  }

  /**
   * Persist a durable record of the capture request so the pending view
   * survives forwarder polls and server restarts (see {@link DCaptureRequest}).
   * A null {@code envName} records an "any environment" request (env is filled
   * in from the plan that is ultimately collected).
   */
  private void recordCaptureRequest(DApp app, @Nullable String envName, DAppMetric metric) {
    new DCaptureRequest(app, metric.getKey())
      .setEnv(envName == null ? null : findOrCreateEnv(envName))
      .setLabel(metric.getName())
      .setRequestedAt(Instant.now())
      .save();
  }

  /**
   * Push and record a capture request from an internal trigger (e.g. rollup-based
   * auto-capture). Bypasses HTTP-layer validation; the caller must have already
   * determined the metric is plan-capable. Uses {@code ANY_ENV}: delivered to
   * whichever forwarder polls first, regardless of environment.
   */
  public void autoPushCapture(String appName, String metricKey, String metricLabel) {
    final DApp app = findApp(appName);
    if (app == null) {
      return;
    }
    messageService.pushMessage(appName, MessageService.ANY_ENV, "qp:" + metricKey);
    new DCaptureRequest(app, metricKey)
      .setLabel(metricLabel)
      .setRequestedAt(Instant.now())
      .save();
  }

  public List<PendingPlan> listPendingPlans(@Nullable String app, @Nullable String env,
                                            @Nullable String hash, @Nullable String label) {
    final Instant from = Instant.now().minus(Duration.ofMinutes(PENDING_STALE_MINUTES));
    final QDCaptureRequest q = new QDCaptureRequest()
      .collectedAt.isNull()
      .requestedAt.gt(from)
      .app.name.eqIfNotBlank(app)
      .hash.eqIfNotBlank(hash)
      .label.eqIfNotBlank(label);
    if (env != null && !env.isBlank()) {
      // include "any environment" requests (env is null) when filtering by env,
      // since such a request may yet be collected in the requested environment
      q.env.name.eqOrNull(env.trim());
    }
    return q
      .orderBy().requestedAt.asc()
      .findList()
      .stream()
      .map(r -> PendingPlan.builder()
        .app(r.app().getName())
        .env(r.env() == null ? MessageService.ANY_ENV : r.env().getName())
        .hash(r.hash())
        .label(r.label())
        .requestedAt(r.requestedAt())
        .build())
      .toList();
  }

  // ---------------------------------------------------------------------------
  // Plan change events
  // ---------------------------------------------------------------------------

  public List<PlanChange> listPlanChanges(@Nullable String app, @Nullable String env,
                                          @Nullable String hash, @Nullable String changeType,
                                          @Nullable String label, @Nullable String kind,
                                          @Nullable String type,
                                          @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                          @Nullable Integer limit) {
    final DQueryPlanChange.ChangeType ct = parseChangeType(changeType);
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, 0L);
    final DApp resolved;
    if (app != null && !app.isBlank()) {
      resolved = findApp(app);
      if (resolved == null) {
        return List.of();
      }
    } else {
      resolved = null;
    }
    return new QDQueryPlanChange()
      .app.fetch()
      .env.fetch()
      .app.eqIfPresent(resolved)
      .env.name.eqIfNotBlank(env)
      .hash.eqIfNotBlank(hash)
      .label.eqIfNotBlank(label)
      .kind.eqIfNotBlank(kind)
      .type.eqIfNotBlank(type)
      .changeType.eqIfPresent(ct)
      .detectedAt.gtIfPresent(window.from())
      .orderBy().detectedAt.desc().id.desc()
      .setMaxRows(clampLimit(limit))
      .findList()
      .stream()
      .map(V1QueryService::toPlanChange)
      .toList();
  }

  public PlanChangeDetail getPlanChange(long id) {
    final DQueryPlanChange c = new QDQueryPlanChange()
      .id.eq((int) id)
      .app.fetch()
      .env.fetch()
      .findOne();
    if (c == null) {
      throw new NotFoundException("No plan change with id " + id);
    }
    return PlanChangeDetail.builder()
      .change(toPlanChange(c))
      .fromPlan(c.fromPlan() == null ? null : toQueryPlan(c.fromPlan()))
      .toPlan(toQueryPlan(c.toPlan()))
      .build();
  }

  private static DQueryPlanChange.@Nullable ChangeType parseChangeType(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return DQueryPlanChange.ChangeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Invalid changeType '" + value + "'; expected FIRST or CHANGED");
    }
  }
  // ---------------------------------------------------------------------------
  // Envs
  // ---------------------------------------------------------------------------

  public List<Env> listEnvs() {
    return new QDEnv()
      .orderBy().name.asc()
      .setMaxRows(MAX_ROWS_GUARD)
      .findList()
      .stream()
      .map(e -> new Env(e.getName()))
      .toList();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private List<QueryPlanSummary> runPlanSummaryQuery(@Nullable DApp app, @Nullable String env,
                                                     @Nullable String label, @Nullable String hash,
                                                     @Nullable String kind, @Nullable String type,
                                                     TimeWindow window, int limit) {
    final List<DQueryPlan> plans = new QDQueryPlan()
      .app.eqIfPresent(app)
      .env.name.eqIfNotBlank(env)
      .label.eqIfNotBlank(label)
      .hash.eqIfNotBlank(hash)
      .kind.eqIfNotBlank(kind)
      .type.eqIfNotBlank(type)
      .whenCreated.gtIfPresent(window.from())
      .orderBy().whenCreated.desc()
      .setMaxRows(limit)
      .findList();

    final Set<Long> changedIds = computeShapeChanges(plans);
    final List<QueryPlanSummary> result = new ArrayList<>(plans.size());
    for (DQueryPlan p : plans) {
      result.add(toQueryPlanSummary(p, changedIds.contains((long) p.getId())));
    }
    return result;
  }

  /**
   * Determine which plans are shape "change points" within their (env, hash) series.
   *
   * <p>A change point is a capture whose non-null planShapeHash differs from the most
   * recent earlier non-null planShapeHash in the same series. The earliest captured
   * shape is a baseline (not a change), and placeholder rows with a null shape are
   * neither a baseline nor a change point (they are skipped, carrying the baseline
   * forward).
   */
  static Set<Long> computeShapeChanges(List<DQueryPlan> plans) {
    final Map<String, List<DQueryPlan>> series = new LinkedHashMap<>();
    for (DQueryPlan p : plans) {
      final String key = p.env().getName() + '\u0000' + p.hash();
      series.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
    }
    final Comparator<DQueryPlan> asc = Comparator
      .comparing(DQueryPlan::whenCaptured, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparingInt(DQueryPlan::getId);

    final Set<Long> changed = new HashSet<>();
    for (List<DQueryPlan> group : series.values()) {
      group.sort(asc);
      String last = null;
      for (DQueryPlan p : group) {
        final String shape = p.planShapeHash();
        if (shape == null) {
          continue;
        }
        if (last != null && !shape.equals(last)) {
          changed.add((long) p.getId());
        }
        last = shape;
      }
    }
    return changed;
  }

  @Nullable
  private DApp findApp(@Nullable String appName) {
    if (appName == null || appName.isBlank()) {
      return null;
    }
    return new QDApp().name.eq(appName.trim()).findOne();
  }

  /**
   * Resolve an environment by name, creating it if it does not yet exist.
   * A capture request may name an env before any metrics for it have been
   * ingested, so we cannot assume the {@link DEnv} row already exists.
   */
  private DEnv findOrCreateEnv(String envName) {
    final DEnv found = new QDEnv().name.eq(envName).findOne();
    if (found != null) {
      return found;
    }
    final DEnv created = new DEnv(envName);
    created.save();
    return created;
  }

  /**
   * Resolve an environment name to its id, or {@code null} when no env filter
   * was requested or the named environment does not exist. Pair with
   * {@link #envFilterMisses} to distinguish "no filter" from "unknown env".
   */
  @Nullable
  private static Integer resolveEnvId(@Nullable String env) {
    if (env == null || env.isBlank()) {
      return null;
    }
    final DEnv found = new QDEnv().name.eq(env.trim()).findOne();
    return found == null ? null : found.getId();
  }

  /**
   * True when an env filter was requested but no matching environment exists,
   * in which case the caller should short-circuit to an empty result.
   */
  private static boolean envFilterMisses(@Nullable String env, @Nullable Integer envId) {
    return env != null && !env.isBlank() && envId == null;
  }

  /**
   * Select the coarsest timed rollup table whose retention covers the requested
   * window, to bound the number of rows scanned for long windows. The returned
   * value is one of a fixed set of schema-qualified table names (never derived
   * from user input), so it is safe to interpolate into SQL.
   */
  static String timedTableFor(long windowMinutes) {
    if (windowMinutes <= M1_MAX_MINUTES) {
      return "ebean_insight.timed_m1";
    }
    if (windowMinutes <= M10_MAX_MINUTES) {
      return "ebean_insight.timed_m10";
    }
    if (windowMinutes <= M60_MAX_MINUTES) {
      return "ebean_insight.timed_m60";
    }
    return "ebean_insight.timed_d1";
  }

  /**
   * Table selection for the single-hash timeseries. Unlike {@link #timedTableFor}
   * — which tiers coarsely to bound row scans for cross-metric aggregation — this
   * picks the <em>finest</em> rollup that keeps the bucket count within
   * {@link #TS_MAX_BUCKETS} and is still within the table's retention. Scanning a
   * single hash at fine resolution is cheap, and the finer grid keeps the trend
   * chart a consistent width across windows: e.g. a 6h window stays
   * 1-minute/360 buckets instead of dropping to 10-minute/36 buckets at the 3h
   * aggregation boundary.
   */
  static String timeseriesTableFor(long windowMinutes) {
    if (windowMinutes <= TS_MAX_BUCKETS) {
      return "ebean_insight.timed_m1";
    }
    if (windowMinutes <= TS_MAX_BUCKETS * 10) {
      return "ebean_insight.timed_m10";
    }
    if (windowMinutes <= TS_MAX_BUCKETS * 60) {
      return "ebean_insight.timed_m60";
    }
    return "ebean_insight.timed_d1";
  }

  /** Gauge rollup table covering the window (parallels {@link #timedTableFor(long)}). */
  static String gaugeTableFor(long windowMinutes) {
    if (windowMinutes <= TS_MAX_BUCKETS) {
      return "ebean_insight.gauge_m1";
    }
    if (windowMinutes <= M10_MAX_MINUTES) {
      return "ebean_insight.gauge_m10";
    }
    if (windowMinutes <= M60_MAX_MINUTES) {
      return "ebean_insight.gauge_m60";
    }
    return "ebean_insight.gauge_d1";
  }

  static long gaugeBucketMinutesFor(long windowMinutes, String table) {
    if ("ebean_insight.gauge_m1".equals(table)) {
      return topBucketMinutesFor(windowMinutes, "ebean_insight.timed_m1");
    }
    if ("ebean_insight.gauge_m10".equals(table)) {
      return topBucketMinutesFor(windowMinutes, "ebean_insight.timed_m10");
    }
    return bucketMinutesFor(table.replace("gauge_", "timed_"));
  }

  /**
   * Resolve the {@code by} aggregation key. {@code hash} and {@code name} are
   * structural; anything else is treated as a tag key (default {@code label}).
   * The value is only ever bound as a parameter (never interpolated into SQL).
   */
  private static String resolveBy(@Nullable String by) {
    if (by == null || by.isBlank()) {
      return "label";
    }
    final String key = by.trim();
    if (!SAFE_TAG_KEY.matcher(key).matches()) {
      throw new BadRequestException(
        "Invalid 'by' value '" + by + "'; expected hash, name, or a tag key (letters, digits, _, ., -)");
    }
    return key;
  }

  private static boolean isBlank(@Nullable String s) {
    return s == null || s.isBlank();
  }

  private DApp requireApp(String appName) {
    final DApp app = findApp(appName);
    if (app == null) {
      throw new NotFoundException("No app named '" + appName + "'");
    }
    return app;
  }

  private static int clampLimit(@Nullable Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  @Nullable
  private static Instant toInstant(@Nullable Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static String resolveOrderBy(@Nullable String orderBy) {
    if (orderBy == null || orderBy.isBlank()) {
      return "total";
    }
    final String key = orderBy.trim().toLowerCase(Locale.ROOT);
    if (!ORDER_BY_KEYS.contains(key)) {
      throw new BadRequestException(
        "Unknown orderBy '" + orderBy + "'; expected one of " + ORDER_BY_KEYS);
    }
    return key;
  }

  private static String orderByExpression(String key) {
    return switch (key) {
      case "mean" -> "case when sum(t.count) = 0 then 0 else sum(t.total) / sum(t.count) end";
      case "max" -> "max(t.max)";
      case "count" -> "sum(t.count)";
      case "value" -> "max(t.max)";
      default -> "sum(t.total)";
    };
  }

  // ---------------------------------------------------------------------------
  // DTO mappers (domain → generated record)
  // ---------------------------------------------------------------------------

  static App toApp(DApp app) {
    return new App((long) app.getId(), app.getName());
  }

  static Env toEnv(DEnv env) {
    return new Env(env.getName());
  }

  AppMetric toAppMetric(DAppMetric m) {
    return AppMetric.builder()
      .id((long) m.getId())
      .name(m.getName())
      .label(displayLabel(m.getName(), m.getTags()))
      .tags(tagsToStringMap(m.getTags()))
      .key(m.getKey())
      .loc(m.getLoc())
      .sql(m.getSql())
      .createdAt(m.getWhenCreated())
      .modifiedAt(m.getWhenModified())
      .build();
  }

  /** Display label: the {@code label} tag when present, otherwise the family name. */
  String displayLabel(String name, @Nullable Map<String, String> tags) {
    if (tags != null) {
      final String label = tags.get("label");
      if (label != null) {
        return label;
      }
    }
    return name;
  }

  @Nullable
  Map<String, String> tagsToStringMap(@Nullable Map<String, String> tags) {
    return tags == null || tags.isEmpty() ? null : tags;
  }

  static QueryPlanSummary toQueryPlanSummary(DQueryPlan p) {
    return toQueryPlanSummary(p, false);
  }

  static QueryPlanSummary toQueryPlanSummary(DQueryPlan p, boolean shapeChanged) {
    return QueryPlanSummary.builder()
      .id((long) p.getId())
      .appMetricId(p.metric() == null ? 0L : (long) p.metric().getId())
      .envName(p.env().getName())
      .hash(p.hash())
      .name(p.name())
      .label(p.label())
      .tags(planTags(p.kind(), p.type(), p.label()))
      .queryTimeMicros(p.queryTimeMicros())
      .captureCount(p.captureCount())
      .whenCaptured(p.whenCaptured())
      .planShapeHash(p.planShapeHash())
      .shapeChanged(shapeChanged)
      .build();
  }

  static QueryPlan toQueryPlan(DQueryPlan p) {
    return QueryPlan.builder()
      .id((long) p.getId())
      .hash(p.hash())
      .name(p.name())
      .label(p.label())
      .tags(planTags(p.kind(), p.type(), p.label()))
      .appMetricId(p.metric() == null ? 0L : (long) p.metric().getId())
      .envName(p.env().getName())
      .queryTimeMicros(p.queryTimeMicros())
      .captureCount(p.captureCount())
      .captureMicros(p.captureMicros())
      .whenCaptured(p.whenCaptured())
      .sql(p.sql())
      .bind(p.bind())
      .plan(p.plan())
      .planShape(p.planShape())
      .planShapeHash(p.planShapeHash())
      .planShapeAlgo(p.planShapeAlgo())
      .build();
  }

  static PlanChange toPlanChange(DQueryPlanChange c) {
    return PlanChange.builder()
      .id((long) c.getId())
      .appName(c.app().getName())
      .envName(c.env().getName())
      .hash(c.hash())
      .name(c.name())
      .label(c.label())
      .tags(planTags(c.kind(), c.type(), c.label()))
      .changeType(c.changeType().name())
      .fromPlanId(c.fromPlan() == null ? null : (long) c.fromPlan().getId())
      .toPlanId((long) c.toPlan().getId())
      .fromShapeHash(c.fromShapeHash())
      .toShapeHash(c.toShapeHash())
      .algo(c.algo())
      .fromQueryTimeMicros(c.fromQueryTimeMicros())
      .toQueryTimeMicros(c.toQueryTimeMicros())
      .whenCaptured(c.whenCaptured())
      .detectedAt(c.detectedAt())
      .build();
  }

  private static Map<String, String> planTags(@Nullable String kind, @Nullable String type, @Nullable String label) {
    final Map<String, String> tags = new java.util.LinkedHashMap<>();
    if (kind != null) {
      tags.put("kind", kind);
    }
    if (type != null) {
      tags.put("type", type);
    }
    if (label != null) {
      tags.put("label", label);
    }
    return tags;
  }
}
