package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.AppMetricStats;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Top metrics ranked by aggregated timing over a recent window.
 *
 * <p>Reports the most expensive queries (by total, mean, max time or call
 * count) across all apps, or for a single app when {@code --app} is supplied.
 * The {@code HASH} column can be fed directly into {@code insight capture} or
 * {@code insight plans --hash}.
 */
@Command(name = "top", mixinStandardHelpOptions = true,
    description = "List top metrics by total/mean/max time (or call count) over a recent window.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight top                              # all apps, by total time, last 60m",
        "  insight top --by mean --since-hours 6    # rank by mean over a wider window",
        "  insight top --by max                     # worst single execution",
        "  insight top --by count                   # highest call volume",
        "  insight top --app myapp --env test       # scope to one app / one env",
        "  insight top --plan-capable               # only plan-capable queries",
        "  insight top --chart                      # horizontal Pareto bar chart",
        "  insight top --by mean -i                 # interactive drill-down (sql/plan/capture)",
        "  insight top -o json | jq .",
        "  # the HASH column feeds straight into:  insight capture <app> --env <env> <hash>"
    })
final class TopCommand implements Callable<Integer> {

  enum OrderBy { total, mean, max, count }

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app",
      description = "Limit to one application. When omitted, ranks across all apps.")
  @Nullable String app;

  @Option(names = "--env",
      description = "Limit to one environment (e.g. test, dev). When omitted, spans all envs.")
  @Nullable String env;

  @Option(names = "--by", defaultValue = "total",
      description = "Rank by: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
  OrderBy by = OrderBy.total;

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
    final String orderBy = by.name();
    try (Insight insight = Insight.open(conn)) {
      java.util.function.Function<String, List<AppMetricStats>> fetch = ob -> (app == null)
          ? insight.metrics.topMetrics(ob, sinceMinutes, sinceHours, limit, planCapable, env)
          : insight.metrics.topAppMetrics(app, ob, sinceMinutes, sinceHours, limit, planCapable, env);
      List<AppMetricStats> rows = fetch.apply(orderBy);
      if (out.json()) {
        out.printJsonList(AppMetricStats.class, rows);
        return 0;
      }
      if (rows.isEmpty()) {
        System.out.println("No metrics found.");
        return 0;
      }
      if (interactive) {
        return Interactive.topLoop(insight, rows, by, env, fetch);
      }
      if (chart) {
        Charts.printPareto(rows, by);
        return 0;
      }
      int appWidth = "APP".length();
      int labelWidth = "LABEL".length();
      for (AppMetricStats r : rows) {
        if (r.app() != null) {
          appWidth = Math.max(appWidth, r.app().length());
        }
        if (r.label() != null) {
          labelWidth = Math.max(labelWidth, r.label().length());
        }
      }
      String headFmt = "%-" + appWidth + "s  %-" + labelWidth + "s  %10s  %16s  %14s  %14s  %8s  %5s  %s%n";
      String rowFmt = "%-" + appWidth + "s  %-" + labelWidth + "s  %10d  %16d  %14d  %14d  %8d  %5s  %s%n";
      System.out.printf(headFmt,
          "APP", "LABEL", "COUNT", "TOTAL(us)", "MEAN(us)", "MAX(us)", "WINDOW", "PLAN", "HASH");
      for (AppMetricStats r : rows) {
        System.out.printf(rowFmt,
            r.app(), r.label(), r.count(), r.totalMicros(), r.meanMicros(), r.maxMicros(),
            r.windowMinutes(), r.planCapable() ? "yes" : "no", r.key());
      }
      return 0;
    }
  }
}
