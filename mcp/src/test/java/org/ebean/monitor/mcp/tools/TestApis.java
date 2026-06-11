package org.ebean.monitor.mcp.tools;

import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.AppSummary;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingPlan;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written stub implementations of the {@code /v1} API interfaces for unit
 * tests — they record the arguments of each call and return canned data, so
 * tools can be exercised without a running ebean-insight server.
 */
public final class TestApis {

  /** Records {@code method name -> last call arguments}. */
  public final Map<String, Object[]> calls = new HashMap<>();

  public final Apps apps = new Apps();
  public final Envs envs = new Envs();
  public final Metrics metrics = new Metrics();
  public final Plans plans = new Plans();

  private void record(String method, Object... args) {
    calls.put(method, args);
  }

  public Object[] args(String method) {
    return calls.get(method);
  }

  public boolean called(String method) {
    return calls.containsKey(method);
  }

  static QueryPlan samplePlan(long id) {
    return new QueryPlan(id, "hash" + id, "orm.X.find", 1L, "test",
        100L, 1L, 100L, Instant.parse("2026-06-01T00:00:00Z"),
        "select 1", "[]", "Seq Scan", "shape", "h", 1);
  }

  public final class Apps implements AppsApi {
    @Override
    public List<App> listApps(Long activeWithinMinutes, Long activeWithinHours) {
      record("listApps", activeWithinMinutes, activeWithinHours);
      return List.of(new App(1L, "central-access"));
    }

    @Override
    public AppSummary getApp(String app) {
      record("getApp", app);
      return null;
    }
  }

  public final class Envs implements EnvsApi {
    @Override
    public List<Env> listEnvs() {
      record("listEnvs");
      return List.of(new Env("test"), new Env("dev"));
    }
  }

  public final class Metrics implements MetricsApi {
    public List<AppMetric> appMetrics = List.of();
    public List<AppMetricStats> stats = List.of();
    public List<MissingPlanMetric> missing = List.of();

    @Override
    public List<AppMetric> listAppMetrics(String app, String label, Boolean planCapable, Integer limit) {
      record("listAppMetrics", app, label, planCapable, limit);
      return appMetrics;
    }

    @Override
    public List<AppMetric> listMetricsByLabel(String app, String label) {
      record("listMetricsByLabel", app, label);
      return List.of();
    }

    @Override
    public List<AppMetric> getMetricByHash(String app, String hash) {
      record("getMetricByHash", app, hash);
      return List.of();
    }

    @Override
    public List<AppMetricStats> getMetricStatsByHash(String app, String hash, Long sinceMinutes, Long sinceHours, String env) {
      record("getMetricStatsByHash", app, hash, sinceMinutes, sinceHours, env);
      return List.of();
    }

    @Override
    public MetricTimeseries getMetricTimeseries(String app, String hash, Long sinceMinutes, Long sinceHours, String env) {
      record("getMetricTimeseries", app, hash, sinceMinutes, sinceHours, env);
      return null;
    }

    @Override
    public List<AppMetricStats> topAppMetrics(String app, String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
      record("topAppMetrics", app, orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
      return stats;
    }

    @Override
    public List<MissingPlanMetric> listMissingPlans(String app, String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
      record("listMissingPlans", app, orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
      return missing;
    }

    @Override
    public List<AppMetricStats> topMetrics(String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
      record("topMetrics", orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
      return stats;
    }

    @Override
    public List<MissingPlanMetric> topMissingPlans(String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
      record("topMissingPlans", orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
      return missing;
    }
  }

  public final class Plans implements PlansApi {
    public List<QueryPlanSummary> planSummaries = List.of();
    public QueryPlan plan = samplePlan(15L);

    @Override
    public List<QueryPlanSummary> listAppPlans(String app, String env, String label, String hash, Long sinceMinutes, Long sinceHours, Integer limit) {
      record("listAppPlans", app, env, label, hash, sinceMinutes, sinceHours, limit);
      return planSummaries;
    }

    @Override
    public List<QueryPlanSummary> listPlansByHash(String app, String hash, String env, Integer limit) {
      record("listPlansByHash", app, hash, env, limit);
      return planSummaries;
    }

    @Override
    public List<QueryPlanSummary> listPlansByLabel(String app, String label, String env, Integer limit) {
      record("listPlansByLabel", app, label, env, limit);
      return planSummaries;
    }

    @Override
    public PendingResponse requestPlanCapture(String app, String hash, String env) {
      record("requestPlanCapture", app, hash, env);
      return null;
    }

    @Override
    public QueryPlan getPlan(Long planId) {
      record("getPlan", planId);
      return plan;
    }

    @Override
    public List<QueryPlanSummary> listPlans(String app, String env, String label, String hash, Long sinceMinutes, Long sinceHours, Integer limit) {
      record("listPlans", app, env, label, hash, sinceMinutes, sinceHours, limit);
      return planSummaries;
    }

    @Override
    public List<PendingPlan> listPendingPlans(String app, String env) {
      record("listPendingPlans", app, env);
      return List.of();
    }
  }
}
