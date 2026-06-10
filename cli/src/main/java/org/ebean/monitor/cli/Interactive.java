package org.ebean.monitor.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.avaje.http.client.HttpException;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

/**
 * "Light interactive" guided drill-down for ranked-metric lists.
 *
 * <p>A command prints a numbered, bar-annotated list, then waits for the user
 * to pick a row and an action (sql / plan / capture / trend) — a multi-step
 * investigation without a full-screen TUI. Input is read line-by-line from
 * stdin so sessions can be driven by a pipe and captured deterministically.
 *
 * <p>Only engaged on an interactive request; callers must fall back to plain
 * output for {@code -o json} or a non-tty.
 */
final class Interactive {

  private static final int BAR_WIDTH = 24;

  /** A flattened ranked row: enough to render and to drive the row actions. */
  record Row(String app, String hash, String label, double value, String unit) {
  }

  private final Insight insight;
  private final String env;
  private final boolean additive;
  private final String valueTitle;
  private final TrendCommand.Measure measure;
  private final BufferedReader in;

  private Interactive(Insight insight, String env, boolean additive, String valueTitle, TrendCommand.Measure measure) {
    this.insight = insight;
    this.env = env;
    this.additive = additive;
    this.valueTitle = valueTitle;
    this.measure = measure;
    this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
  }

  static int topLoop(Insight insight, List<AppMetricStats> rows, TopCommand.OrderBy by, String env) {
    List<Row> mapped = new ArrayList<>(rows.size());
    String unit = Charts.unit(by);
    for (AppMetricStats r : rows) {
      mapped.add(new Row(r.app(), r.key(), r.label(), Charts.measure(r, by), unit));
    }
    return new Interactive(insight, env, Charts.additive(by), columnTitle(by.name(), unit), TrendCommand.Measure.of(by.name()))
        .run("Top " + rows.size() + " by " + by.name(), mapped);
  }

  static int missingPlansLoop(Insight insight, List<MissingPlanMetric> rows, MissingPlansCommand.OrderBy by, String env) {
    List<Row> mapped = new ArrayList<>(rows.size());
    String unit = by == MissingPlansCommand.OrderBy.count ? "calls" : "us";
    boolean additive = by == MissingPlansCommand.OrderBy.total || by == MissingPlansCommand.OrderBy.count;
    for (MissingPlanMetric m : rows) {
      mapped.add(new Row(m.app(), m.key(), m.label(), missingMeasure(m, by), unit));
    }
    return new Interactive(insight, env, additive, columnTitle(by.name(), unit), TrendCommand.Measure.of(by.name()))
        .run("Missing plans " + rows.size() + " by " + by.name(), mapped);
  }

  /** Column heading for the value column, e.g. {@code TOTAL(us)}, {@code MEAN(us)}, {@code COUNT}. */
  static String columnTitle(String byName, String unit) {
    String name = byName.toUpperCase(Locale.ROOT);
    return "us".equals(unit) ? name + "(us)" : name;
  }

  /** Test-only factory for exercising the pure renderers without an HTTP client. */
  static Interactive forRender(boolean additive, String valueTitle) {
    return new Interactive(null, null, additive, valueTitle, TrendCommand.Measure.mean);
  }

  private static double missingMeasure(MissingPlanMetric m, MissingPlansCommand.OrderBy by) {
    return switch (by) {
      case total -> m.totalMicros();
      case mean -> m.meanMicros();
      case max -> m.maxMicros();
      case count -> m.count();
    };
  }

