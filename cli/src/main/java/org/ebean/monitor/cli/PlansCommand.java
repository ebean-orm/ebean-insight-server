package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** List recently captured query plans. */
@Command(name = "plans", mixinStandardHelpOptions = true, description = "List recently captured query plans.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight plans --app myapp --env test",
        "  insight plans --hash <hash>                # all plans for one query",
        "  insight plans --since-hours 6              # captured in the last 6h",
        "  insight plan <id>          # full SQL, bind values and EXPLAIN output",
        "  insight plan <id> --raw    # EXPLAIN plan text only"
    })
final class PlansCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", description = "Filter by application name.")
  @Nullable String app;

  @Option(names = "--env", description = "Filter by environment name.")
  @Nullable String env;

  @Option(names = "--label", description = "Filter by query label.")
  @Nullable String label;

  @Option(names = "--hash", description = "Filter by plan hash.")
  @Nullable String hash;

  @Option(names = "--since-minutes", description = "Only plans captured within the last N minutes.")
  @Nullable Long sinceMinutes;

  @Option(names = "--since-hours", description = "Only plans captured within the last N hours.")
  @Nullable Long sinceHours;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<QueryPlanSummary> plans =
          insight.plans.listPlans(app, env, label, hash, sinceMinutes, sinceHours, limit);
      if (out.json()) {
        out.printJsonList(QueryPlanSummary.class, plans);
        return 0;
      }
      if (plans.isEmpty()) {
        System.out.println("No plans found.");
        return 0;
      }
      System.out.printf("%-8s %-12s %-10s %-30s %12s %8s  %s%n",
          "ID", "ENV", "HASH", "LABEL", "TIME(us)", "COUNT", "CAPTURED");
      for (QueryPlanSummary p : plans) {
        System.out.printf("%-8d %-12s %-10s %-30s %12d %8d  %s%n",
            p.id(), p.envName(), p.hash(), p.label(),
            p.queryTimeMicros(), p.captureCount(), p.whenCaptured());
      }
      return 0;
    }
  }
}
