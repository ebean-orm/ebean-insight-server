package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.TopGroup;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Top metrics ranked over a recent window, aggregated at the level chosen by
 * {@code --by}.
 *
 * <p>{@code --by} selects the grouping dimension across the three aggregation
 * levels — {@code name} (coarsest: metric families), {@code label} (the default
 * middle level: one row per label tag), and {@code hash} (finest: individual
 * queries). It can also group by any other tag key such as {@code type} or
 * {@code kind}. {@code --sort} selects the ranking measure (total/mean/max time,
 * call count, or gauge value).
 *
 * <p>With {@code --by hash} the {@code HASH} column can be fed directly into
 * {@code insight capture} or {@code insight plans --hash}.
 */
@Command(name = "top", mixinStandardHelpOptions = true,
    description = "List top metrics aggregated by label/name/hash/tag, ranked over a recent window.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight top                              # top labels (default), by total time, last 60m",
        "  insight top --by name                    # coarsest level: roll up per metric family",
        "  insight top --by label                   # middle level: one row per label tag",
        "  insight top --by hash                    # finest level: individual queries (HASH feeds capture)",
        "  insight top --by type --name ebean.query # group ebean.query by its type tag",
        "  insight top --sort mean --since-hours 6  # rank by mean over a wider window",
        "  insight top --sort max                   # worst single execution",
        "  insight top --app myapp --env test       # scope to one app / one env",
        "  insight top --plan-capable               # only plan-capable queries",
        "  insight top --chart                      # horizontal Pareto bar chart",
        "  insight top --by hash --sort mean -i     # interactive drill-down (sql/plan/capture)",
        "  insight top -o json | jq ."
    })
final class TopCommand implements Callable<Integer> {

  /** Ranking measure (the SQL {@code orderBy}). */
  enum Sort { total, mean, max, count, value }

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app",
      description = "Limit to one application. When omitted, ranks across all apps.")
  @Nullable String app;

  @Option(names = "--env",
      description = "Limit to one environment (e.g. test, dev). When omitted, spans all envs.")
  @Nullable String env;

  @Option(names = "--by", defaultValue = "label",
      description = "Aggregation dimension: hash, name, or a tag key such as label, type, kind"
          + " (default: ${DEFAULT-VALUE}).")
  String by = "label";

  @Option(names = "--sort", defaultValue = "total",
      description = "Rank by: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}). 'value' ranks gauges by peak.")
  Sort sort = Sort.total;

  @Option(names = "--name",
      description = "Filter to one metric family name (e.g. ebean.query, web.api).")
  @Nullable String name;

  @Option(names = "--kind", description = "Filter by the 'kind' tag (e.g. orm).")
  @Nullable String kind;

  @Option(names = "--type", description = "Filter by the 'type' tag (e.g. a bean type).")
  @Nullable String type;

  @Option(names = "--since-minutes", description = "Window size in minutes (default: 60).")
  @Nullable Long sinceMinutes;

  @Option(names = "--since-hours", description = "Window size in hours (mutually exclusive with --since-minutes).")
  @Nullable Long sinceHours;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Option(names = "--plan-capable", arity = "0..1", fallbackValue = "true",
      description = "Filter to plan-capable metrics. Omit for no filter; use --plan-capable=false for the inverse.")
  @Nullable Boolean planCapable;

  @Option(names = {"-c", "--chart"}, description = "Render a horizontal Pareto bar chart instead of the table.")
  boolean chart;

  @Option(names = {"-i", "--interactive"},
      description = "Drill down interactively: pick a row, then view sql/plan/capture/trend.")
  boolean interactive;

  @Override
  public Integer call() {
    if (sinceMinutes != null && sinceHours != null) {
      throw new CliException("Supply only one of --since-minutes / --since-hours, not both.");
    }
    if (app == null) {
      app = ConfigDefaults.appOrNull();
    }
    if (env == null) {
      env = ConfigDefaults.envOrNull();
    }
    final boolean byHash = "hash".equalsIgnoreCase(by);
    final boolean gauge = sort == Sort.value;
    try (Insight insight = Insight.open(conn)) {
      java.util.function.Function<String, List<TopGroup>> fetch = ob -> (app == null)
          ? insight.metrics.topMetrics(by, name, kind, type, ob, sinceMinutes, sinceHours, limit, planCapable, env)
          : insight.metrics.topAppMetrics(app, by, name, kind, type, ob, sinceMinutes, sinceHours, limit, planCapable, env);
      List<TopGroup> rows = fetch.apply(sort.name());
      if (out.json()) {
        out.printJsonList(TopGroup.class, rows);
        return 0;
      }
      if (rows.isEmpty()) {
        System.out.println("No metrics found.");
        return 0;
      }
      if (interactive) {
        return Interactive.topLoop(insight, app, by, rows, sort, env, fetch);
      }
      if (chart) {
        Charts.printPareto(rows, sort);
        return 0;
      }
      printTable(rows, byHash, gauge);
      return 0;
    }
  }

  private void printTable(List<TopGroup> rows, boolean byHash, boolean gauge) {
    int appWidth = "APP".length();
    int groupWidth = "GROUP".length();
    for (TopGroup r : rows) {
      if (r.app() != null) {
        appWidth = Math.max(appWidth, r.app().length());
      }
      if (r.group() != null) {
        groupWidth = Math.max(groupWidth, r.group().length());
      }
    }
    if (gauge) {
      String headFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %16s  %8s  %6s%n";
      String rowFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %16s  %8d  %6d%n";
      System.out.printf(headFmt, "APP", "GROUP", "VALUE", "WINDOW", "HASHES");
      for (TopGroup r : rows) {
        System.out.printf(rowFmt, nv(r.app()), nv(r.group()),
            r.value() == null ? "" : String.format("%,.0f", r.value()),
            r.windowMinutes(), r.hashCount());
      }
      return;
    }
    if (byHash) {
      String headFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %10s  %16s  %14s  %14s  %8s  %5s  %s%n";
      String rowFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %10d  %16d  %14d  %14d  %8d  %5s  %s%n";
      System.out.printf(headFmt,
          "APP", "LABEL", "COUNT", "TOTAL(us)", "MEAN(us)", "MAX(us)", "WINDOW", "PLAN", "HASH");
      for (TopGroup r : rows) {
        System.out.printf(rowFmt,
            nv(r.app()), nv(r.label() == null ? r.group() : r.label()),
            z(r.count()), z(r.totalMicros()), z(r.meanMicros()), z(r.maxMicros()),
            r.windowMinutes(), Boolean.TRUE.equals(r.planCapable()) ? "yes" : "no", nv(r.key()));
      }
      return;
    }
    String headFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %10s  %16s  %14s  %14s  %8s  %6s%n";
    String rowFmt = "%-" + appWidth + "s  %-" + groupWidth + "s  %10d  %16d  %14d  %14d  %8d  %6d%n";
    System.out.printf(headFmt,
        "APP", "GROUP", "COUNT", "TOTAL(us)", "MEAN(us)", "MAX(us)", "WINDOW", "HASHES");
    for (TopGroup r : rows) {
      System.out.printf(rowFmt,
          nv(r.app()), nv(r.group()),
          z(r.count()), z(r.totalMicros()), z(r.meanMicros()), z(r.maxMicros()),
          r.windowMinutes(), r.hashCount());
    }
  }

  private static String nv(@Nullable String s) {
    return s == null ? "" : s;
  }

  private static long z(@Nullable Long v) {
    return v == null ? 0L : v;
  }
}
