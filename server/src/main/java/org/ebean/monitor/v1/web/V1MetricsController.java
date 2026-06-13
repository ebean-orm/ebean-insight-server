package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.TopGroup;

@Controller
public final class V1MetricsController implements MetricsApi {

  private final V1QueryService service;

  public V1MetricsController(V1QueryService service) {
    this.service = service;
  }

  @Override
  public List<AppMetric> listAppMetrics(String app, String name, String label, String kind, String type, Boolean planCapable, Integer limit) {
    return service.listAppMetrics(app, name, label, kind, type, planCapable, limit);
  }

  @Override
  public List<AppMetric> getMetricByHash(String app, String hash) {
    return service.getMetricByHash(app, hash);
  }

  @Override
  public List<AppMetricStats> getMetricStatsByHash(String app, String hash, Long sinceMinutes, Long sinceHours, String env) {
    return service.getMetricStatsByHash(app, hash, sinceMinutes, sinceHours, env);
  }

  @Override
  public MetricTimeseries getMetricTimeseries(String app, String hash, Long sinceMinutes, Long sinceHours, String env) {
    return service.getMetricTimeseries(app, hash, sinceMinutes, sinceHours, env);
  }

  @Override
  public List<TopGroup> topAppMetrics(String app, String by, String name, String label, String kind, String type, String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
    return service.topAppMetrics(app, by, name, label, kind, type, orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
  }

  @Override
  public List<MissingPlanMetric> listMissingPlans(String app, String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
    return service.listMissingPlans(app, orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
  }

  @Override
  public List<TopGroup> topMetrics(String by, String name, String label, String kind, String type, String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
    return service.topMetrics(by, name, label, kind, type, orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
  }

  @Override
  public List<MissingPlanMetric> topMissingPlans(String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
    return service.topMissingPlans(orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
  }
}
