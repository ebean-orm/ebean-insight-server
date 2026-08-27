package org.ebean.monitor.web;

import io.avaje.htmx.api.Html;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Get;
import io.avaje.http.api.Path;
import io.avaje.http.api.Produces;
import io.avaje.http.api.QueryParam;
import io.avaje.jsonb.Json;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
  private static final String JVM_MEMORY_RSS = "jvm.memory.process.vmrss";
  private static final String JVM_MEMORY_HEAP = "jvm.memory.heap.used";
  private static final String JVM_CPU_USER_MICROS = "jvm.cgroup.cpu.userMicros";
  private static final String JVM_CPU_SYSTEM_MICROS = "jvm.cgroup.cpu.systemMicros";

  private static final DateTimeFormatter BUCKET_LABEL_FORMAT =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US).withZone(ZoneOffset.UTC);

  private final V1QueryService service;
  private final Jsonb chartJsonb;

  private record QueryTotalRequest(String app, String env, RangeOption range,
                                   @Nullable Instant from, @Nullable Instant to,
                                   long windowMinutes, boolean compactLayout, String timezone) {
  }

  @Json
  record QueryTotalData(boolean hasData, ChartData queryTotal, ChartData mean, ChartData max,
                        ChartData count, ChartData topByTime, ChartData topByMean,
                        boolean showTopRankings,
                        List<LegendData> legend, boolean datasourcePoolDashboard,
                        ChartData datasourcePool, ChartData datasourcePoolTiming,
                        boolean webApiDashboard, List<String> webApiGroups,
                        ChartData webApi, ChartData webApiMean, ChartData webApiMax,
                        ChartData webApiCount, boolean jvmDashboard, ChartData jvmMemory,
                        ChartData jvmCpu, String queryRate, String webApiRate,
                        String queryLoad, String webApiLoad) {
  }

  @Json
  record LegendData(String color, String group, String totalMs, String executions, String avgMs,
                    String rate, String hashCount, boolean other, @Nullable String detailUrl) {
  }

  public UIQueryTotalController(V1QueryService service) {
    this.service = service;
    this.chartJsonb = Jsonb.builder().serializeNulls(true).build();
  }

  @Get("top")
  QueryTotalView queryTotal(@QueryParam("app") @Nullable String appParam,
                            @QueryParam("env") @Nullable String envParam,
                            @QueryParam("range") @Nullable String rangeParam,
                            @QueryParam("from") @Nullable String fromParam,
                            @QueryParam("to") @Nullable String toParam,
                            @QueryParam("layout") @Nullable String layoutParam,
                            @QueryParam("tz") @Nullable String timezoneParam) {

    final List<App> apps = service.listApps(null, null);
    final List<Env> envs = service.listEnvs();
    final String selectedApp = (appParam != null && !appParam.isBlank())
      ? appParam
      : apps.isEmpty() ? "" : apps.get(0).name();
    final String selectedEnv = envParam == null ? "" : envParam;
    final QueryTotalRequest request = queryTotalRequest(
      selectedApp, selectedEnv, rangeParam, fromParam, toParam, layoutParam, timezoneParam);
    final RangeOption range = request.range();
    final long windowMinutes = request.windowMinutes();
    final Breadcrumb breadcrumb = new Breadcrumb(List.of(new Breadcrumb.Item("Top")));

    if (selectedApp.isBlank()) {
      return emptyView(breadcrumb, apps, envs, selectedEnv, range, request.compactLayout());
    }

    final QueryTotalData data = dashboardData(
      selectedApp, selectedEnv, range, request.from(), request.to(), windowMinutes,
      request.compactLayout(), request.timezone());

    return buildView(breadcrumb, apps, envs, selectedApp, selectedEnv, range, data, request.compactLayout());
  }

  @Get("top/data")
  @Produces("application/json")
  String queryTotalData(@QueryParam("app") @Nullable String appParam,
                        @QueryParam("env") @Nullable String envParam,
                        @QueryParam("range") @Nullable String rangeParam,
                        @QueryParam("from") @Nullable String fromParam,
                        @QueryParam("to") @Nullable String toParam,
                        @QueryParam("layout") @Nullable String layoutParam,
                        @QueryParam("tz") @Nullable String timezoneParam) {
    final QueryTotalRequest request = queryTotalRequest(
      appParam == null ? "" : appParam,
      envParam == null ? "" : envParam,
      rangeParam, fromParam, toParam, layoutParam, timezoneParam);
    final QueryTotalData data = request.app().isBlank()
      ? emptyData()
      : dashboardData(request.app(), request.env(), request.range(),
        request.from(), request.to(), request.windowMinutes(), request.compactLayout(), request.timezone());
    return chartJsonb.type(QueryTotalData.class).toJson(data).replace("<", "\\u003c");
  }

  private QueryTotalRequest queryTotalRequest(String app, String env, @Nullable String rangeParam,
                                               @Nullable String fromParam, @Nullable String toParam,
                                               @Nullable String layoutParam, @Nullable String timezoneParam) {
    final Instant from = parseInstant(fromParam, "from");
    final Instant to = parseInstant(toParam, "to");
    if ((from == null) != (to == null)) {
      throw new BadRequestException("Both from and to timestamps are required");
    }
    final RangeOption range = from == null ? RangeOptions.resolve(rangeParam) : RangeOptions.custom();
    final long windowMinutes = from == null ? range.minutes() : windowMinutes(from, to);
    return new QueryTotalRequest(
      app, env, range, from, to, windowMinutes, "compact".equals(layoutParam),
      timezoneParam == null ? "" : timezoneParam);
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

  private QueryTotalData dashboardData(String selectedApp, String selectedEnv, RangeOption range,
                                       @Nullable Instant from, @Nullable Instant to, long windowMinutes,
                                       boolean compactLayout, String timezone) {
    final MetricTimeseriesTop data = topTimeseries(selectedApp, selectedEnv, from, to, windowMinutes);
    final List<MetricTimeseriesTopSeries> series = data.series();
    final boolean queryData = !series.isEmpty();
    final List<MetricTimeBucket> firstBuckets = queryData
      ? series.get(0).buckets()
      : List.of();
    final int bucketCount = firstBuckets.size();
    final List<String> labels = new ArrayList<>(bucketCount);
    for (MetricTimeBucket bucket : firstBuckets) {
      labels.add(BUCKET_LABEL_FORMAT.format(bucket.eventTime()));
    }

    final List<ChartData.ChartDataset> datasets = new ArrayList<>(series.size());
    final List<LegendData> legend = new ArrayList<>(series.size());
    long totalExecutions = 0L;
    long totalMicros = 0L;
    final double elapsedSeconds = from == null
      ? windowMinutes * 60d
      : Duration.between(from, to).toMillis() / 1000d;

    int colorIndex = 0;
    for (MetricTimeseriesTopSeries s : series) {
      final String color = s.other() ? Palette.OTHER_COLOR : Palette.colorFor(colorIndex++);

      final List<Long> valuesMs = new ArrayList<>(bucketCount);
      long execCount = 0L;
      final List<MetricTimeBucket> buckets = s.buckets();
      for (int i = 0; i < bucketCount; i++) {
        final MetricTimeBucket bucket = buckets.get(i);
        final long ms = bucket.total() / 1000L / data.bucketMinutes();
        valuesMs.add(ms);
        execCount += bucket.count();
      }
      datasets.add(new ChartData.ChartDataset(s.group(), valuesMs, color));

      final long totalMs = s.totalMicros() / 1000L;
      final long avgMs = execCount == 0L ? 0L : totalMs / execCount;
      totalExecutions += execCount;
      totalMicros += s.totalMicros();
      final String detailUrl = s.other() ? null
        : metricDetailUrl(selectedApp, selectedEnv, range.key(), s.group(), from, to, timezone);
      legend.add(new LegendData(color, s.group(), formatNum(totalMs), formatNum(execCount),
        formatRate(execCount / elapsedSeconds), formatNum(avgMs), formatNum(s.hashCount()),
        s.other(), detailUrl));
    }

    final List<Long> timestamps = firstBuckets.stream().map(bucket -> bucket.eventTime().toEpochMilli()).toList();
    final ChartData chartData = queryData
      ? new ChartData(labels, timestamps, datasets, data.bucketMinutes())
      : emptyDataChart();
    final ChartData meanMaxMean = queryData ? derivedChartData(data, false) : emptyDataChart();
    final ChartData meanMaxMax = queryData ? derivedChartData(data, true) : emptyDataChart();
    final ChartData meanMaxCount = queryData ? countChartData(data) : emptyDataChart();
    final boolean showTopRankings = queryData && !compactLayout && series.size() > 1;
    final ChartData topByTime = showTopRankings
      ? rankingChartData(topMetrics(selectedApp, "total", selectedEnv, from, to, windowMinutes), false)
      : emptyDataChart();
    final ChartData topByMean = showTopRankings
      ? rankingChartData(topMetrics(selectedApp, "mean", selectedEnv, from, to, windowMinutes), true)
      : emptyDataChart();
    final boolean datasourcePoolDashboard = service.isDatasourcePoolDashboardEnabled(selectedApp);
    final ChartData datasourcePool = datasourcePoolDashboard
      ? gaugeChartData(service.getGaugeTimeseries(selectedApp, "datasource.pool.size", "type",
        from == null ? windowMinutes : null, null,
        selectedEnv.isBlank() ? null : selectedEnv, from, to))
      : emptyDataChart();
    final ChartData datasourcePoolTiming = datasourcePoolDashboard
      ? timingChartData(service.getDatasourcePoolTimingTimeseries(selectedApp,
        from == null ? windowMinutes : null, null,
        selectedEnv.isBlank() ? null : selectedEnv, from, to), poolColors(datasourcePool))
      : emptyDataChart();
    final boolean webApiDashboard = service.isWebApiDashboardEnabled(selectedApp);
    final MetricTimeseriesTop webApiData = webApiDashboard
      ? webApiTimeseries(selectedApp, selectedEnv, from, to, windowMinutes)
      : MetricTimeseriesTop.builder().series(List.of()).build();
    final ChartData webApi = webApiDashboard ? webApiChartData(webApiData, "total") : emptyDataChart();
    final ChartData webApiMean = webApiDashboard
      ? webApiChartData(webApiData, "mean") : emptyDataChart();
    final ChartData webApiMax = webApiDashboard
      ? webApiChartData(webApiData, "max") : emptyDataChart();
    final ChartData webApiCount = webApiDashboard
      ? webApiChartData(webApiData, "count") : emptyDataChart();
    final List<String> webApiGroups = webApiData.series().stream()
      .map(MetricTimeseriesTopSeries::group).toList();
    final long webApiExecutions = webApiData.series().stream()
      .mapToLong(s -> s.buckets().stream().mapToLong(MetricTimeBucket::count).sum()).sum();
    final long webApiMicros = webApiData.series().stream()
      .mapToLong(MetricTimeseriesTopSeries::totalMicros).sum();
    final String queryRate = formatRate(totalExecutions / elapsedSeconds);
    final String webApiRate = formatRate(webApiExecutions / elapsedSeconds);
    final String queryLoad = formatRate(totalMicros / 1_000_000d / elapsedSeconds);
    final String webApiLoad = formatRate(webApiMicros / 1_000_000d / elapsedSeconds);
    final boolean jvmDashboard = service.isJvmDashboardEnabled(selectedApp);
    final ChartData jvmMemory = jvmDashboard
      ? jvmMemoryChartData(selectedApp, selectedEnv, from, to, windowMinutes)
      : emptyDataChart();
    final ChartData jvmCpu = jvmDashboard
      ? jvmCpuChartData(selectedApp, selectedEnv, from, to, windowMinutes)
      : emptyDataChart();

    final boolean hasData = hasDashboardData(queryData, datasourcePool, datasourcePoolTiming,
      webApi, webApiMean, webApiMax, webApiCount, jvmMemory, jvmCpu);
    return new QueryTotalData(hasData, chartData, meanMaxMean, meanMaxMax, meanMaxCount, topByTime, topByMean,
      showTopRankings, legend, datasourcePoolDashboard, datasourcePool, datasourcePoolTiming,
      webApiDashboard, webApiGroups, webApi, webApiMean, webApiMax, webApiCount,
      jvmDashboard, jvmMemory, jvmCpu, queryRate, webApiRate,
      queryLoad, webApiLoad);
  }

  static boolean hasDashboardData(boolean queryData, ChartData... charts) {
    if (queryData) {
      return true;
    }
    for (ChartData chart : charts) {
      if (!chart.labels().isEmpty() && (!chart.datasets().isEmpty() || !chart.timestamps().isEmpty())) {
        return true;
      }
    }
    return false;
  }

  private QueryTotalView buildView(Breadcrumb breadcrumb, List<App> apps, List<Env> envs,
                                   String selectedApp, String selectedEnv, RangeOption range,
                                   QueryTotalData data, boolean compactLayout) {
    return new QueryTotalView(breadcrumb, data.hasData(), compactLayout, selectedApp, appOptions(apps, selectedApp),
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      data.queryTotal().bucketMinutes(), toChartJson(data.queryTotal()), toChartJson(data.mean()),
      toChartJson(data.max()), toChartJson(data.count()), toChartJson(data.topByTime()),
      toChartJson(data.topByMean()), data.showTopRankings(),
      data.legend().stream().map(UIQueryTotalController::legendRow).toList(),
      data.datasourcePoolDashboard(), toChartJson(data.datasourcePool()),
      toChartJson(data.datasourcePoolTiming()), data.webApiDashboard(), data.webApiGroups(),
      toChartJson(data.webApi()), toChartJson(data.webApiMean()), toChartJson(data.webApiMax()),
      toChartJson(data.webApiCount()), data.jvmDashboard(), toChartJson(data.jvmMemory()),
      toChartJson(data.jvmCpu()), data.queryRate(), data.webApiRate(), data.queryLoad(), data.webApiLoad());
  }

  private QueryTotalView emptyView(Breadcrumb breadcrumb, List<App> apps, List<Env> envs,
                                    String selectedEnv, RangeOption range, boolean compactLayout) {
    return new QueryTotalView(breadcrumb, false, compactLayout, "", appOptions(apps, ""),
      selectedEnv, envOptions(envs, selectedEnv), range.key(), RangeOptions.options(range.key()),
      0L, emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(),
      emptyChartJson(), false,
      List.of(), false, emptyChartJson(), emptyChartJson(),
      false, List.of(), emptyChartJson(), emptyChartJson(), emptyChartJson(), emptyChartJson(), false,
      emptyChartJson(), emptyChartJson(),
      "-", "-", "-", "-");
  }

  private MetricTimeseriesTop webApiTimeseries(String app, String env,
                                               @Nullable Instant from, @Nullable Instant to,
                                               long windowMinutes) {
    final String selectedEnv = env.isBlank() ? null : env;
    if (from != null) {
      return service.getTopAppMetricsTimeseries(app, "web.api", null, null, "total",
        SERIES_LIMIT, null, selectedEnv, from, to);
    }
    return service.getTopAppMetricsTimeseries(app, "web.api", null, null, "total",
      windowMinutes, null, SERIES_LIMIT, null, selectedEnv);
  }

  private ChartData gaugeChartData(MetricTimeseriesTop data) {
    if (data.series().isEmpty()) {
      return emptyDataChart();
    }

    final List<MetricTimeBucket> buckets = data.series().get(0).buckets();
    final List<String> labels = buckets.stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = buckets.stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final List<ChartData.ChartDataset> datasets = new ArrayList<>();
    int colorIndex = 0;
    for (MetricTimeseriesTopSeries series : data.series().stream()
      .sorted(Comparator.comparingLong(MetricTimeseriesTopSeries::totalMicros))
      .toList()) {
      datasets.add(new ChartData.ChartDataset(series.group(),
        poolSizeValues(series.buckets(), data.bucketMinutes()),
        poolSizeColor(colorIndex++)));
    }
    return new ChartData(labels, timestamps, datasets, data.bucketMinutes());
  }

  static List<Long> poolSizeValues(List<MetricTimeBucket> buckets, long bucketMinutes) {
    return buckets.stream()
      .map(bucket -> bucket.total() / bucketMinutes)
      .toList();
  }

  private String poolSizeColor(int index) {
    return switch (index % 4) {
      case 0 -> "#2e7d32";
      case 1 -> "#66bb6a";
      case 2 -> "#1b5e20";
      default -> "#a5d6a7";
    };
  }

  private ChartData timingChartData(MetricTimeseriesTop data, Map<String, String> poolColors) {
    if (data.series().isEmpty()) {
      return emptyDataChart();
    }
    final List<MetricTimeBucket> buckets = data.series().get(0).buckets();
    final List<String> labels = buckets.stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = buckets.stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final List<ChartData.ChartDataset> datasets = data.series().stream()
      .sorted(Comparator.comparingInt(series -> series.group().startsWith("Acquire") ? 0 : 1))
      .map(series -> new ChartData.ChartDataset(series.group(),
        poolTimingValues(series.buckets()),
        timingColor(series.group(), poolColors)))
      .toList();
    return new ChartData(labels, timestamps, datasets, data.bucketMinutes());
  }

  static List<Long> poolTimingValues(List<MetricTimeBucket> buckets) {
    return buckets.stream().map(MetricTimeBucket::total).toList();
  }

  private Map<String, String> poolColors(ChartData data) {
    final Map<String, String> colors = new HashMap<>();
    for (ChartData.ChartDataset dataset : data.datasets()) {
      colors.put(dataset.label(), dataset.backgroundColor());
    }
    return colors;
  }

  private String timingColor(String label, Map<String, String> poolColors) {
    final boolean wait = label.startsWith("Wait");
    final boolean readonly = label.endsWith("readonly");
    if (wait) {
      return readonly ? "#f6c344" : "#d64545";
    }
    final int separator = label.indexOf(" · ");
    if (separator >= 0) {
      final String poolType = label.substring(separator + 3);
      final String poolColor = poolColors.get(poolType);
      if (poolColor != null) {
        return poolColor;
      }
    }
    return readonly ? "#f6b26b" : "#b45f06";
  }

  private ChartData webApiChartData(MetricTimeseriesTop data, String mode) {
    if (data.series().isEmpty()) {
      return emptyDataChart();
    }
    final List<MetricTimeBucket> firstBuckets = data.series().get(0).buckets();
    final List<String> labels = firstBuckets.stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = firstBuckets.stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final List<ChartData.ChartDataset> datasets = new ArrayList<>();
    int colorIndex = 0;
    for (MetricTimeseriesTopSeries series : data.series()) {
      final List<Long> values = series.buckets().stream().map(bucket -> {
        return switch (mode) {
          case "mean" -> bucket.count() == 0L ? null : (bucket.total() / bucket.count()) / 1000L;
          case "max" -> bucket.count() == 0L ? null : bucket.max() / 1000L;
          case "count" -> bucket.count();
          default -> bucket.total() / 1000L / data.bucketMinutes();
        };
      }).toList();
      datasets.add(new ChartData.ChartDataset(series.group(), values, Palette.colorFor(colorIndex++)));
    }
    return new ChartData(labels, timestamps, datasets, data.bucketMinutes());
  }

  private ChartData jvmMemoryChartData(String app, String env, @Nullable Instant from,
                                       @Nullable Instant to, long windowMinutes) {
    final MetricTimeseriesTop rss = jvmGaugeTimeseries(app, env, JVM_MEMORY_RSS, from, to, windowMinutes);
    final MetricTimeseriesTop heap = jvmGaugeTimeseries(app, env, JVM_MEMORY_HEAP, from, to, windowMinutes);
    return jvmMetricChartData(List.of(new JvmMetric("RSS", rss), new JvmMetric("Heap", heap)));
  }

  private ChartData jvmCpuChartData(String app, String env, @Nullable Instant from,
                                    @Nullable Instant to, long windowMinutes) {
    final MetricTimeseriesTop user = jvmGaugeTimeseries(
      app, env, JVM_CPU_USER_MICROS, from, to, windowMinutes);
    final MetricTimeseriesTop system = jvmGaugeTimeseries(
      app, env, JVM_CPU_SYSTEM_MICROS, from, to, windowMinutes);
    return jvmCpuChartData(user, system);
  }

  private MetricTimeseriesTop jvmGaugeTimeseries(String app, String env, String name,
                                                 @Nullable Instant from, @Nullable Instant to,
                                                 long windowMinutes) {
    return service.getGaugeTimeseries(app, name, "pod",
      from == null ? windowMinutes : null, null,
      env.isBlank() ? null : env, from, to);
  }

  private record JvmMetric(String label, MetricTimeseriesTop data) {
  }

  private ChartData jvmMetricChartData(List<JvmMetric> metrics) {
    final MetricTimeseriesTop first = metrics.stream()
      .map(JvmMetric::data)
      .filter(data -> !data.series().isEmpty())
      .findFirst()
      .orElse(null);
    if (first == null) {
      return emptyDataChart();
    }
    final List<MetricTimeBucket> firstBuckets = first.series().get(0).buckets();
    final List<String> labels = firstBuckets.stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = firstBuckets.stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final List<ChartData.ChartDataset> datasets = new ArrayList<>();
    final Map<String, String> colorsByPod = new HashMap<>();
    for (JvmMetric metric : metrics) {
      for (MetricTimeseriesTopSeries series : metric.data().series()) {
        final List<Long> values = series.buckets().stream()
          .map(bucket -> bucket.count() == 0L ? null : bucket.total())
          .toList();
        final String color = colorsByPod.computeIfAbsent(
          series.group(), _ -> Palette.colorFor(colorsByPod.size()));
        datasets.add(new ChartData.ChartDataset(
          series.group() + " · " + metric.label(), values, color));
      }
    }
    return new ChartData(labels, timestamps, datasets, first.bucketMinutes());
  }

  private ChartData jvmCpuChartData(MetricTimeseriesTop user, MetricTimeseriesTop system) {
    final MetricTimeseriesTop first = !user.series().isEmpty() ? user : system;
    if (first.series().isEmpty()) {
      return emptyDataChart();
    }
    final List<MetricTimeBucket> firstBuckets = first.series().get(0).buckets();
    final List<String> labels = firstBuckets.stream()
      .map(bucket -> BUCKET_LABEL_FORMAT.format(bucket.eventTime()))
      .toList();
    final List<Long> timestamps = firstBuckets.stream()
      .map(bucket -> bucket.eventTime().toEpochMilli())
      .toList();
    final Map<String, MetricTimeseriesTopSeries> userByPod = seriesByPod(user);
    final Map<String, MetricTimeseriesTopSeries> systemByPod = seriesByPod(system);
    final var pods = new LinkedHashSet<String>();
    pods.addAll(userByPod.keySet());
    pods.addAll(systemByPod.keySet());
    final List<ChartData.ChartDataset> datasets = new ArrayList<>(pods.size() * 2);
    for (String pod : pods) {
      final MetricTimeseriesTopSeries systemSeries = systemByPod.get(pod);
      if (systemSeries != null) {
        datasets.add(new ChartData.ChartDataset(
          pod + " · System", cpuMillicores(systemSeries.buckets(), system.bucketMinutes()), Palette.colorFor(1)));
      }
    }
    for (String pod : pods) {
      final MetricTimeseriesTopSeries userSeries = userByPod.get(pod);
      if (userSeries != null) {
        datasets.add(new ChartData.ChartDataset(
          pod + " · User", cpuMillicores(userSeries.buckets(), user.bucketMinutes()), Palette.colorFor(0)));
      }
    }
    return new ChartData(labels, timestamps, datasets, first.bucketMinutes());
  }

  private static Map<String, MetricTimeseriesTopSeries> seriesByPod(MetricTimeseriesTop data) {
    final Map<String, MetricTimeseriesTopSeries> byPod = new LinkedHashMap<>();
    for (MetricTimeseriesTopSeries series : data.series()) {
      byPod.put(series.group(), series);
    }
    return byPod;
  }

  static List<Long> cpuMillicores(List<MetricTimeBucket> buckets, long bucketMinutes) {
    final List<Long> values = new ArrayList<>(buckets.size());
    Long previous = null;
    for (MetricTimeBucket bucket : buckets) {
      if (bucket.count() == 0L) {
        values.add(null);
        previous = null;
        continue;
      }
      final long current = bucket.total();
      if (previous == null) {
        values.add(null);
      } else {
        final long delta = current - previous;
        values.add(delta > 0L
          ? Math.round(delta / (bucketMinutes * 60d * 1_000d))
          : null);
      }
      previous = current;
    }
    return values;
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

  private ChartData countChartData(MetricTimeseriesTop data) {
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
      final List<Long> values = s.buckets().stream().map(MetricTimeBucket::count).toList();
      datasets.add(new ChartData.ChartDataset(s.group(), values, color));
    }
    return new ChartData(labels, timestamps, datasets, data.bucketMinutes());
  }

  private ChartData rankingChartData(List<TopGroup> groups, boolean mean) {
    final List<String> labels = groups.stream().map(TopGroup::group).toList();
    final List<Long> values = groups.stream()
      .map(g -> (mean ? g.meanMicros() : g.totalMicros()) / 1000L)
      .toList();
    final ChartData chartData = new ChartData(labels, List.of(),
      List.of(new ChartData.ChartDataset("Top", values, "#b7d5f7")), 0L);
    return chartData;
  }

  private static String emptyChartJson() {
    return "{\"labels\":[],\"datasets\":[]}";
  }

  private static ChartData emptyDataChart() {
    return new ChartData(List.of(), List.of(), List.of(), 0L);
  }

  private static QueryTotalData emptyData() {
    final ChartData empty = emptyDataChart();
    return new QueryTotalData(false, empty, empty, empty, empty, empty, empty, false, List.of(),
      false, empty, empty, false, List.of(), empty, empty, empty, empty,
      false, empty, empty, "-", "-", "-", "-");
  }

  private String toChartJson(ChartData data) {
    return chartJsonb.type(ChartData.class).toJson(data).replace("<", "\\u003c");
  }

  private static LegendRow legendRow(LegendData data) {
    return new LegendRow(data.color(), data.group(), data.totalMs(), data.executions(),
      data.avgMs(), data.rate(), data.hashCount(), data.other(), data.detailUrl());
  }

  /** Link from a query-total label (legend row / stacked-bar segment) to its {@link UIMetricDetailController} drill-down. */
  static String metricDetailUrl(String app, String env, String range, String label) {
    return metricDetailUrl(app, env, range, label, null, null);
  }

  static String metricDetailUrl(String app, String env, String range, String label,
                                @Nullable Instant from, @Nullable Instant to) {
    return metricDetailUrl(app, env, range, label, from, to, null);
  }

  static String metricDetailUrl(String app, String env, String range, String label,
                                @Nullable Instant from, @Nullable Instant to,
                                @Nullable String timezone) {
    final StringBuilder sb = new StringBuilder("/ux/metric-detail?app=")
      .append(urlEncode(app))
      .append("&range=").append(urlEncode(range))
      .append("&label=").append(urlEncode(label));
    if (env != null && !env.isBlank()) {
      sb.append("&env=").append(urlEncode(env));
    }
    appendAbsoluteRange(sb, from, to);
    appendTimezone(sb, timezone);
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
    return topUrl(app, env, range, from, to, null);
  }

  static String topUrl(String app, String env, String range,
                       @Nullable Instant from, @Nullable Instant to,
                       @Nullable String timezone) {
    final StringBuilder sb = new StringBuilder("/ux/top?app=")
      .append(urlEncode(app))
      .append("&env=").append(urlEncode(env))
      .append("&range=").append(urlEncode(range));
    appendAbsoluteRange(sb, from, to);
    appendTimezone(sb, timezone);
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

  private static void appendTimezone(StringBuilder sb, @Nullable String timezone) {
    if (timezone != null && !timezone.isBlank()) {
      sb.append("&tz=").append(urlEncode(timezone));
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
    options.add(new Option("", "All", selected.isBlank()));
    for (Env env : envs) {
      options.add(new Option(env.name(), env.name(), env.name().equals(selected)));
    }
    return options;
  }

  private static String formatNum(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  private static String formatRate(double value) {
    return String.format(Locale.US, "%,.2f", value);
  }
}
