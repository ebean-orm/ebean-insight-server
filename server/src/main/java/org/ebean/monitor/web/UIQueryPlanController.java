package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.QueryPlanView;
import org.ebean.monitor.web.view.QueryPlanView.SiblingRow;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Single captured query-plan drill-down page, reached by clicking a row in
 * the "recently captured query plans" table on {@link UIMetricDetailController}.
 * <p>
 * Shows the plan's SQL/bind values and renders the captured EXPLAIN text via
 * the PEV2 visualizer (in an isolated iframe so its required Bootstrap CSS
 * doesn't leak into the rest of the Pico.css-based dashboard), plus a list of
 * sibling captures for the same (env, hash) so an older/regressed capture is
 * one click away.
 */
@Html
@Controller
@Path("/ux")
public class UIQueryPlanController {

  private static final int SIBLINGS_LIMIT = 20;

  private static final DateTimeFormatter CAPTURED_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

  private final V1QueryService service;
  private final Jsonb jsonb;

  public UIQueryPlanController(V1QueryService service, Jsonb jsonb) {
    this.service = service;
    this.jsonb = jsonb;
  }

  @Get("query-plan")
  QueryPlanView queryPlan(@QueryParam("id") long id) {
    final QueryPlan plan = service.getPlan(id);
    final String appName = service.getPlanAppName(id);

    final Breadcrumb breadcrumb = new Breadcrumb(List.of(
      new Breadcrumb.Item("/ux/metric-detail?app=" + appName + "&label=" + plan.label(), plan.label()),
      new Breadcrumb.Item("Query plan")));

    final List<QueryPlanSummary> siblingSummaries = service.listPlans(
      appName, plan.envName(), null, plan.hash(), null, null, null, null, SIBLINGS_LIMIT);
    final List<SiblingRow> siblings = siblingSummaries.stream()
      .map(s -> new SiblingRow(s.id(), CAPTURED_FORMAT.format(s.whenCaptured()),
        formatNum(s.queryTimeMicros() / 1000L), s.id() == id, Boolean.TRUE.equals(s.shapeChanged())))
      .toList();

    return new QueryPlanView(breadcrumb, plan.id(), appName, plan.envName(), plan.label(), plan.hash(),
      CAPTURED_FORMAT.format(plan.whenCaptured()), formatNum(plan.queryTimeMicros() / 1000L),
      formatNum(plan.captureCount()), isChanged(siblings, id), plan.sql(), plan.bind(),
      plan.bind() != null && !plan.bind().isBlank(), toPlanDataJson(plan), siblings);
  }

  private static boolean isChanged(List<SiblingRow> siblings, long id) {
    return siblings.stream().filter(s -> s.id() == id).findFirst().map(SiblingRow::shapeChanged).orElse(false);
  }

  private String toPlanDataJson(QueryPlan plan) {
    // Neutralise "</script>" (and any other embedded tag) since the plan/sql
    // text is captured verbatim from the target database - this JSON is
    // inlined into a <script type="application/json"> block.
    return jsonb.type(PlanData.class).toJson(new PlanData(plan.plan(), plan.sql()))
      .replace("<", "\\u003c");
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }
}
