package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.QueryParam;
import io.avaje.jsonb.Jsonb;
import io.avaje.jex.http.BadRequestException;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseriesTop;
import org.ebean.monitor.v1.model.MetricTimeseriesTopSeries;
import org.ebean.monitor.v1.model.TopGroup;
import org.ebean.monitor.v1.web.V1QueryService;
import org.ebean.monitor.web.RangeOptions.RangeOption;
import org.ebean.monitor.web.view.Breadcrumb;
import org.ebean.monitor.web.view.Option;
import org.ebean.monitor.web.view.QueryTotalView;
import org.ebean.monitor.web.view.QueryTotalView.LegendRow;
import org.jspecify.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard page rendering the "Total Query execution time" stacked-bar
 * chart — a client-side Chart.js chart fed by
 * {@code /v1/apps/{app}/metrics/top/timeseries} data.
 * <p>
 * Filters (app/env/range) are plain dropdowns that resubmit via a full page
 * GET with query params — no HTMX partial-swap wiring yet. Each non-"Other"
 * label (legend row or stacked-bar segment) links through to
 * {@link UIMetricDetailController} for a per-label drill-down.
 */
@Html
@Controller
@Path("/ux")
public class UIQueryTotalController {

  /** Metric family backing the Datadog `ebean.query.total` reference chart. */
  private static final String METRIC_NAME = "ebean.query";

  /** Top-N stacked series before the remainder is folded into "Other". */
  private static final int SERIES_LIMIT = 15;

