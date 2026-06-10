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
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDCaptureRequest;
import org.ebean.monitor.domain.query.QDEnv;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.PendingPlan;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.web.MessageService;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

  private static final Set<String> ORDER_BY_KEYS = Set.of("total", "mean", "max", "count");

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
      """;
    return DB.sqlQuery(sql)
      .setParameter("from", window.from())
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

  public List<AppMetric> listAppMetrics(String appName, @Nullable String label,
                                        @Nullable Boolean planCapable, @Nullable Integer limit) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    final QDAppMetric q = new QDAppMetric().app.eq(app);
    q.name.eqIfNotBlank(label);
    if (planCapable != null) {
      q.planCapable.eq(planCapable);
    }
    return q
      .orderBy().name.asc()
      .setMaxRows(clampLimit(limit))
      .findList()
      .stream()
      .map(V1QueryService::toAppMetric)
      .toList();
  }

  public List<AppMetric> listMetricsByLabel(String appName, String label) {
    final DApp app = findApp(appName);
    if (app == null || label == null || label.isBlank()) {
      return List.of();
    }
    return new QDAppMetric()
      .app.eq(app)
      .name.eq(label.trim())
      .orderBy().key.asc()
      .findList()
      .stream()
      .map(V1QueryService::toAppMetric)
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

  private static AppMetricStats toAppMetricStats(ResultSet rs, DApp app, DAppMetric metric, long minutes) throws SQLException {
    final long count = rs.getLong("count");
    final long total = rs.getLong("total");
    final long max = rs.getLong("max");
    final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
    return AppMetricStats.builder()
      .id((long) metric.getId())
      .app(app.getName())
      .label(metric.getName())
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
   * follows {@link #timedTableFor(long)} so long windows stay cheap.
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
    final String table = timedTableFor(minutes);
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
      .label(metric.getName())
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

  public List<AppMetricStats> topAppMetrics(String appName, @Nullable String orderBy,
                                            @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                            @Nullable Integer limit, @Nullable Boolean planCapable,
                                            @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runTopQuery(app, orderBy, window, planCapable, env, clampLimit(limit));
  }

  public List<AppMetricStats> topMetrics(@Nullable String orderBy,
                                         @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                         @Nullable Integer limit, @Nullable Boolean planCapable,
                                         @Nullable String env) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return runTopQuery(null, orderBy, window, planCapable, env, clampLimit(limit));
  }

  private List<AppMetricStats> runTopQuery(@Nullable DApp app, @Nullable String orderBy,
                                           TimeWindow window, @Nullable Boolean planCapable,
                                           @Nullable String env, int limit) {
    final Integer envId = resolveEnvId(env);
    if (envFilterMisses(env, envId)) {
      return List.of();
    }
    final String sortKey = resolveOrderBy(orderBy);
    final String table = timedTableFor(window.minutes());
    final String sql = ("""
      select
        m.id          as metric_id,
        a.name        as app_name,
        m.name        as label,
        m.key         as key,
        m.loc         as loc,
        m.plan_capable as plan_capable,
        coalesce(sum(t.count), 0) as agg_count,
        coalesce(sum(t.total), 0) as agg_total,
        coalesce(max(t.max), 0)   as agg_max
      from %s t
      join ebean_insight.app_metric m on m.id = t.metric_id
      join ebean_insight.app a        on a.id = m.app_id
      where t.event_time > :from
      """
      + (app == null ? "" : "  and a.id = :appId\n")
      + (planCapable == null ? "" : "  and m.plan_capable = :planCapable\n")
      + (envId == null ? "" : "  and t.env_id = :envId\n")
      + """
      group by m.id, a.name, m.name, m.key, m.loc, m.plan_capable
      order by %s desc
      limit :limit
      """).formatted(table, orderByExpression(sortKey));

    final long minutes = window.minutes();
    final SqlQuery sqlQuery = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("limit", limit);
    if (app != null) {
      sqlQuery.setParameter("appId", app.getId());
    }
    if (planCapable != null) {
      sqlQuery.setParameter("planCapable", planCapable);
    }
    if (envId != null) {
      sqlQuery.setParameter("envId", envId);
    }
    return sqlQuery.mapTo((rs, _) -> {
        final long count = rs.getLong("agg_count");
        final long total = rs.getLong("agg_total");
        final long max = rs.getLong("agg_max");
        final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
        return AppMetricStats.builder()
          .id(rs.getLong("metric_id"))
          .app(rs.getString("app_name"))
          .label(rs.getString("label"))
          .key(rs.getString("key"))
          .loc(rs.getString("loc"))
          .planCapable(rs.getBoolean("plan_capable"))
          .count(count)
          .totalMicros(total)
          .meanMicros(mean)
          .maxMicros(max)
          .windowMinutes(minutes)
          .build();
      })
      .findList();
  }

  public List<MissingPlanMetric> listMissingPlans(String appName, @Nullable String orderBy,
                                                  @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                  @Nullable Long olderThanMinutes,
                                                  @Nullable Long olderThanHours,
                                                  @Nullable Integer limit) {
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runMissingPlansQuery(app, orderBy, sinceMinutes, sinceHours,
      olderThanMinutes, olderThanHours, clampLimit(limit));
  }

  public List<MissingPlanMetric> topMissingPlans(@Nullable String orderBy,
                                                 @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                 @Nullable Long olderThanMinutes,
                                                 @Nullable Long olderThanHours,
                                                 @Nullable Integer limit) {
    return runMissingPlansQuery(null, orderBy, sinceMinutes, sinceHours,
      olderThanMinutes, olderThanHours, clampLimit(limit));
  }

  private List<MissingPlanMetric> runMissingPlansQuery(@Nullable DApp app, @Nullable String orderBy,
                                                       @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                                       @Nullable Long olderThanMinutes,
                                                       @Nullable Long olderThanHours, int limit) {
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
        m.name            as label,
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
      left join %s t on t.metric_id = m.id and t.event_time > :from
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
  // Plans
  // ---------------------------------------------------------------------------

  public List<QueryPlanSummary> listAppPlans(String appName, @Nullable String env,
                                             @Nullable String label, @Nullable String hash,
                                             @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                             @Nullable Integer limit) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, 0L);
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runPlanSummaryQuery(app, env, label, hash, window, clampLimit(limit));
  }

  public List<QueryPlanSummary> listPlansByHash(String appName, String hash,
                                                @Nullable String env, @Nullable Integer limit) {
    if (hash == null || hash.isBlank()) {
      return List.of();
    }
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runPlanSummaryQuery(app, env, null, hash, TimeWindow.NONE, clampLimit(limit));
  }

  public List<QueryPlanSummary> listPlansByLabel(String appName, String label,
                                                 @Nullable String env, @Nullable Integer limit) {
    if (label == null || label.isBlank()) {
      return List.of();
    }
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runPlanSummaryQuery(app, env, label, null, TimeWindow.NONE, clampLimit(limit));
  }

  public List<QueryPlanSummary> listPlans(@Nullable String app, @Nullable String env,
                                          @Nullable String label, @Nullable String hash,
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
    return runPlanSummaryQuery(resolved, env, label, hash, window, clampLimit(limit));
  }

  public QueryPlan getPlan(long planId) {
    final DQueryPlan plan = new QDQueryPlan().id.eq((int) planId).findOne();
    if (plan == null) {
      throw new NotFoundException("No query plan with id " + planId);
    }
    return toQueryPlan(plan);
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

  public List<PendingPlan> listPendingPlans(@Nullable String app, @Nullable String env) {
    final Instant from = Instant.now().minus(Duration.ofMinutes(PENDING_STALE_MINUTES));
    final QDCaptureRequest q = new QDCaptureRequest()
      .collectedAt.isNull()
      .requestedAt.gt(from)
      .app.name.eqIfNotBlank(app);
    if (env != null && !env.isBlank()) {
      // include "any environment" requests (env is null) when filtering by env,
      // since such a request may yet be collected in the requested environment
      q.env.name.eqOrNull(env.trim());
    }
    return q
      .orderBy().requestedAt.asc()
      .findStream()
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
  // Envs
  // ---------------------------------------------------------------------------

  public List<Env> listEnvs() {
    return new QDEnv()
      .orderBy().name.asc()
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
                                                     TimeWindow window, int limit) {
    final QDQueryPlan q = new QDQueryPlan();
    if (app != null) {
      q.app.eq(app);
    }
    q.env.name.eqIfNotBlank(env);
    q.label.eqIfNotBlank(label);
    q.hash.eqIfNotBlank(hash);
    if (window.hasFrom()) {
      q.whenCreated.gt(window.from());
    }
    return q
      .orderBy().whenCreated.desc()
      .setMaxRows(limit)
      .findList()
      .stream()
      .map(V1QueryService::toQueryPlanSummary)
      .toList();
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

  static AppMetric toAppMetric(DAppMetric m) {
    return AppMetric.builder()
      .id((long) m.getId())
      .name(m.getName())
      .key(m.getKey())
      .loc(m.getLoc())
      .sql(m.getSql())
      .build();
  }

  static QueryPlanSummary toQueryPlanSummary(DQueryPlan p) {
    return QueryPlanSummary.builder()
      .id((long) p.getId())
      .appMetricId(p.metric() == null ? 0L : (long) p.metric().getId())
      .envName(p.env().getName())
      .hash(p.hash())
      .label(p.label())
      .queryTimeMicros(p.queryTimeMicros())
      .captureCount(p.captureCount())
      .whenCaptured(p.whenCaptured())
      .build();
  }

  static QueryPlan toQueryPlan(DQueryPlan p) {
    return QueryPlan.builder()
      .id((long) p.getId())
      .hash(p.hash())
      .label(p.label())
      .appMetricId(p.metric() == null ? 0L : (long) p.metric().getId())
      .envName(p.env().getName())
      .queryTimeMicros(p.queryTimeMicros())
      .captureCount(p.captureCount())
      .captureMicros(p.captureMicros())
      .whenCaptured(p.whenCaptured())
      .sql(p.sql())
      .bind(p.bind())
      .plan(p.plan())
      .build();
  }
}
