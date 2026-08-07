package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

import java.util.List;

/**
 * View model for the single captured query-plan drill-down page
 * ({@code /ux/query-plan?id=...}), reached by clicking a row in the
 * "recently captured query plans" table on {@code metric-detail}.
 * <p>
 * Shows the plan's metadata (env, label, hash, captured time, query time),
 * its SQL/bind values, the raw captured plan text rendered via the PEV2
 * visualizer (in an isolated iframe - see {@code static/pev2-frame.html}),
 * and a list of sibling captures for the same (env, hash) so an older or
 * shape-regressed capture is one click away.
 */
@JStache(path = "query-plan")
public record QueryPlanView(
  Breadcrumb breadcrumb,
  long id,
  String app,
  String env,
  String label,
  String hash,
  String whenCaptured,
  String queryTimeMs,
  String captureCount,
  boolean shapeChanged,
  String sql,
  String bind,
  boolean hasBind,
  String planDataJson,
  List<SiblingRow> siblings
) {

  /** One row of the "other captures for this hash" list. */
  public record SiblingRow(long id, String whenCaptured, String queryTimeMs, boolean current, boolean shapeChanged) {
  }
}
