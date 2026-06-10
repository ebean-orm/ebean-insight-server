package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;

@Controller
public final class V1MetricsController implements MetricsApi {

  private final V1QueryService service;

  public V1MetricsController(V1QueryService service) {
    this.service = service;
  }

  @Override
  public List<AppMetric> listAppMetrics(String app, String label, Boolean planCapable, Integer limit) {
    return service.listAppMetrics(app, label, planCapable, limit);
  }

  @Override
  public List<AppMetric> listMetricsByLabel(String app, String label) {
    return service.listMetricsByLabel(app, label);
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
  public List<AppMetricStats> topAppMetrics(String app, String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
    return service.topAppMetrics(app, orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
  }

  @Override
  public List<MissingPlanMetric> listMissingPlans(String app, String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
    return service.listMissingPlans(app, orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
  }

  @Override
  public List<AppMetricStats> topMetrics(String orderBy, Long sinceMinutes, Long sinceHours, Integer limit, Boolean planCapable, String env) {
    return service.topMetrics(orderBy, sinceMinutes, sinceHours, limit, planCapable, env);
  }

  @Override
  public List<MissingPlanMetric> topMissingPlans(String orderBy, Long sinceMinutes, Long sinceHours, Long olderThanMinutes, Long olderThanHours, Integer limit) {
    return service.topMissingPlans(orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
  }
}