  private int run(String title, List<Row> rows) {
    if (rows.isEmpty()) {
      System.out.println("Nothing to show.");
      return 0;
    }
    while (true) {
      printList(title, rows);
      System.out.print("Select " + AnsiColor.hot("1-" + rows.size(), "") + "  "
          + AnsiColor.hot("c", "hart") + "  " + AnsiColor.hot("q", "uit") + " > ");
      System.out.flush();
      String line = readLine();
      if (line == null) {
        System.out.println();
        return 0;
      }
      line = line.trim();
      if (line.isEmpty() || line.equalsIgnoreCase("q")) {
        return 0;
      }
      if (line.equalsIgnoreCase("c")) {
        printChart(title, rows);
        continue;
      }
      Integer idx = parseIndex(line, rows.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + rows.size() + ", 'c' for a chart, or 'q' to quit.");
        continue;
      }
      if (!rowMenu(rows.get(idx - 1))) {
        return 0;
      }
    }
  }

  /** Returns false when the user asked to quit the whole session. */
  private boolean rowMenu(Row row) {
    while (true) {
      System.out.println();
      System.out.println(row.label() + "  [" + row.app() + "]  " + row.hash());
      System.out.print("  " + AnsiColor.hot("s", "ql") + "  " + AnsiColor.hot("p", "lan")
          + "  " + AnsiColor.hot("c", "apture") + "  " + AnsiColor.hot("t", "rend")
          + "  " + AnsiColor.hot("b", "ack") + "  " + AnsiColor.hot("q", "uit") + " > ");
      System.out.flush();
      String line = readLine();
      if (line == null) {
        System.out.println();
        return false;
      }
      switch (line.trim().toLowerCase()) {
        case "", "b" -> {
          return true;
        }
        case "q" -> {
          return false;
        }
        case "s" -> showSql(row);
        case "p" -> showPlan(row);
        case "c" -> capture(row);
        case "t" -> showTrend(row);
        default -> System.out.println("Unknown action. Use s/p/c/t/b/q.");
      }
    }
  }

  private void showSql(Row row) {
    try {
      List<AppMetric> metrics = insight.metrics.getMetricByHash(row.app(), row.hash());
      if (metrics.isEmpty()) {
        System.out.println("No metric found for " + row.hash() + ".");
        return;
      }
      System.out.println();
      MetricCommand.printMetric(metrics.get(0));
    } catch (HttpException e) {
      System.out.println("Failed to load metric: HTTP " + e.statusCode());
    }
  }

  private void showPlan(Row row) {
    try {
      List<QueryPlanSummary> plans = insight.plans.listPlansByHash(row.app(), row.hash(), null, 1);
      if (plans.isEmpty()) {
        System.out.println("No captured plan for this hash yet. Use " + AnsiColor.hot("c", "apture") + " to request one.");
        return;
      }
      QueryPlan p = insight.plans.getPlan(plans.get(0).id());
      System.out.println();
      System.out.println("plan id:   " + p.id() + "   env: " + p.envName()
          + "   queryTime: " + p.queryTimeMicros() + "us   captured: " + p.whenCaptured());
      System.out.println();
      System.out.println("sql:");
      System.out.println(p.sql());
      System.out.println();
      System.out.println("bind: " + p.bind());
      System.out.println();
      System.out.println("plan:");
      System.out.println(p.plan());
    } catch (HttpException e) {
      System.out.println("Failed to load plan: HTTP " + e.statusCode());
    }
  }

  private void capture(Row row) {
    try {
      var pending = insight.plans.requestPlanCapture(row.app(), row.hash(), env);
      System.out.println("Capture requested (env " + (env == null ? "*" : env)
          + ", queue depth " + pending.pending() + "). Check 'insight pending' / 'insight plans' shortly.");
    } catch (HttpException e) {
      System.out.println("Capture failed: HTTP " + e.statusCode());
    }
  }

  private void showTrend(Row row) {
    System.out.println();
    try {
      var ts = insight.metrics.getMetricTimeseries(row.app(), row.hash(), null, null, env);
      if (!ts.buckets().isEmpty()) {
        TrendCommand.printTrend(ts, measure);
        return;
      }
    } catch (HttpException e) {
      // older server without the timeseries route -> fall through to preview
    }
    // No live time-series available: show a clearly-labelled rendering preview so
    // the trend visual can still be evaluated. The headline numbers below ARE real
    // (current-window aggregate); only the bucket shape is illustrative.
    AppMetricStats current = null;
    try {
      List<AppMetricStats> stats = insight.metrics.getMetricStatsByHash(row.app(), row.hash(), null, null, env);
      if (!stats.isEmpty()) {
        current = stats.get(0);
      }
    } catch (HttpException e) {
      // fall through to preview-only
    }
    System.out.println("Trend — " + row.label() + " [" + row.app() + "]  (preview)");
    if (current != null) {
      System.out.printf("  now (window %dm): count %,d  mean %,d us  max %,d us%n",
          current.windowMinutes(), current.count(), current.meanMicros(), current.maxMicros());
    }
    long[] meanSample = sampleSeries(row.hash(), 0);
    long[] callsSample = sampleSeries(row.hash(), 1);
    if (measure == TrendCommand.Measure.count) {
      TrendCommand.printColumns("calls", "illustrative shape, " + callsSample.length + " buckets", callsSample, 8);
    } else {
      TrendCommand.printColumns(measure.label(), "illustrative shape, " + meanSample.length + " buckets", meanSample, 8);
      TrendCommand.printColumns("calls", "illustrative shape", callsSample, 3);
    }
    System.out.println();
    System.out.println("  NOTE: trend shape is a rendering preview — this server build does not");
    System.out.println("        yet serve the per-hash time-series endpoint (ix-trend-endpoint).");
  }

  /** Deterministic illustrative series derived from the hash (preview only). */
  private static long[] sampleSeries(String hash, int salt) {
    long seed = (hash == null ? 1L : hash.hashCode()) * 31L + salt;
    long[] out = new long[TrendCommand.TARGET_WIDTH];
    long state = seed == 0 ? 1L : seed;
    double phase = salt * 1.7;
    for (int i = 0; i < out.length; i++) {
      state = state * 6364136223846793005L + 1442695040888963407L;
      int wave = (int) (40 + 30 * Math.sin(i / 6.0 + phase));
      int jitter = (int) ((state >>> 40) % 25);
      out[i] = Math.max(1, wave + jitter);
    }
    return out;
  }

  private void printList(String title, List<Row> rows) {
    System.out.print(renderList(title, rows));
  }

  String renderList(String title, List<Row> rows) {
    double max = 0;
    int labelWidth = "LABEL".length();
    for (Row r : rows) {
      max = Math.max(max, r.value());
      labelWidth = Math.max(labelWidth, Math.min(40, r.label() == null ? 0 : r.label().length()));
    }
    StringBuilder sb = new StringBuilder();
    sb.append('\n').append(title).append('\n').append('\n');
    String fmt = "  %2s  %-" + labelWidth + "s  %s  %14s%n";
    sb.append(String.format(fmt, "#", "LABEL", " ".repeat(BAR_WIDTH), valueTitle));
    int i = 1;
    for (Row r : rows) {
      String label = Charts.truncate(r.label() == null ? "" : r.label(), labelWidth);
      String bar = AnsiColor.chart(TermChart.bar(r.value(), max, BAR_WIDTH));
      sb.append(String.format(fmt, Integer.toString(i++), label, bar,
          String.format("%,.0f %s", r.value(), r.unit())));
    }
    sb.append('\n');
    return sb.toString();
  }

  private void printChart(String title, List<Row> rows) {
    System.out.print(renderChart(title, rows));
  }

  String renderChart(String title, List<Row> rows) {
    double max = 0;
    double total = 0;
    int labelWidth = "LABEL".length();
    for (Row r : rows) {
      max = Math.max(max, r.value());
      total += r.value();
      labelWidth = Math.max(labelWidth, Math.min(40, r.label() == null ? 0 : r.label().length()));
    }
    boolean cum = additive && total > 0;
    StringBuilder sb = new StringBuilder();
    sb.append('\n').append(title).append(" — chart").append('\n').append('\n');
    String fmt = "  %2s  %-" + labelWidth + "s  %s  %14s%s%n";
    sb.append(String.format(fmt, "#", "LABEL", " ".repeat(32), valueTitle, cum ? "  CUM%" : ""));
    double running = 0;
    int i = 1;
    for (Row r : rows) {
      running += r.value();
      String label = Charts.truncate(r.label() == null ? "" : r.label(), labelWidth);
      String bar = AnsiColor.chart(TermChart.bar(r.value(), max, 32));
      String value = String.format("%,.0f %s", r.value(), r.unit());
      String cumStr = cum ? String.format("  (cum %3.0f%%)", running / total * 100.0) : "";
      sb.append(String.format(fmt, Integer.toString(i++), label, bar, value, cumStr));
    }
    sb.append('\n');
    return sb.toString();
  }

  private static Integer parseIndex(String line, int size) {
    try {
      int n = Integer.parseInt(line);
      return (n >= 1 && n <= size) ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String readLine() {
    try {
      return in.readLine();
    } catch (IOException e) {
      return null;
    }
  }
}
