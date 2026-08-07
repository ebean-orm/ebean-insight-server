package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;

/**
 * View model for the SQL detail page for a metric hash without requiring a
 * captured query plan.
 */
@JStache(path = "query-sql")
public record QuerySqlView(
  Breadcrumb breadcrumb,
  String app,
  String env,
  String label,
  String hash,
  String sql
) {
}
