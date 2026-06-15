package org.ebean.monitor.cli;

import org.ebean.monitor.v1.model.TopGroup;

/**
 * Shared rendering of a metric's display identity.
 *
 * <p>In the v2 metric model the family {@code name} is the primary identity
 * (e.g. {@code ebean.txn}, {@code ebean.query}) and {@code label} is the
 * secondary discriminator (e.g. {@code readonly}, {@code DMessage.findMessages}).
 * Tables render these as separate {@code NAME} and {@code LABEL} columns; where a
 * single column is required (a chart bar) they are joined as {@code name:label}.
 */
final class Display {

  private Display() {
  }

  /** Single-line identity for charts: {@code name:label}, with sensible fallbacks. */
  static String chartLabel(TopGroup r) {
    return join(r.name(), r.label(), r.group());
  }

  /** Join a metric's {@code name} and {@code label} into a single {@code name:label} line. */
  static String join(String name, String label, String fallback) {
    if (name == null || name.isBlank()) {
      if (label != null && !label.isBlank()) {
        return label;
      }
      return fallback == null ? "" : fallback;
    }
    if (label == null || label.isBlank() || label.equals(name)) {
      return name;
    }
    return name + ":" + label;
  }
}
