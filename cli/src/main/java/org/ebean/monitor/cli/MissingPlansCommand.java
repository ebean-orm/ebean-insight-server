package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * List plan-capable metrics that have no recently captured query plan.
 *
 * <p>Useful for deciding what to {@code insight capture} next: each row's
 * {@code HASH} can be fed straight into {@code insight capture <app> <hash>}.
 */
@Command(name = "missing-plans", mixinStandardHelpOptions = true,
    description = "List plan-capable metrics with no recently captured query plan.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight missing-plans --app myapp",
        "  insight missing-plans --app myapp --older-than-hours 24",
        "  # then capture one:  insight capture myapp <hash> --env test"
    })
final class MissingPlansCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", required = true, description = "The application name.")
  String app;

  @Option(names = "--older-than-minutes",
      description = "Only metrics whose last capture is older than N minutes (or never captured).")
  @Nullable Long olderThanMinutes;

  @Option(names = "--older-than-hours",
      description = "Like --older-than-minutes but in hours (mutually exclusive with --older-than-minutes).")
  @Nullable Long olderThanHours;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Override
  public Integer call() {
    if (olderThanMinutes != null && olderThanHours != null) {
      throw new CliException("Supply only one of --older-than-minutes / --older-than-hours, not both.");
    }
    try (Insight insight = Insight.open(conn)) {
      List<MissingPlanMetric> rows = insight.metrics.listMissingPlans(app, olderThanMinutes, olderThanHours, limit);
      if (out.json()) {
        out.printJsonList(MissingPlanMetric.class, rows);
        return 0;
      }
      if (rows.isEmpty()) {
        System.out.println("No missing plans found.");
        return 0;
      }
      System.out.printf("%-8s %-34s %9s %-26s %-34s %s%n",
          "ID", "LABEL", "CAPTURES", "LAST_CAPTURED", "HASH", "LOC");
      for (MissingPlanMetric m : rows) {
        System.out.printf("%-8d %-34s %9d %-26s %-34s %s%n",
            m.id(), m.label(), m.captureCount(),
            m.lastCapturedAt() == null ? "never" : m.lastCapturedAt().toString(),
            m.key(), m.loc() == null ? "" : m.loc());
      }
      return 0;
    }
  }
}