  private static final DateTimeFormatter BUCKET_LABEL_FORMAT =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US).withZone(ZoneOffset.UTC);

  private final V1QueryService service;
  private final Jsonb jsonb;
  private final Jsonb chartJsonb;

  public UIQueryTotalController(V1QueryService service, Jsonb jsonb) {
    this.service = service;
    this.jsonb = jsonb;
    this.chartJsonb = Jsonb.builder().serializeNulls(true).build();
  }

  @Get("top")
  QueryTotalView queryTotal(@QueryParam("app") @Nullable String appParam,
                            @QueryParam("env") @Nullable String envParam,
                            @QueryParam("range") @Nullable String rangeParam,
                            @QueryParam("from") @Nullable String fromParam,
                            @QueryParam("to") @Nullable String toParam) {

    final List<App> apps = service.listApps(null, null);
    final List<Env> envs = service.listEnvs();
    final String selectedApp = (appParam != null && !appParam.isBlank())
      ? appParam
      : apps.isEmpty() ? "" : apps.get(0).name();
    final String selectedEnv = envParam == null ? "" : envParam;
    final Instant from = parseInstant(fromParam, "from");
    final Instant to = parseInstant(toParam, "to");
    if ((from == null) != (to == null)) {
      throw new BadRequestException("Both from and to timestamps are required");
    }
    final RangeOption range = from == null
      ? RangeOptions.resolve(rangeParam)
      : RangeOptions.custom();
    final long windowMinutes = from == null ? range.minutes() : windowMinutes(from, to);
    final Breadcrumb breadcrumb = new Breadcrumb(List.of(new Breadcrumb.Item("Top")));

    if (selectedApp.isBlank()) {
      return emptyView(breadcrumb, apps, envs, selectedEnv, range);
    }

    final MetricTimeseriesTop data = topTimeseries(
      selectedApp, selectedEnv, from, to, windowMinutes);

    return buildView(breadcrumb, apps, envs, selectedApp, selectedEnv, range, windowMinutes, from, to, data);
  }

  private MetricTimeseriesTop topTimeseries(String app, String selectedEnv,
                                            @Nullable Instant from, @Nullable Instant to,
                                            long windowMinutes) {
    final String env = selectedEnv.isBlank() ? null : selectedEnv;
    if (from != null) {
      return service.getTopAppMetricsTimeseries(
        app, METRIC_NAME, null, null, "total", SERIES_LIMIT, null, env, from, to);
    }
    return service.getTopAppMetricsTimeseries(
      app, METRIC_NAME, null, null, "total", windowMinutes, null, SERIES_LIMIT, null, env);
  }

  private List<TopGroup> topMetrics(String app, String orderBy, String selectedEnv,
                                    @Nullable Instant from, @Nullable Instant to,
                                    long windowMinutes) {
    final String env = selectedEnv.isBlank() ? null : selectedEnv;
    if (from != null) {
      return service.topAppMetrics(
        app, "label", METRIC_NAME, null, null, null, orderBy,
        SERIES_LIMIT, null, env, from, to);
    }
    return service.topAppMetrics(
      app, "label", METRIC_NAME, null, null, null, orderBy,
      windowMinutes, null, SERIES_LIMIT, null, env);
  }

  private static long windowMinutes(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new BadRequestException("The from timestamp must be before the to timestamp");
    }
    final long seconds = Duration.between(from, to).toSeconds();
    return Math.max(1L, (seconds + 59L) / 60L);
  }

  @Nullable
  static Instant parseInstant(@Nullable String value, String parameter) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new BadRequestException("Invalid " + parameter + " timestamp");
    }
  }

  private QueryTotalView buildView(Breadcrumb breadcrumb, List<App> apps, List<Env> envs,
                                    String selectedApp, String selectedEnv, RangeOption range, long windowMinutes,
                                    @Nullable Instant from, @Nullable Instant to,
                                    MetricTimeseriesTop data) {
    final List<MetricTimeseriesTopSeries> series = data.series();
    if (series.isEmpty()) {
      return new QueryTotalView(breadcrumb, false, selectedApp, appOptions(apps, selectedApp),
        selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
        data.bucketMinutes(), emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), List.of());
    }

    final List<MetricTimeBucket> firstBuckets = series.get(0).buckets();
    final int bucketCount = firstBuckets.size();
    final List<String> labels = new ArrayList<>(bucketCount);
    for (MetricTimeBucket bucket : firstBuckets) {
      labels.add(BUCKET_LABEL_FORMAT.format(bucket.eventTime()));
    }

    final List<ChartData.ChartDataset> datasets = new ArrayList<>(series.size());
    final List<LegendRow> legend = new ArrayList<>(series.size());

    int colorIndex = 0;
    for (MetricTimeseriesTopSeries s : series) {
      final String color = s.other() ? Palette.OTHER_COLOR : Palette.colorFor(colorIndex++);

      final List<Long> valuesMs = new ArrayList<>(bucketCount);
      long execCount = 0L;
      final List<MetricTimeBucket> buckets = s.buckets();
      for (int i = 0; i < bucketCount; i++) {
        final MetricTimeBucket bucket = buckets.get(i);
        final long ms = bucket.total() / 1000L;
        valuesMs.add(ms);
        execCount += bucket.count();
      }
      datasets.add(new ChartData.ChartDataset(s.group(), valuesMs, color));

      final long totalMs = s.totalMicros() / 1000L;
      final long avgMs = execCount == 0L ? 0L : totalMs / execCount;
      final String detailUrl = s.other() ? null
        : metricDetailUrl(selectedApp, selectedEnv, range.key(), s.group(), from, to);
      legend.add(new LegendRow(color, s.group(), formatNum(totalMs), formatNum(execCount),
        formatNum(avgMs), formatNum(s.hashCount()), s.other(), detailUrl));
    }

    final List<Long> timestamps = firstBuckets.stream().map(bucket -> bucket.eventTime().toEpochMilli()).toList();
    final ChartData chartData = new ChartData(labels, timestamps, datasets, data.bucketMinutes());
    // Neutralise "</script>" (and any other embedded tag) since group labels
    // are user-supplied query labels — this JSON is inlined into a <script>
    // block in the template.
    final String chartDataJson = chartJsonb.type(ChartData.class).toJson(chartData).replace("<", "\\u003c");
    final String meanMaxMeanJson = chartJsonb.type(ChartData.class)
      .toJson(derivedChartData(data, false)).replace("<", "\\u003c");
    final String meanMaxMaxJson = chartJsonb.type(ChartData.class)
      .toJson(derivedChartData(data, true)).replace("<", "\\u003c");
    final String topByTimeJson = rankingChartJson(topMetrics(
      selectedApp, "total", selectedEnv, from, to, windowMinutes), false);
    final String topByMeanJson = rankingChartJson(topMetrics(
      selectedApp, "mean", selectedEnv, from, to, windowMinutes), true);

    return new QueryTotalView(breadcrumb, true, selectedApp, appOptions(apps, selectedApp),
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      data.bucketMinutes(), chartDataJson, meanMaxMeanJson, meanMaxMaxJson,
      topByTimeJson, topByMeanJson, legend);
  }

  private QueryTotalView emptyView(Breadcrumb breadcrumb, List<App> apps, List<Env> envs,
                                    String selectedEnv, RangeOption range) {
    return new QueryTotalView(breadcrumb, false, "", appOptions(apps, ""),
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      0L, emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), List.of());
  }

  private ChartData derivedChartData(MetricTimeseriesTop data, boolean max) {
    final List<MetricTimeseriesTopSeries> series = data.series();
    final List<String> labels = series.isEmpty() ? List.of() : series.get(0).buckets().stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = series.isEmpty() ? List.of() : series.get(0).buckets().stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final List<ChartData.ChartDataset> datasets = new ArrayList<>(series.size());
    int colorIndex = 0;
    for (MetricTimeseriesTopSeries s : series) {
      final String color = s.other() ? Palette.OTHER_COLOR : Palette.colorFor(colorIndex++);
      final List<Long> values = s.buckets().stream()
        .map(bucket -> {
          if (max) {
            return bucket.count() == 0L ? null : Long.valueOf(bucket.max() / 1000L);
          }
          return bucket.count() == 0L ? null : Long.valueOf((bucket.total() / 1000L) / bucket.count());
        })
        .toList();
      datasets.add(new ChartData.ChartDataset(s.group(), values, color));
    }
    return new ChartData(labels, timestamps, datasets, data.bucketMinutes());
  }

  private String rankingChartJson(List<TopGroup> groups, boolean mean) {
    final List<String> labels = groups.stream().map(TopGroup::group).toList();
    final List<Long> values = groups.stream()
      .map(g -> (mean ? g.meanMicros() : g.totalMicros()) / 1000L)
      .toList();
    final ChartData chartData = new ChartData(labels, List.of(),
      List.of(new ChartData.ChartDataset("Top", values, "#b7d5f7")), 0L);
    return jsonb.type(ChartData.class).toJson(chartData).replace("<", "\\u003c");
  }

  private static String emptyChartJson() {
    return "{\"labels\":[],\"datasets\":[]}";
  }

  /** Link from a query-total label (legend row / stacked-bar segment) to its {@link UIMetricDetailController} drill-down. */
  static String metricDetailUrl(String app, String env, String range, String label) {
    return metricDetailUrl(app, env, range, label, null, null);
  }

  static String metricDetailUrl(String app, String env, String range, String label,
                                @Nullable Instant from, @Nullable Instant to) {
    final StringBuilder sb = new StringBuilder("/ux/metric-detail?app=")
      .append(urlEncode(app))
      .append("&range=").append(urlEncode(range))
      .append("&label=").append(urlEncode(label));
    if (env != null && !env.isBlank()) {
      sb.append("&env=").append(urlEncode(env));
    }
    appendAbsoluteRange(sb, from, to);
    return sb.toString();
  }

  static String queryHashUrl(String app, String env, String range, String label, String hash) {
    return queryHashUrl(app, env, range, label, hash, null, null);
  }

  static String queryHashUrl(String app, String env, String range, String label, String hash,
                             @Nullable Instant from, @Nullable Instant to) {
    final StringBuilder sb = new StringBuilder("/ux/query-hash?app=")
      .append(urlEncode(app))
      .append("&range=").append(urlEncode(range))
      .append("&label=").append(urlEncode(label))
      .append("&hash=").append(urlEncode(hash));
    if (env != null && !env.isBlank()) {
      sb.append("&env=").append(urlEncode(env));
    }
    appendAbsoluteRange(sb, from, to);
    return sb.toString();
  }

  static String topUrl(String app, String env, String range,
                       @Nullable Instant from, @Nullable Instant to) {
    final StringBuilder sb = new StringBuilder("/ux/top?app=")
      .append(urlEncode(app))
      .append("&env=").append(urlEncode(env))
      .append("&range=").append(urlEncode(range));
    appendAbsoluteRange(sb, from, to);
    return sb.toString();
  }

  static String queryPlanUrl(long id, String range, @Nullable Instant from, @Nullable Instant to) {
    final StringBuilder sb = new StringBuilder("/ux/query-plan?id=")
      .append(id)
      .append("&range=").append(urlEncode(range));
    appendAbsoluteRange(sb, from, to);
    return sb.toString();
  }

  static String rangeKey(@Nullable String range, @Nullable Instant from, @Nullable Instant to) {
    return from == null && to == null ? RangeOptions.resolve(range).key() : RangeOptions.custom().key();
  }

  private static void appendAbsoluteRange(StringBuilder sb, @Nullable Instant from, @Nullable Instant to) {
    if (from != null && to != null) {
      sb.append("&from=").append(urlEncode(from.toString()))
        .append("&to=").append(urlEncode(to.toString()));
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static List<Option> appOptions(List<App> apps, String selected) {
    return apps.stream()
      .map(a -> new Option(a.name(), a.name(), a.name().equals(selected)))
      .toList();
  }

  private static List<Option> envOptions(List<Env> envs, String selected) {
    final List<Option> options = new ArrayList<>();
    options.add(new Option("", "All environments", selected.isBlank()));
    for (Env env : envs) {
      options.add(new Option(env.name(), env.name(), env.name().equals(selected)));
    }
    return options;
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }
}
