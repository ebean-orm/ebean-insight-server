package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

import java.util.List;

/** View model for the complete detail page for one metric query hash. */
@JStache(path = "query-hash")
public record QueryHashView(
  Breadcrumb breadcrumb,
  String app,
  String env,
  String label,
  String hash,
  String location,
  String createdAt,
  String modifiedAt,
  String sql,
  String totalMs,
  String meanMs,
  String maxMs,
  String count,
  List<PlanRow> plans
) {

  public record PlanRow(long id, String env, String capturedAt, String queryTimeMs,
                        String captureCount, boolean shapeChanged, String url) {
  }
}
