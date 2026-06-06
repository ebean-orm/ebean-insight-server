package org.ebean.monitor.v1.web;

import io.avaje.inject.Component;
import io.avaje.jex.http.BadRequestException;
import io.avaje.jex.http.NotFoundException;
import io.ebean.DB;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.query.QDApp;
import org.ebean.monitor.domain.query.QDAppMetric;
import org.ebean.monitor.domain.query.QDEnv;
import org.ebean.monitor.domain.query.QDQueryPlan;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.web.MessageService;
import org.jspecify.annotations.Nullable;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
  private static final long DEFAULT_TOP_WINDOW_MINUTES = 60L;
  private static final long DEFAULT_ACTIVE_WINDOW_MINUTES = 60L;
  private static final String NO_ENVIRONMENT = "no-environment";

  private static final Set<String> ORDER_BY_KEYS = Set.of("total", "mean", "max", "count");

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
      .findList()
      .stream()
      .map(row -> new App(row.getLong("id"), row.getString("name")))
      .toList();
  }

  public AppSummary getApp(String appName) {
    final DApp app = requireApp(appName);
    final String sql = """
      select
        (select count(*) from ebean_insight.app_metric m where m.app_id = :appId) as metric_count,
        (select count(*) from ebean_insight.query_plan p where p.app_id = :appId) as plan_count,
        (select max(t.event_time) from ebean_insight.timed_m1 t where t.app_id = :appId) as last_report_at
      """;
    final SqlRow row = DB.sqlQuery(sql)
      .setParameter("appId", app.getId())
      .findOne();
    final long metricCount = row == null ? 0L : row.getLong("metric_count");
    final long planCount = row == null ? 0L : row.getLong("plan_count");
    final Instant lastReportAt = row == null ? null : toInstant(row.getTimestamp("last_report_at"));
    return new AppSummary((long) app.getId(), app.getName(), lastReportAt, metricCount, planCount);
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
    if (label != null && !label.isBlank()) {
      q.name.eq(label.trim());
    }
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
                                                   @Nullable Long sinceHours) {
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
    final SqlRow row = DB.sqlQuery("""
        select
          coalesce(sum(t.count), 0) as count,
          coalesce(sum(t.total), 0) as total,
          coalesce(max(t.max), 0)   as max
        from ebean_insight.timed_m1 t
        where t.metric_id = :metricId
          and t.event_time > :from
        """)
      .setParameter("metricId", metric.getId())
      .setParameter("from", window.from())
      .findOne();
    final long count = row == null ? 0L : row.getLong("count");
    final long total = row == null ? 0L : row.getLong("total");
    final long max = row == null ? 0L : row.getLong("max");
    final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
    return List.of(new AppMetricStats(
      (long) metric.getId(),
      app.getName(),
      metric.getName(),
      metric.getKey(),
      metric.getLoc(),
      metric.isPlanCapable(),
      count, total, mean, max,
      window.minutes()));
  }

  public List<AppMetricStats> topAppMetrics(String appName, @Nullable String orderBy,
                                            @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                            @Nullable Integer limit, @Nullable Boolean planCapable) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    return runTopQuery(app, orderBy, window, planCapable, clampLimit(limit));
  }

  public List<AppMetricStats> topMetrics(@Nullable String orderBy,
                                         @Nullable Long sinceMinutes, @Nullable Long sinceHours,
                                         @Nullable Integer limit, @Nullable Boolean planCapable) {
    final TimeWindow window = TimeWindow.of(sinceMinutes, sinceHours, DEFAULT_TOP_WINDOW_MINUTES);
    return runTopQuery(null, orderBy, window, planCapable, clampLimit(limit));
  }

  private List<AppMetricStats> runTopQuery(@Nullable DApp app, @Nullable String orderBy,
                                           TimeWindow window, @Nullable Boolean planCapable, int limit) {
    final String sortKey = resolveOrderBy(orderBy);
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
      from ebean_insight.timed_m1 t
      join ebean_insight.app_metric m on m.id = t.metric_id
      join ebean_insight.app a        on a.id = m.app_id
      where t.event_time > :from
      """
      + (app == null ? "" : "  and a.id = :appId\n")
      + (planCapable == null ? "" : "  and m.plan_capable = :planCapable\n")
      + """
      group by m.id, a.name, m.name, m.key, m.loc, m.plan_capable
      order by %s desc
      limit :limit
      """).formatted(orderByExpression(sortKey));

    final SqlQuery q = DB.sqlQuery(sql)
      .setParameter("from", window.from())
      .setParameter("limit", limit);
    if (app != null) {
      q.setParameter("appId", app.getId());
    }
    if (planCapable != null) {
      q.setParameter("planCapable", planCapable);
    }
    return q.findList().stream()
      .map(row -> {
        final long count = row.getLong("agg_count");
        final long total = row.getLong("agg_total");
        final long max = row.getLong("agg_max");
        final long mean = count == 0L ? 0L : Math.floorDiv(total, count);
        return new AppMetricStats(
          row.getLong("metric_id"),
          row.getString("app_name"),
          row.getString("label"),
          row.getString("key"),
          row.getString("loc"),
          row.getBoolean("plan_capable"),
          count, total, mean, max,
          window.minutes());
      })
      .toList();
  }

  public List<MissingPlanMetric> listMissingPlans(String appName,
                                                  @Nullable Long olderThanMinutes,
                                                  @Nullable Long olderThanHours,
                                                  @Nullable Integer limit) {
    final TimeWindow window = TimeWindow.of(olderThanMinutes, olderThanHours, 0L);
    final DApp app = findApp(appName);
    if (app == null) {
      return List.of();
    }
    final int max = clampLimit(limit);
    final boolean hasWindow = window.hasFrom();
    final String sql = """
      select
        m.id              as metric_id,
        m.name            as label,
        m.key             as key,
        m.loc             as loc,
        m.sql             as sql,
        agg.last_captured as last_captured,
        coalesce(agg.capture_count, 0) as capture_count
      from ebean_insight.app_metric m
      left join (
        select metric_id,
               max(when_captured) as last_captured,
               count(*)           as capture_count
        from ebean_insight.query_plan
        group by metric_id
      ) agg on agg.metric_id = m.id
      where m.app_id = :appId
        and m.plan_capable = true
        and (
          agg.last_captured is null
          %s
        )
      order by coalesce(agg.last_captured, timestamp '1970-01-01') asc, m.name asc
      limit :limit
      """.formatted(hasWindow ? "or agg.last_captured < :threshold" : "");
    var query = DB.sqlQuery(sql)
      .setParameter("appId", app.getId())
      .setParameter("limit", max);
    if (hasWindow) {
      query.setParameter("threshold", window.from());
    }
    return query
      .findList()
      .stream()
      .map(row -> new MissingPlanMetric(
        row.getLong("metric_id"),
        app.getName(),
        row.getString("label"),
        row.getString("key"),
        row.getString("loc"),
        toInstant(row.getTimestamp("last_captured")),
        row.getLong("capture_count"),
        row.getString("sql")))
      .toList();
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
        "Metric '" + metric.getName() + "' is not plan-capable; only ORM (orm.*) metrics support plan capture");
    }
    final String envName = (env == null || env.isBlank()) ? NO_ENVIRONMENT : env.trim();
    final String message = "qp:" + metric.getKey();
    final int pending = messageService.pushMessage(app.getName(), envName, message);
    return new PendingResponse(pending);
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
    if (env != null && !env.isBlank()) {
      q.env.name.eq(env.trim());
    }
    if (label != null && !label.isBlank()) {
      q.label.eq(label.trim());
    }
    if (hash != null && !hash.isBlank()) {
      q.hash.eq(hash.trim());
    }
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
    return new AppMetric((long) m.getId(), m.getName(), m.getKey(), m.getLoc(), m.getSql());
  }

  static QueryPlanSummary toQueryPlanSummary(DQueryPlan p) {
    return new QueryPlanSummary(
      (long) p.getId(),
      p.metric() == null ? 0L : (long) p.metric().getId(),
      p.env().getName(),
      p.hash(),
      p.label(),
      p.queryTimeMicros(),
      p.captureCount(),
      p.whenCaptured());
  }

  static QueryPlan toQueryPlan(DQueryPlan p) {
    return new QueryPlan(
      (long) p.getId(),
      p.hash(),
      p.label(),
      p.metric() == null ? 0L : (long) p.metric().getId(),
      p.env().getName(),
      p.queryTimeMicros(),
      p.captureCount(),
      p.captureMicros(),
      p.whenCaptured(),
      p.sql(),
      p.bind(),
      p.plan());
  }
}
