package org.ebean.monitor.cli;

import java.util.List;

import org.ebean.monitor.v1.model.AppMetricStats;

/**
 * Shared rendering helpers for ranked-metric charts (Pareto bars).
 *
 * <p>Charts augment the numbers — they never replace them. Cumulative-percent
 * (Pareto) annotation is only meaningful for additive measures (total time,
 * call count); for mean/max we render bars for relative sizing only.
 */
final class Charts {

  private static final int BAR_WIDTH = 28;

  private Charts() {
  }

  /** The plotted value for a row under the chosen ranking measure. */
  static double measure(AppMetricStats r, TopCommand.OrderBy by) {
    return switch (by) {
      case total -> r.totalMicros();
      case mean -> r.meanMicros();
      case max -> r.maxMicros();
      case count -> r.count();
    };
  }

  /** Additive measures support a meaningful running cumulative percentage. */
  static boolean additive(TopCommand.OrderBy by) {
    return by == TopCommand.OrderBy.total || by == TopCommand.OrderBy.count;
  }

  static String unit(TopCommand.OrderBy by) {
    return by == TopCommand.OrderBy.count ? "calls" : "us";
  }

  /**
   * Render a full-width Pareto bar chart of the rows under the chosen measure.
   * Rows are assumed already ordered by {@code by} (descending).
   */
  static void printPareto(List<AppMetricStats> rows, TopCommand.OrderBy by) {
    if (rows.isEmpty()) {
      System.out.println("No metrics found.");
      return;
    }
    double max = 0;
    double total = 0;
    int labelWidth = "LABEL".length();
    for (AppMetricStats r : rows) {
      double v = measure(r, by);
      max = Math.max(max, v);
      total += v;
      String label = r.label() == null ? "" : r.label();
      labelWidth = Math.max(labelWidth, Math.min(40, label.length()));
    }
    boolean cum = additive(by) && total > 0;
    System.out.printf("Top %d by %s (%s)%n%n", rows.size(), by.name(), unit(by));
    double running = 0;
    String fmt = "  %-" + labelWidth + "s  %s  %14s%s%n";
    for (AppMetricStats r : rows) {
      double v = measure(r, by);
      running += v;
      String label = truncate(r.label() == null ? "" : r.label(), labelWidth);
      String bar = AnsiColor.chart(TermChart.bar(v, max, BAR_WIDTH));
      String value = String.format("%,.0f %s", v, unit(by));
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
}
