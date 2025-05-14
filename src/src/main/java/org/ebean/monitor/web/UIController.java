package org.ebean.monitor.web;

import io.avaje.htmx.api.HxRequest;
import io.avaje.http.api.*;
import org.ebean.monitor.domain.*;
import org.ebean.monitor.web.view.Page;
import org.ebean.monitor.web.view.Partial;

import java.util.List;

@Controller
@Path("/")
final class UIController {

  private final UIService service;
  private final MessageService messageService;

  UIController(UIService service, MessageService messageService) {
    this.service = service;
    this.messageService = messageService;
  }

  @Get
  Page.Index index() {
    return new Page.Index();
  }

  @HxRequest
  @Get("env")
  Partial.Envs envs() {
    List<DEnv> envs = service.findAllEnvs();
    return new Partial.Envs(envs);
  }

  @HxRequest
  @Get("app")
  Partial.Apps apps() {
    List<DApp> metrics = service.findApps();
    return new Partial.Apps(metrics);
  }

  @HxRequest
  @Get("app/{appId}/metrics")
  Partial.AppMetrics metrics(int appId) {
    List<DAppMetric> metrics = service.findMetrics(appId);
    return new Partial.AppMetrics(metrics);
  }

  @Get("app/{id}")
  Page.App app(int id) {
    DApp app = service.findApp(id);
    return new Page.App(app);
  }

  @Get("metrics/{appMetricId}")
  Page.AppMetric appMetric(int appMetricId) {
    DAppMetric app = service.findAppMetric(appMetricId);
    return new Page.AppMetric(app);
  }

  @HxRequest
  @Get("metrics/{appMetricId}/recent")
  Partial.MetricsRecent metricsRecent(int appMetricId) {
    List<? extends BaseTimedEntry> metrics = service.findMetricsRecent(appMetricId);
    return new Partial.MetricsRecent(metrics);
  }

  @HxRequest
  @Form
  @Post("queryplan/{appMetricId}/request")
  Partial.MsgPending queryPlanRequest(int appMetricId, String environment) {
    DAppMetric appMetric = service.findAppMetric(appMetricId);
    DEnv env = service.findEnv(environment);
    DApp app = appMetric.getApp();

    String msg = "qp:" + appMetric.getKey();

    int pending = messageService.pushMessage(app.getName(), env.getName(), msg);
    return new Partial.MsgPending(pending);
  }

  @HxRequest
  @Get("queryplan/{appMetricId}")
  Partial.QueryPlans queryPlans(int appMetricId) {
    List<DQueryPlan> plans = service.findQueryPlans(appMetricId);
    return new Partial.QueryPlans(plans);
  }

  @Get("view-plan/{planId}")
  Page.ViewPlan viewPlan(int planId) {
    DQueryPlan queryPlan = service.findQueryPlan(planId);
    String rawSql = queryPlan.sql();
    String rawPlan = queryPlan.plan();
    if (rawPlan.startsWith("QUERY PLAN")) {
      rawPlan = rawPlan.substring(11);
    }
    return new Page.ViewPlan(rawSql, rawPlan);
  }

  @Get("dash")
  Page.Dash dash() {
    List<TimeEvents> events = service.dashOne();
    List<TimeEvents> events2 = service.dashOne2();
    return new Page.Dash(events, events2);
  }
}
