package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jex.http.BadRequestException;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.model.TopGroup;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.web.RangeOptions.RangeOption;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.MetricDetailView;
import org.ebean.monitor.web.view.MetricDetailView.FamilyRow;
import org.ebean.monitor.web.view.MetricDetailView.HashRow;
import org.ebean.monitor.web.view.MetricDetailView.PlanRow;
import org.ebean.monitor.web.view.Option;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * hashes for the selected window, and the most recently collected query plans.
 */
@Html
@Controller
@Path("/ux")
public class UIMetricDetailController {

  private static final String METRIC_NAME = "ebean.query";
  private static final int HASH_BREAKDOWN_LIMIT = 15;
  private static final int RECENT_PLANS_LIMIT = 10;
  private static final int FAMILY_LIMIT = 50;

  private static final DateTimeFormatter PLAN_CAPTURED_FORMAT =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

  private final V1QueryService service;
  private final Jsonb chartJsonb;

  public UIMetricDetailController(V1QueryService service) {
    this.service = service;
    this.chartJsonb = Jsonb.builder().serializeNulls(true).build();
  }

  @Get("metric-detail")
  MetricDetailView metricDetail(@QueryParam("app") String appParam,
                                @QueryParam("env") @Nullable String envParam,
                                @QueryParam("range") @Nullable String rangeParam,
                                @QueryParam("label") String labelParam,
                                @QueryParam("from") @Nullable String fromParam,
                                @QueryParam("to") @Nullable String toParam) {

    final List<Env> envs = service.listEnvs();
    final String selectedApp = appParam == null ? "" : appParam;
    final String selectedEnv = envParam == null ? "" : envParam;
    final Instant from = parseInstant(fromParam, "from");
    final Instant to = parseInstant(toParam, "to");
    if ((from == null) != (to == null)) {
      throw new BadRequestException("Both from and to timestamps are required");
    }
    final RangeOption range = from == null ? RangeOptions.resolve(rangeParam) : RangeOptions.custom();
    final String label = labelParam == null ? "" : labelParam;

    final Breadcrumb breadcrumb = new Breadcrumb(List.of(
      new Breadcrumb.Item(UIQueryTotalController.topUrl(
        selectedApp, selectedEnv, range.key(), from, to), "Top"),
      new Breadcrumb.Item(label)));

    if (selectedApp.isBlank() || label.isBlank()) {
      return emptyView(breadcrumb, envs, selectedApp, selectedEnv, range, label);
    }

    final String env = selectedEnv.isBlank() ? null : selectedEnv;
    final MetricTimeseriesTop hashTimeseries = from == null
      ? service.getLabelHashTimeseries(
        selectedApp, label, METRIC_NAME, (long) range.minutes(), null, HASH_BREAKDOWN_LIMIT, env)
      : service.getLabelHashTimeseries(
        selectedApp, label, METRIC_NAME, HASH_BREAKDOWN_LIMIT, env, from, to);
    if (hashTimeseries.series().isEmpty()) {
      return emptyView(breadcrumb, envs, selectedApp, selectedEnv, range, label);
    }

    final List<TopGroup> hashGroups = from == null
      ? service.topAppMetrics(selectedApp, "hash", METRIC_NAME, label,
        null, null, "total", (long) range.minutes(), null, HASH_BREAKDOWN_LIMIT, null, env)
      : service.topAppMetrics(selectedApp, "hash", METRIC_NAME, label,
        null, null, "total", HASH_BREAKDOWN_LIMIT, null, env, from, to);
    final Map<String, String> colorByHash = new HashMap<>();
    final List<HashRow> hashBreakdown = new ArrayList<>(hashGroups.size());
    int colorIndex = 0;
    for (TopGroup g : hashGroups) {
      final String color = Palette.colorFor(colorIndex++);
      colorByHash.put(g.key(), color);
      hashBreakdown.add(new HashRow(g.key(), color,
        g.loc(), formatNum(microsToMs(g.totalMicros())), formatNum(microsToMs(g.meanMicros())),
        g.sql(),
        hasSql(g.sql()) ? UIQueryTotalController.queryHashUrl(
          selectedApp, selectedEnv, range.key(), label, g.key(), from, to) : null));
    }
    final BucketCharts.HashCharts charts = BucketCharts.buildHashCharts(hashTimeseries, colorByHash);
    final String totalChartJson = toChartJson(charts.total());
    final String meanChartJson = toChartJson(charts.mean());
    final String maxChartJson = toChartJson(charts.max());

    // Plans are recent by collection time, independent of the selected metric range.
    final List<QueryPlanSummary> plans = service.listPlans(selectedApp, env, label, null, null, null,
      null, null, RECENT_PLANS_LIMIT);
    final List<PlanRow> recentPlans = plans.stream()
      .map(p -> new PlanRow(p.id(), UIQueryTotalController.queryPlanUrl(
          p.id(), range.key(), from, to), p.envName(), p.hash(),
        PLAN_CAPTURED_FORMAT.format(p.whenCaptured()),
        formatNum(p.queryTimeMicros() / 1000L), formatNum(p.captureCount()),
        Boolean.TRUE.equals(p.shapeChanged()), colorByHash.getOrDefault(p.hash(), Palette.OTHER_COLOR)))
      .toList();

    final List<FamilyRow> family = buildFamily(selectedApp, label, range, env, from, to);

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
  private List<FamilyRow> buildFamily(String app, String label, RangeOption range, @Nullable String env,
                                      @Nullable Instant from, @Nullable Instant to) {
    final String root = LabelFamily.rootOf(label);
    final List<TopGroup> familyGroups = from == null
      ? service.topLabelFamily(app, root, METRIC_NAME, (long) range.minutes(), env, FAMILY_LIMIT)
      : service.topLabelFamily(app, root, METRIC_NAME, env, FAMILY_LIMIT, from, to);
    if (familyGroups.size() <= 1) {
      return List.of();
    }
    return LabelFamily.buildTree(familyGroups, root, label).stream()
      .map(n -> new FamilyRow(n.label(), n.display(), n.depth() * 20,
        formatMs(n.totalMicros()), formatMs(n.meanMicros()), formatNum(n.count()),
        String.format(Locale.US, "%.0f", n.pct()), n.current(),
        UIQueryTotalController.metricDetailUrl(app, env, range.key(), n.label(), from, to)))
      .toList();
  }

  @Nullable
  private static Instant parseInstant(@Nullable String value, String parameter) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new BadRequestException("Invalid " + parameter + " timestamp");
    }
  }

  private String toChartJson(ChartData chartData) {
    return chartJsonb.type(ChartData.class).toJson(chartData).replace("<", "\\u003c");
  }

  private static String emptyChartJson() {
    return "{\"labels\":[],\"datasets\":[]}";
  }

  private static long microsToMs(@Nullable Long micros) {
    return micros == null ? 0L : micros / 1000L;
  }

  private static boolean hasSql(@Nullable String sql) {
    return sql != null && !sql.isBlank();
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  private static String formatMs(long micros) {
    final String formatted = String.format(Locale.US, "%,.3f", micros / 1000.0);
    int end = formatted.length();
    while (end > 0 && formatted.charAt(end - 1) == '0') {
      end--;
    }
    if (end > 0 && formatted.charAt(end - 1) == '.') {
      end--;
    }
    return formatted.substring(0, end);
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
