package org.ebean.monitor.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.http.api.QueryParam;
import io.avaje.jex.http.NotFoundException;
import org.ebean.monitor.api.App;
import org.ebean.monitor.api.AppMetric;
import org.ebean.monitor.api.DataPoint;
import org.ebean.monitor.api.Env;
import org.ebean.monitor.api.ListResponse;
import org.ebean.monitor.api.PendingResponse;
import org.ebean.monitor.api.QueryPlan;
import org.ebean.monitor.api.QueryPlanSummary;
import org.ebean.monitor.cleanup.CleanupPartitions;
import org.ebean.monitor.domain.DApp;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DEnv;
import org.ebean.monitor.domain.DGaugeRollupM1;
import org.ebean.monitor.domain.DQueryPlan;
import org.ebean.monitor.domain.DTimedRollupM1;
import org.ebean.monitor.domain.query.QDGaugeRollupM1;
import org.ebean.monitor.domain.query.QDTimedRollupM1;

import java.util.ArrayList;
import java.util.List;

@Controller
@Path("/api")
class ApiController {

  private final UIService service;
  private final MessageService messageService;

  ApiController(UIService service, MessageService messageService) {
    this.service = service;
    this.messageService = messageService;
  }

  @Post("cleanup")
  String cleanup() {
    final long droppedPartitions = new CleanupPartitions().run();
    return "partitions " + droppedPartitions;
  }

  /**
   * Return the environments for the given org.
   */
  @Get("env")
  ListResponse<Env> getEnvs() {
    final List<Env> envs = DEnv.find.findAll();
    return new ListResponse<>(envs);
  }

  /**
   * Return the applications for the given org.
   */
  @Get("app")
  ListResponse<App> getApps() {
    final List<App> apps = DApp.find.findAll();
    return new ListResponse<>(apps);
  }

  /**
   * Return the application metrics.
   */
  @Get("app/{id}/metric")
  ListResponse<AppMetric> getAppMetrics(long id) {
    final DApp app = DApp.find.ref(id);
    return new ListResponse<>(DAppMetric.find.byApp(app));
  }

  /**
   * Request capture of the query plan for an app metric.
   * <p>
   * Pushes a {@code qp:<hash>} message that the originating app picks up
   * on its next metric POST and uses to enable bind capture for the
   * matching query plan.
   *
   * @param appMetricId the app-metric id (from {@code /api/app/{id}/metric})
   * @param environment optional environment name; defaults to {@code no-environment}
   */
  @Post("queryplan/{appMetricId}/request")
  PendingResponse requestQueryPlan(int appMetricId, @QueryParam String environment) {
    DAppMetric appMetric = service.findAppMetric(appMetricId);
    String envName = (environment == null || environment.isBlank()) ? "no-environment" : environment.trim();
    DEnv env = service.findEnv(envName);
    DApp app = appMetric.getApp();
    if (app == null) {
      throw new NotFoundException("AppMetric " + appMetricId + " has no associated app");
    }
    String msg = "qp:" + appMetric.getKey();
    int pending = messageService.pushMessage(app.getName(), env.getName(), msg);
    PendingResponse response = new PendingResponse();
    response.pending = pending;
    return response;
  }

  /**
   * Return the most recently captured query plans, optionally scoped by app,
   * environment, label, hash and/or recency.
   *
   * @param count        maximum number of plans to return (default 10, max 100)
   * @param app          optional app name filter
   * @param environment  optional environment name filter
   * @param label        optional metric label filter (exact match)
   * @param hash         optional query plan hash filter (exact match)
   * @param sinceMinutes optional: only plans captured within the last N minutes
   */
  @Get("queryplan/recent")
  ListResponse<QueryPlanSummary> getRecentQueryPlans(@QueryParam Integer count, @QueryParam String app,
                                                     @QueryParam String environment, @QueryParam String label,
                                                     @QueryParam String hash, @QueryParam Integer sinceMinutes) {
    int max = (count == null) ? 10 : Math.max(1, Math.min(100, count));
    List<QueryPlanSummary> result = service.findRecentQueryPlans(max, app, environment, label, hash, sinceMinutes).stream()
      .map(ApiController::toSummary)
      .toList();
    return new ListResponse<>(result);
  }

