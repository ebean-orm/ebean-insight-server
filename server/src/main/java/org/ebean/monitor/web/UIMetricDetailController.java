package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.model.TopGroup;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.v1.web.V1QueryService.LabelTimeseries;
import org.ebean.monitor.web.RangeOptions.RangeOption;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.MetricDetailView;
import org.ebean.monitor.web.view.MetricDetailView.FamilyRow;
import org.ebean.monitor.web.view.MetricDetailView.HashRow;
import org.ebean.monitor.web.view.MetricDetailView.PlanRow;
import org.ebean.monitor.web.view.Option;
import org.jspecify.annotations.Nullable;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-label drill-down page reached by clicking a label (legend row or
 * stacked-bar segment) on {@link UIQueryTotalController}'s dashboard.
 * <p>
 * Shows "total" and "mean" execution-time trend charts for the label (summed
 * across every underlying query hash sharing it), a ranked breakdown of those
 * hashes for the selected window, and any recently captured query plans.
 */
@Html
@Controller
@Path("/ux")
public class UIMetricDetailController {

  private static final String METRIC_NAME = "ebean.query";
  private static final int HASH_BREAKDOWN_LIMIT = 15;
  private static final int RECENT_PLANS_LIMIT = 15;
  private static final int FAMILY_LIMIT = 50;

  private static final DateTimeFormatter PLAN_CAPTURED_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

  private final V1QueryService service;
  private final Jsonb jsonb;

  public UIMetricDetailController(V1QueryService service, Jsonb jsonb) {
    this.service = service;
    this.jsonb = jsonb;
  }

  @Get("metric-detail")
  MetricDetailView metricDetail(@QueryParam("app") String appParam,
                                @QueryParam("env") @Nullable String envParam,
                                @QueryParam("range") @Nullable String rangeParam,
                                @QueryParam("label") String labelParam) {

    final List<Env> envs = service.listEnvs();
    final String selectedApp = appParam == null ? "" : appParam;
    final String selectedEnv = envParam == null ? "" : envParam;
    final RangeOption range = RangeOptions.resolve(rangeParam);
    final String label = labelParam == null ? "" : labelParam;

    final Breadcrumb breadcrumb = new Breadcrumb(List.of(
      new Breadcrumb.Item("/ux/top?app=" + selectedApp
        + "&env=" + selectedEnv + "&range=" + range.key(), "Top"),
      new Breadcrumb.Item(label)));

    if (selectedApp.isBlank() || label.isBlank()) {
      return emptyView(breadcrumb, envs, selectedApp, selectedEnv, range, label);
    }

    final String env = selectedEnv.isBlank() ? null : selectedEnv;
    final LabelTimeseries totalSeries = service.getLabelTimeseries(
      selectedApp, label, (long) range.minutes(), null, env);

    if (totalSeries.buckets().isEmpty()) {
      return emptyView(breadcrumb, envs, selectedApp, selectedEnv, range, label);
    }

    final List<TopGroup> hashGroups = service.topAppMetrics(selectedApp, "hash", METRIC_NAME, label,
      null, null, "total", (long) range.minutes(), null, HASH_BREAKDOWN_LIMIT, null, env);
    final Map<String, String> colorByHash = new HashMap<>();
    final List<HashRow> hashBreakdown = new ArrayList<>(hashGroups.size());
    int colorIndex = 0;
    for (TopGroup g : hashGroups) {
      final String color = Palette.colorFor(colorIndex++);
      colorByHash.put(g.key(), color);
      hashBreakdown.add(new HashRow(g.key(), color));
    }
    final MetricTimeseriesTop hashTimeseries = service.getLabelHashTimeseries(
      selectedApp, label, METRIC_NAME, (long) range.minutes(), null, HASH_BREAKDOWN_LIMIT, env);
    final String totalChartJson = toJson(BucketCharts.buildHashStacked(hashTimeseries, colorByHash));
    final String meanChartJson = toJson(BucketCharts.buildHashMean(hashTimeseries, colorByHash));
    final String maxChartJson = toJson(BucketCharts.buildHashMax(hashTimeseries, colorByHash));

    final List<QueryPlanSummary> plans = service.listPlans(selectedApp, env, label, null, null, null,
      (long) range.minutes(), null, RECENT_PLANS_LIMIT);
    final List<PlanRow> recentPlans = plans.stream()
      .map(p -> new PlanRow(p.id(), p.envName(), p.hash(), PLAN_CAPTURED_FORMAT.format(p.whenCaptured()),
        formatNum(p.queryTimeMicros() / 1000L), formatNum(p.captureCount()),
        Boolean.TRUE.equals(p.shapeChanged()), colorByHash.getOrDefault(p.hash(), Palette.OTHER_COLOR)))
      .toList();

    final List<FamilyRow> family = buildFamily(selectedApp, label, range, env);

    return new MetricDetailView(breadcrumb, true, selectedApp,
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      label, totalChartJson, meanChartJson, maxChartJson, hashBreakdown, recentPlans, !family.isEmpty(), family);
  }

  private MetricDetailView emptyView(Breadcrumb breadcrumb, List<Env> envs,
                                     String selectedApp, String selectedEnv, RangeOption range, String label) {
    return new MetricDetailView(breadcrumb, false, selectedApp,
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      label, emptyChartJson(), emptyChartJson(), emptyChartJson(), List.of(), List.of(), false, List.of());
  }

  /**
   * The label's fully-expanded "query family" tree - its dot-hierarchy root
   * (e.g. {@code Type.method}) plus every descendant fetch-path label
   * sharing that root - or an empty list when the label has no family
   * (no dots beyond the root, or no sibling/descendant data in this window).
   */
  private List<FamilyRow> buildFamily(String app, String label, RangeOption range, @Nullable String env) {
    final String root = LabelFamily.rootOf(label);
    final List<TopGroup> familyGroups = service.topLabelFamily(
      app, root, METRIC_NAME, (long) range.minutes(), env, FAMILY_LIMIT);
    if (familyGroups.size() <= 1) {
      return List.of();
    }
    return LabelFamily.buildTree(familyGroups, root, label).stream()
      .map(n -> new FamilyRow(n.label(), n.display(), n.depth() * 20,
        formatNum(n.totalMicros() / 1000L), formatNum(n.meanMicros() / 1000L), formatNum(n.count()),
        String.format(Locale.US, "%.0f", n.pct()), n.current(),
        UIQueryTotalController.metricDetailUrl(app, env, range.key(), n.label())))
      .toList();
  }

  private String toJson(ChartData chartData) {
    // Neutralise "</script>" (and any other embedded tag) since labels/hashes
    // are user-supplied — this JSON is inlined into a <script> block.
    return jsonb.type(ChartData.class).toJson(chartData).replace("<", "\\u003c");
  }

  private static String emptyChartJson() {
    return "{\"labels\":[],\"datasets\":[]}";
  }

  private static long microsToMs(@Nullable Long micros) {
    return micros == null ? 0L : micros / 1000L;
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  private static List<Option> envOptions(List<Env> envs, String selected) {
    final List<Option> options = new ArrayList<>();
    options.add(new Option("", "All environments", selected.isBlank()));
    for (Env env : envs) {
      options.add(new Option(env.name(), env.name(), env.name().equals(selected)));
    }
    return options;
  }
}
