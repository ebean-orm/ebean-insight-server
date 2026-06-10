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
  private static final int LABEL_MAX = 70;
  private static final int HASH_SHORT = 12;

  /** Which ranked list is being shown — drives the per-mode columns. */
  enum Mode { TOP, MISSING }

  /** A flattened ranked row: enough to render and to drive the row actions. */
  record Row(String app, String hash, String label, double value, String unit,
             long count, long total, long mean, long max,
             boolean planCapable, Long captureCount, String lastCaptured) {

    /** Compact constructor for tests that only need the identity + ranked value. */
    Row(String app, String hash, String label, double value, String unit) {
      this(app, hash, label, value, unit, 0, 0, 0, 0, false, null, null);
    }
  }

  /** A rendered column: header, justification and a per-row cell extractor. */
  private record Col(String header, boolean right, java.util.function.Function<Row, String> cell) {
  }

  /** Ranking measure — switchable live in the interactive loop. */
  enum By {
    total, mean, max, count;

    String unit() {
      return this == count ? "calls" : "us";
    }
  }

  private final Insight insight;
  private final String env;
  private final Mode mode;
  private final boolean showApp;
  private final String titleHead;
  private final String titleTail;
  private final java.util.function.Function<By, List<Row>> reload;
  private final BufferedReader in;

  private List<Row> rows;
  private By by;
  private TrendCommand.Measure measure;

  private Interactive(Insight insight, String env, Mode mode, boolean showApp,
                      String titleHead, String titleTail, By by,
                      List<Row> rows, java.util.function.Function<By, List<Row>> reload) {
    this.insight = insight;
    this.env = env;
    this.mode = mode;
    this.showApp = showApp;
    this.titleHead = titleHead;
    this.titleTail = titleTail;
    this.rows = rows;
    this.reload = reload;
    applyBy(by);
    this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
  }

  /** Constructor for the pure-render tests (no HTTP client, no reload). */
  private Interactive(Mode mode, boolean showApp) {
    this.insight = null;
    this.env = null;
    this.mode = mode;
    this.showApp = showApp;
    this.titleHead = "";
    this.titleTail = "";
    this.rows = List.of();
    this.reload = null;
    this.by = By.total;
    this.measure = TrendCommand.Measure.mean;
    this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
  }

  private void applyBy(By by) {
    this.by = by;
    this.measure = TrendCommand.Measure.of(by.name());
  }

  static int topLoop(Insight insight, List<AppMetricStats> initial, TopCommand.OrderBy by, String env,
                     java.util.function.Function<String, List<AppMetricStats>> fetch) {
    String only = singleApp(initial.stream().map(AppMetricStats::app).toList());
    long window = initial.isEmpty() ? 0 : initial.get(0).windowMinutes();
    String titleTail = (only != null ? " — " + only : "")
        + (window > 0 ? " — window " + window + "m" : "");
    java.util.function.Function<By, List<Row>> reload = b -> toTopRows(fetch.apply(b.name()), b);
    List<Row> rows = toTopRows(initial, By.valueOf(by.name()));
    return new Interactive(insight, env, Mode.TOP, only == null, "Top", titleTail,
        By.valueOf(by.name()), rows, reload).run();
  }

  static int missingPlansLoop(Insight insight, List<MissingPlanMetric> initial, MissingPlansCommand.OrderBy by,
                              String env, java.util.function.Function<String, List<MissingPlanMetric>> fetch) {
    String only = singleApp(initial.stream().map(MissingPlanMetric::app).toList());
    String titleTail = only != null ? " — " + only : "";
    java.util.function.Function<By, List<Row>> reload = b -> toMissingRows(fetch.apply(b.name()), b);
    List<Row> rows = toMissingRows(initial, By.valueOf(by.name()));
    return new Interactive(insight, env, Mode.MISSING, only == null, "Missing plans", titleTail,
        By.valueOf(by.name()), rows, reload).run();
  }

  private static List<Row> toTopRows(List<AppMetricStats> src, By by) {
    String unit = by.unit();
    List<Row> out = new ArrayList<>(src.size());
    for (AppMetricStats r : src) {
      out.add(new Row(r.app(), r.key(), r.label(), measure(r, by), unit,
          r.count(), r.totalMicros(), r.meanMicros(), r.maxMicros(),
          Boolean.TRUE.equals(r.planCapable()), null, null));
    }
    return out;
  }

  private static List<Row> toMissingRows(List<MissingPlanMetric> src, By by) {
    String unit = by.unit();
    List<Row> out = new ArrayList<>(src.size());
    for (MissingPlanMetric m : src) {
      out.add(new Row(m.app(), m.key(), m.label(), measure(m, by), unit,
          m.count(), m.totalMicros(), m.meanMicros(), m.maxMicros(),
          false, m.captureCount(), m.lastCapturedAt() == null ? "never" : m.lastCapturedAt().toString()));
    }
    return out;
  }

  private static double measure(AppMetricStats r, By by) {
    return switch (by) {
      case total -> r.totalMicros();
      case mean -> r.meanMicros();
      case max -> r.maxMicros();
      case count -> r.count();
    };
  }

  private static double measure(MissingPlanMetric m, By by) {
    return switch (by) {
      case total -> m.totalMicros();
      case mean -> m.meanMicros();
      case max -> m.maxMicros();
      case count -> m.count();
    };
  }

  /** Test-only factory for exercising the pure list renderer without an HTTP client. */
  static Interactive forRender(Mode mode, boolean showApp) {
    return new Interactive(mode, showApp);
  }

  /** The single app shared by every row, or null when rows span more than one app. */
  private static String singleApp(List<String> apps) {
    String found = null;
    for (String a : apps) {
      if (a == null) {
        continue;
      }
      if (found == null) {
        found = a;
      } else if (!found.equals(a)) {
        return null;
      }
    }
    return found;
  }

  private int run() {
    if (rows.isEmpty()) {
      System.out.println("Nothing to show.");
      return 0;
    }
    while (true) {
      printList(title(), rows);
      System.out.print(prompt());
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
      By chosen = parseBy(line);
      if (chosen != null) {
        switchMeasure(chosen);
        continue;
      }
      Integer idx = parseIndex(line, rows.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + rows.size()
            + ", a measure (t/m/x/n), or 'q' to quit.");
        continue;
      }
      if (!rowMenu(rows.get(idx - 1))) {
        return 0;
      }
    }
  }

  private String title() {
    return titleHead + " " + rows.size() + " by " + by.name() + titleTail;
  }

  private String prompt() {
    return "Select " + AnsiColor.hot("1-" + rows.size(), "") + "   by " + measureKeys()
        + "   " + AnsiColor.hot("q", "uit") + " > ";
  }

  /** Measure hotkeys with the active measure noted. */
  private String measureKeys() {
    return AnsiColor.hot("t", "otal") + " " + AnsiColor.hot("m", "ean")
        + " ma" + AnsiColor.hot("x", "") + " cou" + AnsiColor.hot("n", "t")
        + " (now: " + by.name() + ")";
  }

  private static By parseBy(String line) {
    return switch (line.toLowerCase(Locale.ROOT)) {
      case "t", "total" -> By.total;
      case "m", "mean" -> By.mean;
      case "x", "max" -> By.max;
      case "n", "count" -> By.count;
      default -> null;
    };
  }

  /** Re-query the server ranked by the chosen measure (the top-N set differs per measure). */
  private void switchMeasure(By target) {
    if (target == by || reload == null) {
      return;
    }
    try {
      List<Row> fresh = reload.apply(target);
      if (fresh.isEmpty()) {
        System.out.println("No rows ranked by " + target.name() + ".");
        return;
      }
      this.rows = fresh;
      applyBy(target);
    } catch (RuntimeException e) {
      System.out.println("Reload failed: " + e.getMessage());
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
      var ts = insight.metrics.getMetricTimeseries(row.app(), row.hash(),
          TrendCommand.DEFAULT_TREND_WINDOW_MINUTES, null, env);
      if (!ts.buckets().isEmpty()) {
        TrendCommand.printTrend(ts, measure);
        return;
      }
      System.out.println("Trend — " + row.label() + " [" + row.app() + "]");
      System.out.println("  No time-series data for this metric in the last " + ts.windowMinutes() + "m"
          + (env == null ? "" : " (env " + env + ")") + ".");
    } catch (HttpException e) {
      if (e.statusCode() == 404) {
        System.out.println("This server build does not serve the per-hash time-series endpoint yet.");
      } else {
        System.out.println("Failed to load trend: HTTP " + e.statusCode());
      }
    }
  }

  private void printList(String title, List<Row> rows) {
    System.out.print(renderList(title, rows));
  }

  String renderList(String title, List<Row> rows) {
    List<Col> pre = preColumns();
    List<Col> post = postColumns();
    double max = 0;
    for (Row r : rows) {
      max = Math.max(max, r.value());
    }
    int idxWidth = Math.max(1, Integer.toString(rows.size()).length());
    int[] preW = widths(pre, rows);
    int[] postW = widths(post, rows);

    StringBuilder sb = new StringBuilder();
    sb.append('\n').append(title).append('\n').append('\n');
    sb.append("  ").append(pad("#", idxWidth, true));
    appendHeaders(sb, pre, preW);
    sb.append("  ").append(pad("chart", BAR_WIDTH, false));
    appendHeaders(sb, post, postW);
    sb.append('\n');
    int i = 1;
    for (Row r : rows) {
      sb.append("  ").append(pad(Integer.toString(i++), idxWidth, true));
      appendCells(sb, pre, preW, r);
      sb.append("  ").append(AnsiColor.chart(TermChart.bar(r.value(), max, BAR_WIDTH)));
      appendCells(sb, post, postW, r);
      sb.append('\n');
    }
    sb.append('\n');
    return sb.toString();
  }

  /** Identity + the fixed numeric block (rendered before the bar). */
  private List<Col> preColumns() {
    List<Col> cols = new ArrayList<>();
    if (showApp) {
      cols.add(new Col("APP", false, r -> r.app() == null ? "" : r.app()));
    }
    cols.add(new Col("LABEL", false, r -> tail(r.label(), LABEL_MAX)));
    cols.add(new Col("HASH", false, r -> shortHash(r.hash())));
    cols.add(new Col("COUNT", true, r -> num(r.count())));
    cols.add(new Col("TOTAL(us)", true, r -> num(r.total())));
    cols.add(new Col("MEAN(us)", true, r -> num(r.mean())));
    cols.add(new Col("MAX(us)", true, r -> num(r.max())));
    return cols;
  }

  /** Mode-specific columns rendered after the bar. */
  private List<Col> postColumns() {
    List<Col> cols = new ArrayList<>();
    if (mode == Mode.TOP) {
      cols.add(new Col("PLAN", false, r -> r.planCapable() ? "yes" : "no"));
    } else {
      cols.add(new Col("CAPTURES", true, r -> num(r.captureCount() == null ? 0 : r.captureCount())));
      cols.add(new Col("CAPTURED", false, r -> r.lastCaptured() == null ? "never" : r.lastCaptured()));
    }
    return cols;
  }

  private static int[] widths(List<Col> cols, List<Row> rows) {
    int[] w = new int[cols.size()];
    for (int c = 0; c < cols.size(); c++) {
      w[c] = cols.get(c).header().length();
    }
    for (Row r : rows) {
      for (int c = 0; c < cols.size(); c++) {
        w[c] = Math.max(w[c], cols.get(c).cell().apply(r).length());
      }
    }
    return w;
  }

  private static void appendHeaders(StringBuilder sb, List<Col> cols, int[] w) {
    for (int c = 0; c < cols.size(); c++) {
      sb.append("  ").append(pad(cols.get(c).header(), w[c], cols.get(c).right()));
    }
  }

  private static void appendCells(StringBuilder sb, List<Col> cols, int[] w, Row r) {
    for (int c = 0; c < cols.size(); c++) {
      sb.append("  ").append(pad(cols.get(c).cell().apply(r), w[c], cols.get(c).right()));
    }
  }

  private static String num(long v) {
    return String.format(Locale.ROOT, "%,d", v);
  }

  private static String shortHash(String h) {
    if (h == null) {
      return "";
    }
    return h.length() <= HASH_SHORT ? h : h.substring(0, HASH_SHORT);
  }

  /** Truncate keeping the distinguishing tail — ORM query labels share a long prefix and differ in their suffix. */
  private static String tail(String s, int width) {
    if (s == null) {
      return "";
    }
    if (s.length() <= width) {
      return s;
    }
    return "…" + s.substring(s.length() - (width - 1));
  }

  private static String pad(String s, int width, boolean right) {
    if (s.length() >= width) {
      return s;
    }
    String spaces = " ".repeat(width - s.length());
    return right ? spaces + s : s + spaces;
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