  /**
   * Return a single captured query plan by its plan id, including SQL, bind
   * values and the raw plan text.
   *
   * @param planId the query plan id (e.g. from {@code /api/queryplan/recent})
   */
  @Get("queryplan/plan/{planId}")
  QueryPlan getQueryPlan(int planId) {
    DQueryPlan plan = service.findQueryPlan(planId);
    if (plan == null) {
      throw new NotFoundException("No query plan with id " + planId);
    }
    return toDto(plan);
  }

  /**
   * Return captured query plans for an app metric, most recent first.
   *
   * @param appMetricId the app-metric id
   * @param count       maximum number of plans to return (default 50, max 200)
   */
  @Get("queryplan/{appMetricId}")
  ListResponse<QueryPlan> getQueryPlans(int appMetricId, @QueryParam Integer count) {
    int max = (count == null) ? 50 : Math.max(1, Math.min(200, count));
    List<QueryPlan> result = service.findQueryPlans(appMetricId, max).stream()
      .map(ApiController::toDto)
      .toList();
    return new ListResponse<>(result);
  }

  /**
   * Return Aggregate metric for an org, env, app.
   */
  @Get("env/{envName}/app/{appId}/global/{metricName}")
  ListResponse<DataPoint> getAggregateDbTime(String envName, long appId, String metricName, Integer maxRows) {
    final DEnv env = DEnv.find.byName(envName);
    if (env == null) {
      throw new NotFoundException("No such environment");
    }
    final DAppMetric metric = DAppMetric.find.globalByName(metricName);
    if (metric == null) {
      throw new NotFoundException("No such metric");
    }
    final DApp app = DApp.find.ref(appId);
    int max = (maxRows == null) ? 60 * 3 : maxRows;

    if (metricName.startsWith("jvm")) {
      final QDGaugeRollupM1 g = QDGaugeRollupM1.alias();
      final List<DGaugeRollupM1> list = new QDGaugeRollupM1()
        .select(g.eventTime, g.total)
        .app.eq(app)
        .env.eq(env)
        .metric.eq(metric)
        .eventTime.desc()
        .setMaxRows(max)
        .findList();

      List<DataPoint> data = new ArrayList<>(list.size());
      for (DGaugeRollupM1 timed : list) {
        data.add(new DataPoint(timed.getEventTime(), timed.getTotal().longValue()));
      }
      return new ListResponse<>(data);
    }

    final QDTimedRollupM1 t = QDTimedRollupM1.alias();
    final List<DTimedRollupM1> list = new QDTimedRollupM1()
      .select(t.eventTime, t.total)
      .app.eq(app)
      .env.eq(env)
      .metric.eq(metric)
      .eventTime.desc()
      .setMaxRows(max)
      .findList();

    List<DataPoint> data = new ArrayList<>(list.size());
    for (DTimedRollupM1 timed : list) {
      data.add(new DataPoint(timed.getEventTime(), timed.getTotal()));
    }
    return new ListResponse<>(data);
  }

  private static QueryPlan toDto(DQueryPlan p) {
    QueryPlan dto = new QueryPlan();
    dto.id = p.getId();
    dto.hash = p.hash();
    dto.label = p.label();
    dto.appMetricId = p.metric() == null ? 0L : p.metric().getId();
    dto.envName = p.env().getName();
    dto.queryTimeMicros = p.queryTimeMicros();
    dto.captureCount = p.captureCount();
    dto.captureMicros = p.captureMicros();
    dto.whenCaptured = p.whenCaptured();
    dto.sql = p.sql();
    dto.bind = p.bind();
    dto.plan = p.plan();
    return dto;
  }

  private static QueryPlanSummary toSummary(DQueryPlan p) {
    QueryPlanSummary dto = new QueryPlanSummary();
    dto.id = p.getId();
    dto.appMetricId = p.metric() == null ? 0L : p.metric().getId();
    dto.envName = p.env().getName();
    dto.hash = p.hash();
    dto.label = p.label();
    dto.queryTimeMicros = p.queryTimeMicros();
    dto.captureCount = p.captureCount();
    dto.whenCaptured = p.whenCaptured();
    return dto;
  }
}
