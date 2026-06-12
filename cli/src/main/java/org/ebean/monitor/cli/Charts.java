package org.ebean.monitor.cli;

import java.util.List;

import org.ebean.monitor.v1.model.TopGroup;

/**
 * Shared rendering helpers for ranked-metric charts (Pareto bars).
 *
 * <p>Charts augment the numbers — they never replace them. Cumulative-percent
 * (Pareto) annotation is only meaningful for additive measures (total time,
 * call count); for mean/max/value we render bars for relative sizing only.
 */
final class Charts {

  private static final int BAR_WIDTH = 28;

  private Charts() {
  }

  /** The plotted value for a row under the chosen ranking measure. */
  static double measure(TopGroup r, TopCommand.Sort sort) {
    return switch (sort) {
      case total -> nz(r.totalMicros());
      case mean -> nz(r.meanMicros());
      case max -> nz(r.maxMicros());
      case count -> nz(r.count());
      case value -> r.value() == null ? 0d : r.value();
    };
  }

  /** Additive measures support a meaningful running cumulative percentage. */
  static boolean additive(TopCommand.Sort sort) {
    return sort == TopCommand.Sort.total || sort == TopCommand.Sort.count;
  }

  static String unit(TopCommand.Sort sort) {
    return switch (sort) {
      case count -> "calls";
      case value -> "";
      default -> "us";
    };
  }

  /** A row's display label: the group value, falling back to the label tag. */
  private static String rowLabel(TopGroup r) {
    if (r.group() != null) {
      return r.group();
    }
    return r.label() == null ? "" : r.label();
  }

  /**
   * Render a full-width Pareto bar chart of the rows under the chosen measure.
   * Rows are assumed already ordered by {@code sort} (descending).
   */
  static void printPareto(List<TopGroup> rows, TopCommand.Sort sort) {
    if (rows.isEmpty()) {
      System.out.println("No metrics found.");
      return;
    }
    double max = 0;
    double total = 0;
    int labelWidth = "LABEL".length();
    for (TopGroup r : rows) {
      double v = measure(r, sort);
      max = Math.max(max, v);
      total += v;
      labelWidth = Math.max(labelWidth, Math.min(40, rowLabel(r).length()));
    }
    boolean cum = additive(sort) && total > 0;
    String unit = unit(sort);
    System.out.printf("Top %d by %s%s%n%n", rows.size(), sort.name(), unit.isEmpty() ? "" : " (" + unit + ")");
    double running = 0;
    String fmt = "  %-" + labelWidth + "s  %s  %14s%s%n";
    for (TopGroup r : rows) {
      double v = measure(r, sort);
      running += v;
      String label = truncate(rowLabel(r), labelWidth);
      String bar = AnsiColor.chart(TermChart.bar(v, max, BAR_WIDTH));
      String value = unit.isEmpty() ? String.format("%,.0f", v) : String.format("%,.0f %s", v, unit);
      String cumStr = cum ? String.format("  (cum %3.0f%%)", running / total * 100.0) : "";
      System.out.printf(fmt, label, bar, value, cumStr);
    }
  }

  static String truncate(String s, int width) {
    if (s.length() <= width) {
      return s;
    }
    return s.substring(0, Math.max(1, width - 1)) + "\u2026";
  }

  private static double nz(Long v) {
    return v == null ? 0d : v;
  }
}
