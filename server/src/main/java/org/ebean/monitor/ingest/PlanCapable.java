package org.ebean.monitor.ingest;

import java.util.Map;

/**
 * Derives whether a metric supports query-plan capture (i.e. it is a SELECT-style query).
 * <p>
 * For v2 metrics (tags present) this is driven by the {@code kind} tag:
 * {@code orm} (excluding {@code update.} labels), {@code dto}, or {@code sql}
 * with a {@code query.} label. For legacy v1 metrics it is derived from the
 * flat name prefix ({@code orm.}/{@code dto.}/{@code sql.query.}, excluding
 * {@code orm.update.}).
 */
final class PlanCapable {

  private PlanCapable() {
  }

  static boolean derive(String name, Map<String, String> tags) {
    if (tags != null && !tags.isEmpty()) {
      final String kind = tags.get("kind");
      final String labelValue = tags.get("label");
      final String label = labelValue == null ? "" : labelValue;
      if ("orm".equals(kind)) {
        return !label.startsWith("update.");
      }
      if ("dto".equals(kind)) {
        return true;
      }
      if ("sql".equals(kind)) {
        return label.startsWith("query.");
      }
      return false;
    }
    return name != null
      && (name.startsWith("orm.") || name.startsWith("dto.") || name.startsWith("sql.query."))
      && !name.startsWith("orm.update.");
  }
}
