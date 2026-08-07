package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

import java.util.List;

/**
 * View model for the per-label drill-down page ({@code /ux/metric-detail}),
 * reached by clicking a label on the {@code query-total} dashboard.
 * <p>
 * Shows two small trend charts (total execution time, mean execution time)
 * for the label across the selected window, a ranked breakdown of the
 * underlying query hashes sharing that label, and any recently captured
 * query plans for those hashes.
 */
@JStache(path = "metric-detail")
public record MetricDetailView(
  Breadcrumb breadcrumb,
  boolean hasData,
  String app,
  String env,
  List<Option> envs,
  String range,
  List<Option> ranges,
  String label,
  String totalChartDataJson,
  String meanChartDataJson,
  String maxChartDataJson,
  List<HashRow> hashBreakdown,
  List<PlanRow> recentPlans,
  boolean hasFamily,
  List<FamilyRow> family
) {

  /** One ranked row of the "breakdown by hash" table for this label. */
  public record HashRow(String hash, String color) {
  }

  /** One row of the "recently captured query plans" table for this label. */
  public record PlanRow(long id, String env, String hash, String whenCaptured, String queryTimeMs,
                        String captureCount, boolean shapeChanged, String color) {
  }

  /**
   * One row of the fully-expanded "query family" tree (the label's
   * dot-hierarchy ancestor/descendant fetch-path queries). {@code indent}
   * is a ready-to-use left-margin (px) for the row's nesting depth and
   * {@code pct} a ready-to-use percentage width for the relative-time bar.
   */
  public record FamilyRow(String label, String display, int indent, String totalMs, String meanMs,
                         String count, String pct, boolean current, String detailUrl) {
  }
}
