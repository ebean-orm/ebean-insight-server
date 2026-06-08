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
    description = "List top metrics by total/mean/max time (or call count) over a recent window.")
final class TopCommand implements Callable<Integer> {

  enum OrderBy { total, mean, max, count }

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app",
      description = "Limit to one application. When omitted, ranks across all apps.")
  @Nullable String app;

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

  @Override
  public Integer call() {
    if (sinceMinutes != null && sinceHours != null) {
      throw new CliException("Supply only one of --since-minutes / --since-hours, not both.");
    }
    final String orderBy = by.name();
    try (Insight insight = Insight.open(conn)) {
      List<AppMetricStats> rows = (app == null)
          ? insight.metrics.topMetrics(orderBy, sinceMinutes, sinceHours, limit, planCapable)
          : insight.metrics.topAppMetrics(app, orderBy, sinceMinutes, sinceHours, limit, planCapable);
      if (out.json()) {
        out.printJsonList(AppMetricStats.class, rows);
        return 0;
      }
      if (rows.isEmpty()) {
        System.out.println("No metrics found.");
        return 0;
      }
      System.out.printf("%-24s %-34s %10s %16s %14s %14s %8s %5s  %s%n",
          "APP", "LABEL", "COUNT", "TOTAL(us)", "MEAN(us)", "MAX(us)", "WINDOW", "PLAN", "HASH");
      for (AppMetricStats r : rows) {
        System.out.printf("%-24s %-34s %10d %16d %14d %14d %8d %5s  %s%n",
            r.app(), r.label(), r.count(), r.totalMicros(), r.meanMicros(), r.maxMicros(),
            r.windowMinutes(), r.planCapable() ? "yes" : "no", r.key());
      }
      return 0;
    }
  }
}
