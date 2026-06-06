package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

@Controller
public final class V1PlansController implements PlansApi {

  private final V1QueryService service;

  public V1PlansController(V1QueryService service) {
    this.service = service;
  }

  @Override
  public List<QueryPlanSummary> listAppPlans(String app, String env, String label, String hash, Long sinceMinutes, Long sinceHours, Integer limit) {
    return service.listAppPlans(app, env, label, hash, sinceMinutes, sinceHours, limit);
  }

  @Override
  public List<QueryPlanSummary> listPlansByHash(String app, String hash, String env, Integer limit) {
    return service.listPlansByHash(app, hash, env, limit);
  }

  @Override
  public List<QueryPlanSummary> listPlansByLabel(String app, String label, String env, Integer limit) {
    return service.listPlansByLabel(app, label, env, limit);
  }

  @Override
  public PendingResponse requestPlanCapture(String app, String hash, String env) {
    return service.requestPlanCapture(app, hash, env);
  }

  @Override
  public QueryPlan getPlan(Long planId) {
    return service.getPlan(planId);
  }

  @Override
  public List<QueryPlanSummary> listPlans(String app, String env, String label, String hash, Long sinceMinutes, Long sinceHours, Integer limit) {
    return service.listPlans(app, env, label, hash, sinceMinutes, sinceHours, limit);
  }
}
