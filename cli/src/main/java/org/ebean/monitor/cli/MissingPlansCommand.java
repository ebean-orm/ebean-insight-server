package org.ebean.monitor.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.avaje.http.client.HttpException;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * List plan-capable metrics that have no recently captured query plan, ranked
 * by execution cost.
 *
 * <p>This is the home for "find the most expensive queries that lack a fresh
 * plan, then capture them". Omit {@code --app} to rank across all apps; supply
 * it to scope to one. Each row's {@code HASH} feeds straight into
 * {@code insight capture <app> <hash>}.
 */
@Command(name = "missing-plans", mixinStandardHelpOptions = true,
    description = "List plan-capable metrics with no recently captured query plan, ranked by cost.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight missing-plans                       # all apps, ranked by total time",
        "  insight missing-plans --app myapp --by mean",
        "  insight missing-plans --app myapp --older-than-hours 24",
        "  # then capture one:  insight capture myapp --env test <hash>",
        "  # or capture every listed metric in one go (capped by -n):",
        "  insight missing-plans --app myapp --env test -n 10 --capture --yes"
    })
final class MissingPlansCommand implements Callable<Integer> {

  enum OrderBy { total, mean, max, count }

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app",
      description = "Limit to one application. When omitted, ranks across all apps.")
  @Nullable String app;

  @Option(names = "--by", defaultValue = "total",
      description = "Rank by: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
  OrderBy by = OrderBy.total;

  @Option(names = "--since-minutes", description = "Cost window size in minutes (default: 60).")
  @Nullable Long sinceMinutes;

  @Option(names = "--since-hours", description = "Cost window size in hours (mutually exclusive with --since-minutes).")
  @Nullable Long sinceHours;

  @Option(names = "--older-than-minutes",
      description = "Only metrics whose last capture is older than N minutes (or never captured).")
  @Nullable Long olderThanMinutes;

  @Option(names = "--older-than-hours",
      description = "Like --older-than-minutes but in hours (mutually exclusive with --older-than-minutes).")
  @Nullable Long olderThanHours;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Option(names = "--capture",
      description = "Request a plan capture for every listed metric (capped by -n). Needs confirmation or --yes.")
  boolean capture;

  @Option(names = "--yes", description = "Skip the confirmation prompt when used with --capture.")
  boolean yes;

  @Option(names = "--env",
      description = "Environment to target with --capture (falls back to the persisted 'env' config).")
  @Nullable String env;

  @Override
  public Integer call() {
    if (sinceMinutes != null && sinceHours != null) {
      throw new CliException("Supply only one of --since-minutes / --since-hours, not both.");
    }
    if (olderThanMinutes != null && olderThanHours != null) {
      throw new CliException("Supply only one of --older-than-minutes / --older-than-hours, not both.");
    }
    if (app == null) {
      app = ConfigDefaults.appOrNull();
    }
    if (env == null) {
      env = ConfigDefaults.envOrNull();
    }
    final String orderBy = by.name();
    try (Insight insight = Insight.open(conn)) {
      List<MissingPlanMetric> rows = (app == null)
          ? insight.metrics.topMissingPlans(orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit)
          : insight.metrics.listMissingPlans(app, orderBy, sinceMinutes, sinceHours, olderThanMinutes, olderThanHours, limit);
      if (capture) {
        return captureAll(insight, rows);
      }
      if (out.json()) {
        out.printJsonList(MissingPlanMetric.class, rows);
        return 0;
      }
      if (rows.isEmpty()) {
        System.out.println("No missing plans found.");
        return 0;
      }
      int appWidth = "APP".length();
      int labelWidth = "LABEL".length();
      for (MissingPlanMetric m : rows) {
        if (m.app() != null) {
          appWidth = Math.max(appWidth, m.app().length());
        }
        if (m.label() != null) {
          labelWidth = Math.max(labelWidth, m.label().length());
        }
      }
      String headFmt = "%-" + appWidth + "s  %-" + labelWidth + "s  %10s  %16s  %14s  %14s  %9s  %-26s  %s%n";
      String rowFmt = "%-" + appWidth + "s  %-" + labelWidth + "s  %10d  %16d  %14d  %14d  %9d  %-26s  %s%n";
      System.out.printf(headFmt,
          "APP", "LABEL", "COUNT", "TOTAL(us)", "MEAN(us)", "MAX(us)", "CAPTURES", "LAST_CAPTURED", "HASH");
      for (MissingPlanMetric m : rows) {
        System.out.printf(rowFmt,
            m.app(), m.label(), m.count(), m.totalMicros(), m.meanMicros(), m.maxMicros(),
            m.captureCount(),
            m.lastCapturedAt() == null ? "never" : m.lastCapturedAt().toString(),
            m.key());
      }
      return 0;
    }
  }

  private Integer captureAll(Insight insight, List<MissingPlanMetric> rows) {
    if (rows.isEmpty()) {
      System.out.println("No missing plans found.");
      return 0;
    }
    if (!confirmCapture(rows.size())) {
      System.out.println("Aborted.");
      return 1;
    }
    final List<CaptureResult> results = new ArrayList<>(rows.size());
    boolean anyError = false;
    for (MissingPlanMetric m : rows) {
      try {
        var pending = insight.plans.requestPlanCapture(m.app(), m.key(), env);
        results.add(new CaptureResult(m.key(), pending.label() != null ? pending.label() : m.label(), null));
      } catch (HttpException e) {
        anyError = true;
        results.add(new CaptureResult(m.key(), m.label(), "HTTP " + e.statusCode()));
      }
    }
    if (out.json()) {
      out.printJsonList(CaptureResult.class, results);
      return anyError ? 1 : 0;
    }
    CaptureResult.printText(results);
    final long ok = results.stream().filter(r -> r.error() == null).count();
    System.out.printf("%nRequested %d of %d captures.%n", ok, results.size());
    return anyError ? 1 : 0;
  }

  private boolean confirmCapture(int count) {
    if (yes) {
      return true;
    }
    final var console = System.console();
    if (console == null) {
      throw new CliException("Refusing to capture " + count
          + " plan(s) without confirmation; re-run with --yes.");
    }
    final String answer = console.readLine("Capture %d plan(s)? [y/N] ", count);
    return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
  }
}
