package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

import java.util.List;

/**
 * View model for the "Total Query execution time" stacked-bar dashboard page
 * ({@code /ux/top}).
 * <p>
 * Renders a client-side Chart.js chart ({@link #chartDataJson()}) fed by the
 * {@code /v1/apps/{app}/metrics/top/timeseries} data, plus a shared
 * {@link #legend()} summary table.
 */
@JStache(path = "query-total")
public record QueryTotalView(
  Breadcrumb breadcrumb,
  boolean hasData,
  boolean compactLayout,
  String app,
  List<Option> apps,
  String env,
  List<Option> envs,
  String range,
  List<Option> ranges,
  long bucketMinutes,
  String chartDataJson,
  String meanMaxMeanJson,
  String meanMaxMaxJson,
  String meanMaxCountJson,
  String topByTimeJson,
  String topByMeanJson,
  boolean showTopRankings,
  List<LegendRow> legend,
  boolean datasourcePoolDashboard,
  String datasourcePoolJson,
  String datasourcePoolTimingJson,
  boolean webApiDashboard,
  List<String> webApiGroups,
  String webApiJson,
  String webApiMeanJson,
  String webApiMaxJson,
  String webApiCountJson,
  boolean jvmDashboard,
  String jvmMemoryJson,
  String jvmCpuJson,
  String queryRate,
  String webApiRate,
  String queryLoad,
  String webApiLoad
) {

  /** One row of the shared legend/summary table below the chart. */
  public record LegendRow(String color, String group, String totalMs, String executions, String avgMs,
                           String rate, String hashCount, boolean other, String detailUrl) {
  }
}
