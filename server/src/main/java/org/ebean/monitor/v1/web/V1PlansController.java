package org.ebean.monitor.v1.web;

import io.avaje.http.api.Controller;
import java.util.List;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.PendingPlan;
import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

@Controller
public final class V1PlansController implements PlansApi {

  private final V1QueryService service;

  public V1PlansController(V1QueryService service) {
    this.service = service;
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
  public List<QueryPlanSummary> listPlans(String app, String env, String label, String hash, String kind, String type, Long sinceMinutes, Long sinceHours, Integer limit) {
    return service.listPlans(app, env, label, hash, kind, type, sinceMinutes, sinceHours, limit);
  }

  @Override
  public List<PendingPlan> listPendingPlans(String app, String env, String hash, String label) {
    return service.listPendingPlans(app, env, hash, label);
  }

  @Override
  public List<PlanChange> listPlanChanges(String app, String env, String hash, String changeType, String label, String kind, String type, Long sinceMinutes, Long sinceHours, Integer limit) {
    return service.listPlanChanges(app, env, hash, changeType, label, kind, type, sinceMinutes, sinceHours, limit);
  }

  @Override
  public PlanChangeDetail getPlanChange(Long id) {
    return service.getPlanChange(id);
  }
}
