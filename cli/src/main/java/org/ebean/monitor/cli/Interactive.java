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
import org.ebean.monitor.v1.model.MetricTimeBucket;
import org.ebean.monitor.v1.model.MetricTimeseries;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PlanChange;
import org.ebean.monitor.v1.model.PlanChangeDetail;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.ebean.monitor.v1.model.TopGroup;
import org.jspecify.annotations.Nullable;

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
  private static final int NAME_MAX = 40;
  private static final int LABEL_MAX = 70;
  private static final int HASH_SHORT = 12;

  /** Which ranked list is being shown — drives the per-mode columns. */
  enum Mode { TOP, MISSING }

  /**
   * A flattened ranked row: enough to render and to drive the row actions.
   *
   * <p>{@code name} is the v2 metric family (display only). {@code label} carries
   * the aggregation dimension value and doubles as the drill-down filter key, so it
   * must stay the raw value (not a name-decorated display string).
   */
  record Row(String app, String hash, String name, String label, double value, String unit,
             long count, long total, long mean, long max,
             boolean planCapable, Long captureCount, String lastCaptured) {

    /** Compact constructor for tests that only need the identity + ranked value. */
    Row(String app, String hash, String label, double value, String unit) {
      this(app, hash, null, label, value, unit, 0, 0, 0, 0, false, null, null);
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
  /** The aggregation dimension of the ranked list (hash/name/label/tag), for drill-down. */
  private final String dimension;
  /** The single app the list is scoped to, or null when it spans apps. */
  private final String appScope;
  /**
   * Fetches windowed per-hash timing scoped to a parent group, or null when not
   * available (e.g. the pure-render tests or the single-hash entry points).
   * Exactly one of {@code name/label/kind/type} is supplied by the caller.
   */
  @Nullable private final HashDrill drill;

  /** Windowed {@code by=hash} fetch scoped to a parent group's tag/name filter. */
  @FunctionalInterface
  interface HashDrill {
    List<TopGroup> fetch(@Nullable String app, @Nullable String name, @Nullable String label,
                         @Nullable String kind, @Nullable String type, String orderBy);
  }

  private List<Row> rows;
  private By by;
  private TrendCommand.Measure measure;

  private Interactive(Insight insight, String env, Mode mode, boolean showApp,
                      String titleHead, String titleTail, By by,
                      List<Row> rows, java.util.function.Function<By, List<Row>> reload,
                      String dimension, String appScope, @Nullable HashDrill drill) {
    this.insight = insight;
    this.env = env;
    this.mode = mode;
    this.showApp = showApp;
    this.titleHead = titleHead;
    this.titleTail = titleTail;
    this.rows = rows;
    this.reload = reload;
    this.dimension = dimension;
    this.appScope = appScope;
    this.drill = drill;
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
    this.dimension = "hash";
    this.appScope = null;
    this.drill = null;
    this.by = By.total;
    this.measure = TrendCommand.Measure.mean;
    this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
  }

  private void applyBy(By by) {
    this.by = by;
    this.measure = TrendCommand.Measure.of(by.name());
  }

  static int topLoop(Insight insight, String appScope, String dimension, List<TopGroup> initial,
                     TopCommand.Sort sort, String env,
                     java.util.function.Function<String, List<TopGroup>> fetch,
                     @Nullable HashDrill drill) {
    String only = appScope != null ? appScope : singleApp(initial.stream().map(TopGroup::app).toList());
    long window = initial.isEmpty() ? 0 : initial.get(0).windowMinutes();
    String titleTail = (only != null ? " — " + only : "")
        + (window > 0 ? " — window " + window + "m" : "")
        + " — by " + dimension;
    By startBy = sortToBy(sort);
    java.util.function.Function<By, List<Row>> reload = b -> toTopRows(fetch.apply(b.name()), b);
    List<Row> rows = toTopRows(initial, startBy);
    return new Interactive(insight, env, Mode.TOP, only == null, "Top", titleTail,
        startBy, rows, reload, dimension, appScope, drill).run();
  }

  /** Map the command-line sort to the interactive measure toggle (which is timer-only). */
  private static By sortToBy(TopCommand.Sort sort) {
    return switch (sort) {
      case mean -> By.mean;
      case max -> By.max;
      case count -> By.count;
      default -> By.total;
    };
  }

  static int missingPlansLoop(Insight insight, List<MissingPlanMetric> initial, MissingPlansCommand.OrderBy by,
                              String env, java.util.function.Function<String, List<MissingPlanMetric>> fetch) {
    String only = singleApp(initial.stream().map(MissingPlanMetric::app).toList());
    String titleTail = only != null ? " — " + only : "";
    java.util.function.Function<By, List<Row>> reload = b -> toMissingRows(fetch.apply(b.name()), b);
    List<Row> rows = toMissingRows(initial, By.valueOf(by.name()));
    return new Interactive(insight, env, Mode.MISSING, only == null, "Missing plans", titleTail,
        By.valueOf(by.name()), rows, reload, "hash", only, null).run();
  }

  /**
   * Hash-entry drill-down: open the row menu directly for a single
   * {@code app}/{@code hash} (e.g. a hash lifted from a trace), without first
   * ranking a list. Backs {@code insight metric <app> <hash> -i}.
   */
  static int inspectLoop(Insight insight, String app, String hash, String env) {
    List<AppMetric> metrics;
    try {
      metrics = insight.metrics.getMetricByHash(app, hash);
    } catch (HttpException e) {
      System.out.println("Failed to load metric: HTTP " + e.statusCode());
      return 1;
    }
    if (metrics.isEmpty()) {
      System.out.println("No metric found for hash " + hash + " in app " + app + ".");
      return 1;
    }
    String label = metrics.get(0).name();
    Row row = new Row(app, hash, label == null ? hash : label, 0, "us");
    Interactive it = new Interactive(insight, env, Mode.TOP, false, "", "", By.total,
        List.of(row), b -> List.of(row), "hash", app, null);
    it.rowMenu(row);
    return 0;
  }

  /**
   * Plan-change drill-down: browse a list of plan-change events, open one to see
   * its from/to EXPLAIN diff, then optionally drill into that query's row menu
   * (sql/plan/capture/trend/history). Backs {@code insight changes -i}.
   */
  static int changesLoop(Insight insight, List<PlanChange> changes, String env) {
    return new Interactive(insight, env, Mode.TOP, true, "", "", By.total,
        List.of(), b -> List.of(), "hash", null, null).runChanges(changes);
  }

  private static List<Row> toTopRows(List<TopGroup> src, By by) {
    String unit = by.unit();
    List<Row> out = new ArrayList<>(src.size());
    for (TopGroup r : src) {
      String label = r.label() != null ? r.label() : r.group();
      out.add(new Row(r.app(), r.key(), r.name(), label, measure(r, by), unit,
          nz(r.count()), nz(r.totalMicros()), nz(r.meanMicros()), nz(r.maxMicros()),
          Boolean.TRUE.equals(r.planCapable()), null, null));
    }
    return out;
  }

  private static List<Row> toMissingRows(List<MissingPlanMetric> src, By by) {
    String unit = by.unit();
    List<Row> out = new ArrayList<>(src.size());
    for (MissingPlanMetric m : src) {
      out.add(new Row(m.app(), m.key(), null, m.label(), measure(m, by), unit,
          m.count(), m.totalMicros(), m.meanMicros(), m.maxMicros(),
          false, m.captureCount(), m.lastCapturedAt() == null ? "never" : m.lastCapturedAt().toString()));
    }
    return out;
  }

  private static double measure(TopGroup r, By by) {
    return switch (by) {
      case total -> nz(r.totalMicros());
      case mean -> nz(r.meanMicros());
      case max -> nz(r.maxMicros());
      case count -> nz(r.count());
    };
  }

  private static long nz(@org.jspecify.annotations.Nullable Long v) {
    return v == null ? 0L : v;
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
      if (isCaptureCommand(line)) {
        captureCommand(line.trim().substring(1));
        continue;
      }
      Integer idx = parseIndex(line, rows.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + rows.size()
            + ", capture rows (e.g. 'c 1 3 5'), a measure (t/m/x/n), or 'q' to quit.");
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
    return "Select " + AnsiColor.hot("1-" + rows.size(), "")
        + "   " + AnsiColor.hot("c", "apture") + " N…"
        + "   by " + measureKeys()
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

  /** True for a top-level multi-capture command: 'c', 'c 1 3 5' or 'c 1,3,5'. */
  private static boolean isCaptureCommand(String line) {
    String l = line.trim().toLowerCase(Locale.ROOT);
    return l.equals("c") || l.startsWith("c ") || l.startsWith("c,");
  }

  /**
   * Parse the row indices from the argument part of a capture command (the text
   * after the leading 'c'). Accepts space- or comma-separated 1-based row
   * numbers. Returns the distinct in-range indices in input order, an empty list
   * when no numbers were given, or null when any token is not a valid row.
   */
  static List<Integer> parseCaptureIndices(String args, int size) {
    List<Integer> out = new ArrayList<>();
    String trimmed = args.trim();
    if (trimmed.isEmpty()) {
      return out;
    }
    for (String tok : trimmed.split("[\\s,]+")) {
      if (tok.isEmpty()) {
        continue;
      }
      Integer n = parseIndex(tok, size);
      if (n == null) {
        return null;
      }
      if (!out.contains(n)) {
        out.add(n);
      }
    }
    return out;
  }

  private void captureCommand(String args) {
    List<Integer> indices = parseCaptureIndices(args, rows.size());
    if (indices == null) {
      System.out.println("Invalid capture list. Use row numbers, e.g. 'c 1 3 5'.");
      return;
    }
    if (indices.isEmpty()) {
      System.out.println("Specify row numbers to capture, e.g. 'c 1 3 5'.");
      return;
    }
    captureRows(indices);
  }

  /** Request a plan capture for each of the selected rows and print a per-row summary. */
  private void captureRows(List<Integer> indices) {
    System.out.println();
    int requested = 0;
    int skipped = 0;
    int failed = 0;
    int idxWidth = Math.max(1, Integer.toString(rows.size()).length());
    for (int i : indices) {
      Row row = rows.get(i - 1);
      String status;
      if (mode == Mode.TOP && !row.planCapable()) {
        status = "skipped (not plan-capable)";
        skipped++;
      } else {
        try {
          insight.plans.requestPlanCapture(row.app(), row.hash(), env);
          status = "requested";
          requested++;
        } catch (HttpException e) {
          status = "failed HTTP " + e.statusCode();
          failed++;
        }
      }
      System.out.println("  " + pad(Integer.toString(i), idxWidth, true)
          + "  " + pad(status, 26, false) + "  " + row.label());
    }
    System.out.println();
    System.out.println(requested + " requested, " + skipped + " skipped, " + failed + " failed"
        + "  (env " + (env == null ? "*" : env) + "). Check 'insight pending' / 'insight plans' shortly.");
  }

  /** Returns false when the user asked to quit the whole session. */
  private boolean rowMenu(Row row) {
    if (row.hash() == null || row.hash().isBlank()) {
      return drillGroup(row);
    }
    while (true) {
      System.out.println();
      System.out.println(row.label() + "  [" + row.app() + "]  " + row.hash());
      System.out.print("  " + AnsiColor.hot("s", "ql") + "  " + AnsiColor.hot("p", "lan")
          + "  " + AnsiColor.hot("c", "apture") + "  " + AnsiColor.hot("t", "rend")
          + "  " + AnsiColor.hot("h", "istory")
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
        case "t" -> {
          if (!showTrend(row)) {
            return false;
          }
        }
        case "h" -> showChanges(row);
        default -> System.out.println("Unknown action. Use s/p/c/t/h/b/q.");
      }
    }
  }

  /**
   * Drill from an aggregated row (no single hash) into the individual queries
   * that make up the group, then offer the per-query row menu. Maps the
   * aggregation dimension to the matching filter and fetches windowed per-hash
   * timing via {@code top by=hash}; falls back to the metric catalog (no
   * windowed timing) when no executions land in the window.
   */
  private boolean drillGroup(Row group) {
    final String app = appScope != null ? appScope : group.app();
    if (app == null) {
      System.out.println("Cross-app aggregate — re-run with --app to drill into individual queries.");
      return true;
    }
    final String dim = dimension == null ? "label" : dimension.toLowerCase(Locale.ROOT);
    String name = null;
    String label = null;
    String kind = null;
    String type = null;
    switch (dim) {
      case "label" -> label = group.label();
      case "name" -> name = group.label();
      case "kind" -> kind = group.label();
      case "type" -> type = group.label();
      default -> {
        System.out.println("Drill not available for tag '" + dimension
            + "'. Re-run with --by hash to list individual queries.");
        return true;
      }
    }
    if (drill != null) {
      final List<TopGroup> hashRows;
      try {
        hashRows = drill.fetch(app, name, label, kind, type, by.name());
      } catch (HttpException e) {
        System.out.println("Failed to load queries: HTTP " + e.statusCode());
        return true;
      }
      if (!hashRows.isEmpty()) {
        return drillLoop(Display.join(group.name(), group.label(), group.label()), toTopRows(hashRows, by));
      }
    }
    // No windowed activity (or no drill fetcher): list the catalog so the
    // queries remain browsable for sql/plan/capture, with zeroed timing.
    final List<AppMetric> metrics;
    try {
      metrics = insight.metrics.listAppMetrics(app, name, label, kind, type, null, 100);
    } catch (HttpException e) {
      System.out.println("Failed to load queries: HTTP " + e.statusCode());
      return true;
    }
    if (metrics.isEmpty()) {
      System.out.println("No individual queries found for this group.");
      return true;
    }
    final List<Row> rows = new ArrayList<>(metrics.size());
    for (AppMetric m : metrics) {
      final String lbl = m.label() != null ? m.label() : m.name();
      rows.add(new Row(app, m.key(), m.name(), lbl, 0, "us", 0, 0, 0, 0, true, null, null));
    }
    return drillLoop(Display.join(group.name(), group.label(), group.label()), rows);
  }

  /** A nested list of the individual queries under a drilled group. */
  private boolean drillLoop(String groupLabel, List<Row> drill) {
    while (true) {
      printList("Queries " + drill.size() + " — " + groupLabel, drill);
      System.out.print("Select " + AnsiColor.hot("1-" + drill.size(), "")
          + "   " + AnsiColor.hot("b", "ack") + "   " + AnsiColor.hot("q", "uit") + " > ");
      System.out.flush();
      String line = readLine();
      if (line == null) {
        System.out.println();
        return false;
      }
      line = line.trim();
      if (line.equalsIgnoreCase("q")) {
        return false;
      }
      if (line.isEmpty() || line.equalsIgnoreCase("b")) {
        return true;
      }
      Integer idx = parseIndex(line, drill.size());
      if (idx == null) {
        System.out.println("Enter 1-" + drill.size() + ", 'b' back, or 'q' quit.");
        continue;
      }
      if (!rowMenu(drill.get(idx - 1))) {
        return false;
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
      List<QueryPlanSummary> plans = insight.plans.listPlans(row.app(), null, null, row.hash(), null, null, 1);
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

  /**
   * Show the plan-change history for this row's hash: a short table of recent
   * change events, then the from/to EXPLAIN diff of the most recent one.
   */
  private void showChanges(Row row) {
    try {
      List<PlanChange> changes =
          insight.plans.listPlanChanges(row.app(), env, row.hash(), null, null, null, 10);
      if (changes.isEmpty()) {
        System.out.println("No plan-shape changes recorded for this hash"
            + (env == null ? "" : " (env " + env + ")") + ".");
        return;
      }
      System.out.println();
      System.out.print(ChangesCommand.renderTable(changes));
      PlanChangeDetail detail = insight.plans.getPlanChange(changes.get(0).id());
      System.out.println();
      System.out.print(ChangeCommand.render(detail));
    } catch (HttpException e) {
      System.out.println("Failed to load plan changes: HTTP " + e.statusCode());
    }
  }

  /** List plan-change events; pick one to diff and optionally drill into its query. */
  private int runChanges(List<PlanChange> changes) {
    while (true) {
      System.out.println();
      System.out.println("Plan changes " + changes.size());
      System.out.println();
      System.out.print(ChangesCommand.renderTable(changes));
      System.out.print("Select " + AnsiColor.hot("1-" + changes.size(), "")
          + "   " + AnsiColor.hot("q", "uit") + " > ");
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
      Integer idx = parseIndex(line, changes.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + changes.size() + " or 'q' to quit.");
        continue;
      }
      if (!changeMenu(changes.get(idx - 1))) {
        return 0;
      }
    }
  }

  /** Render one change's from/to diff, then offer to drill into the query's row menu. */
  private boolean changeMenu(PlanChange c) {
    try {
      PlanChangeDetail detail = insight.plans.getPlanChange(c.id());
      System.out.println();
      System.out.print(ChangeCommand.render(detail));
    } catch (HttpException e) {
      System.out.println("Failed to load change " + c.id() + ": HTTP " + e.statusCode());
      return true;
    }
    while (true) {
      System.out.println();
      System.out.print("  " + AnsiColor.hot("d", "rill (sql/plan/capture/trend)")
          + "  " + AnsiColor.hot("b", "ack") + "  " + AnsiColor.hot("q", "uit") + " > ");
      System.out.flush();
      String line = readLine();
      if (line == null) {
        System.out.println();
        return false;
      }
      switch (line.trim().toLowerCase(Locale.ROOT)) {
        case "", "b" -> {
          return true;
        }
        case "q" -> {
          return false;
        }
        case "d" -> {
          Row row = new Row(c.appName(), c.hash(), c.label() == null ? c.hash() : c.label(), 0, "us");
          if (!rowMenu(row)) {
            return false;
          }
        }
        default -> System.out.println("Use d/b/q.");
      }
    }
  }

  /**
   * Show the trend for a row, then loop letting the user re-render it by a
   * different headline measure (total/mean/max/count) or change the window
   * (1h/6h/1d/7d/30d). Switching measure is a pure re-render of the cached
   * buckets; switching window re-queries the server. Pressing {@code p} renders
   * the captured-plan history (table + a timeline overlay aligned to the current
   * trend window). Returns false when the user chose to quit the session.
   */
  private boolean showTrend(Row row) {
    long windowMin = TrendCommand.DEFAULT_TREND_WINDOW_MINUTES;
    TrendCommand.Measure tMeasure = measure;
    MetricTimeseries ts = fetchTrend(row, windowMin);
    if (ts == null) {
      return true;
    }
    while (true) {
      System.out.println();
      if (ts.buckets().isEmpty()) {
        System.out.println("Trend — " + row.label() + " [" + row.app() + "]");
        System.out.println("  No time-series data in the last " + windowLabel(windowMin)
            + (env == null ? "" : " (env " + env + ")") + ".");
      } else {
        TrendCommand.printTrend(ts, tMeasure);
      }
      System.out.print(trendPrompt(tMeasure, windowMin));
      System.out.flush();
      String line = readLine();
      if (line == null) {
        System.out.println();
        return false;
      }
      String cmd = line.trim().toLowerCase(Locale.ROOT);
      if (cmd.isEmpty() || cmd.equals("b")) {
        return true;
      }
      if (cmd.equals("q")) {
        return false;
      }
      long w = parseWindowMinutes(cmd);
      if (w > 0) {
        if (w != windowMin) {
          MetricTimeseries refetched = fetchTrend(row, w);
          if (refetched != null) {
            ts = refetched;
            windowMin = w;
          }
        }
        continue;
      }
      if (cmd.equals("p") || cmd.equals("plans")) {
        showPlanTimeline(row, ts);
        continue;
      }
      By chosen = parseBy(cmd);
      if (chosen != null) {
        tMeasure = TrendCommand.Measure.of(chosen.name());
      } else {
        System.out.println("Use a measure (t/m/x/n), a window (1h/6h/1d/7d/30d), 'p' for plans, 'b' back, or 'q' quit.");
      }
    }
  }

  private MetricTimeseries fetchTrend(Row row, long windowMin) {
    try {
      return insight.metrics.getMetricTimeseries(row.app(), row.hash(), windowMin, null, env);
    } catch (HttpException e) {
      if (e.statusCode() == 404) {
        System.out.println("This server build does not serve the per-hash time-series endpoint yet.");
      } else {
        System.out.println("Failed to load trend: HTTP " + e.statusCode());
      }
      return null;
    }
  }

  /** Window presets selectable in the trend loop; returns minutes, or -1 if not a window token. */
  static long parseWindowMinutes(String cmd) {
    return switch (cmd) {
      case "1h" -> 60L;
      case "6h" -> 360L;
      case "1d" -> 1440L;
      case "7d" -> 10080L;
      case "30d" -> 43200L;
      default -> -1L;
    };
  }

  /** Short human label for a window in minutes (e.g. 180 -> "3h", 1440 -> "1d"). */
  static String windowLabel(long minutes) {
    if (minutes % 1440 == 0) {
      return (minutes / 1440) + "d";
    }
    if (minutes % 60 == 0) {
      return (minutes / 60) + "h";
    }
    return minutes + "m";
  }

  /** Trend sub-prompt: re-render by measure, change window, list plans, or leave. */
  private String trendPrompt(TrendCommand.Measure m, long windowMin) {
    return "trend  by " + AnsiColor.hot("t", "otal") + " " + AnsiColor.hot("m", "ean")
        + " ma" + AnsiColor.hot("x", "") + " cou" + AnsiColor.hot("n", "t")
        + "   window " + AnsiColor.hot("1h", "") + " " + AnsiColor.hot("6h", "") + " "
        + AnsiColor.hot("1d", "") + " " + AnsiColor.hot("7d", "") + " " + AnsiColor.hot("30d", "")
        + "   " + AnsiColor.hot("p", "lans")
        + "  " + AnsiColor.hot("b", "ack") + "  " + AnsiColor.hot("q", "uit")
        + "\n(now: " + m.name() + " / " + windowLabel(windowMin) + ") > ";
  }

  /**
   * Render captured-plan history for the row: a timeline overlay aligned to the
   * current trend window's bucket axis, then a recent-captures table. Uses only
   * existing endpoints (no plan-change detection yet).
   */
  private void showPlanTimeline(Row row, MetricTimeseries ts) {
    List<QueryPlanSummary> plans;
    try {
      plans = insight.plans.listPlans(row.app(), env, null, row.hash(), null, null, 50);
    } catch (HttpException e) {
      System.out.println("Failed to load plans: HTTP " + e.statusCode());
      return;
    }
    if (plans.isEmpty()) {
      System.out.println("No captured plans for this hash"
          + (env == null ? "" : " (env " + env + ")") + ". Use 'b' then 'c' to request a capture.");
      return;
    }
    System.out.print(renderPlanOverlay(ts, plans));
    System.out.print(renderPlanTable(plans));
  }

  /** Distinct non-null plan-shape hashes across the supplied captures. */
  static int distinctShapes(List<QueryPlanSummary> plans) {
    java.util.Set<String> shapes = new java.util.HashSet<>();
    for (QueryPlanSummary p : plans) {
      if (p.planShapeHash() != null) {
        shapes.add(p.planShapeHash());
      }
    }
    return shapes.size();
  }

  /** Count of captures flagged as a plan-shape change point. */
  static int shapeChanges(List<QueryPlanSummary> plans) {
    int k = 0;
    for (QueryPlanSummary p : plans) {
      if (Boolean.TRUE.equals(p.shapeChanged())) {
        k++;
      }
    }
    return k;
  }

  /** A sparse marker row, aligned column-for-column with the trend chart, marking buckets that contain a plan capture. */
  String renderPlanOverlay(MetricTimeseries ts, List<QueryPlanSummary> plans) {
    List<MetricTimeBucket> buckets = ts.buckets();
    if (buckets.isEmpty()) {
      return "";
    }
    int n = buckets.size();
    int width = Math.min(n, TrendCommand.TARGET_WIDTH);
    long bucketMs = ts.bucketMinutes() * 60_000L;
    long windowStart = buckets.get(0).eventTime().toEpochMilli();
    long windowEnd = buckets.get(n - 1).eventTime().toEpochMilli() + bucketMs;
    char[] marks = new char[width];
    boolean[] isChange = new boolean[width];
    java.util.Arrays.fill(marks, ' ');
    int inWindow = 0;
    for (QueryPlanSummary p : plans) {
      if (p.whenCaptured() == null) {
        continue;
      }
      long t = p.whenCaptured().toEpochMilli();
      if (t < windowStart || t >= windowEnd || bucketMs <= 0) {
        continue;
      }
      int i = (int) ((t - windowStart) / bucketMs);
      i = Math.max(0, Math.min(n - 1, i));
      int col = (int) ((long) i * width / n);
      col = Math.max(0, Math.min(width - 1, col));
      boolean change = Boolean.TRUE.equals(p.shapeChanged());
      // a change point always wins the column glyph
      if (change || marks[col] == ' ') {
        marks[col] = change ? '\u25C6' : '\u25B2';
        isChange[col] = change;
      }
      inWindow++;
    }
    int distinct = distinctShapes(plans);
    int changes = shapeChanges(plans);
    StringBuilder sb = new StringBuilder();
    sb.append('\n');
    sb.append("  plan captures   ").append(inWindow).append(" in window (of ")
        .append(plans.size()).append(" recent), ")
        .append(distinct).append(distinct == 1 ? " shape, " : " shapes, ")
        .append(changes).append(changes == 1 ? " change" : " changes").append('\n');
    // colour change-point glyphs individually, plain chart colour for the rest
    sb.append("  ");
    for (int c = 0; c < width; c++) {
      String ch = String.valueOf(marks[c]);
      sb.append(isChange[c] ? AnsiColor.change(ch) : AnsiColor.chart(ch));
    }
    sb.append('\n');
    return sb.toString();
  }

  /** Recent captures, newest first, as a compact table. */
  String renderPlanTable(List<QueryPlanSummary> plans) {
    List<QueryPlanSummary> sorted = new ArrayList<>(plans);
    sorted.sort(java.util.Comparator
        .comparing(QueryPlanSummary::whenCaptured, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
        .reversed());
    StringBuilder sb = new StringBuilder();
    sb.append('\n');
    sb.append(String.format("  %-4s %-8s %-16s %12s %8s %-8s %-2s%n",
        "#", "ID", "CAPTURED", "QUERY(us)", "COUNT", "SHAPE", "\u0394"));
    int i = 1;
    for (QueryPlanSummary p : sorted) {
      boolean change = Boolean.TRUE.equals(p.shapeChanged());
      String delta = change ? AnsiColor.change("\u25C6") : " ";
      sb.append(String.format("  %-4d %-8d %-16s %,12d %,8d %-8s %-2s%n",
          i, p.id(), fmtCaptured(p.whenCaptured()), p.queryTimeMicros(), p.captureCount(),
          shortShape(p.planShapeHash()), delta));
      if (++i > 20) {
        break;
      }
    }
    return sb.toString();
  }

  /** First 8 hex chars of the shape hash, or an em-dash placeholder when absent. */
  static String shortShape(String planShapeHash) {
    if (planShapeHash == null || planShapeHash.isBlank()) {
      return "\u2014";
    }
    return planShapeHash.length() <= 8 ? planShapeHash : planShapeHash.substring(0, 8);
  }

  private static final java.time.format.DateTimeFormatter CAPTURED_FMT =
      java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

  private static String fmtCaptured(java.time.Instant when) {
    return when == null ? "-" : CAPTURED_FMT.format(when);
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
    cols.add(new Col("NAME", false, r -> r.name() == null ? "" : tail(r.name(), NAME_MAX)));
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
