package org.ebean.monitor.web.view;

import io.jstach.jstache.JStache;
import org.ebean.monitor.v1.model.UserUsageSummary;

import java.util.List;

@JStache(path = "user-usage")
public record UserUsageView(
  Breadcrumb breadcrumb,
  List<UserUsageSummary> users,
  long windowMinutes,
  boolean hasData) {
}
