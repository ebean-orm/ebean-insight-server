package org.ebean.monitor.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;
import org.jspecify.annotations.Nullable;
import io.avaje.http.client.HttpException;
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
        "  insight plans -i                           # interactive: pick a row to view its plan",
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

  @Option(names = {"-i", "--interactive"}, description = "Browse plans interactively; pick a row to view its detail.")
  boolean interactive;

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
      if (interactive) {
        return interactiveLoop(insight, plans);
      }
      printTable(plans, false);
      return 0;
    }
  }

  /** Render the plan list. When {@code indexed}, prepend a 1-based {@code #} column. */
  private static void printTable(List<QueryPlanSummary> plans, boolean indexed) {
    int labelWidth = "LABEL".length();
    for (QueryPlanSummary p : plans) {
      if (p.label() != null) {
        labelWidth = Math.max(labelWidth, p.label().length());
      }
    }
    int idxWidth = Math.max(1, Integer.toString(plans.size()).length());
    String idxHead = indexed ? "%-" + idxWidth + "s  " : "%s";
    String idxRow = indexed ? "%-" + idxWidth + "d  " : "%s";
    String headFmt = idxHead + "%-8s  %-12s  %-32s  %-" + labelWidth + "s  %12s  %8s  %s%n";
    String rowFmt = idxRow + "%-8d  %-12s  %-32s  %-" + labelWidth + "s  %12d  %8d  %s%n";
    System.out.printf(headFmt,
        indexed ? "#" : "", "ID", "ENV", "HASH", "LABEL", "TIME(us)", "COUNT", "CAPTURED");
    int i = 1;
    for (QueryPlanSummary p : plans) {
      System.out.printf(rowFmt,
          indexed ? i++ : "", p.id(), p.envName(), p.hash(), p.label(),
          p.queryTimeMicros(), p.captureCount(), p.whenCaptured());
    }
  }

  private static int interactiveLoop(Insight insight, List<QueryPlanSummary> plans) {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (true) {
      System.out.println();
      System.out.println("Plans " + plans.size() + " (most recent)");
      System.out.println();
      printTable(plans, true);
      System.out.print("Select " + AnsiColor.hot("1-" + plans.size(), "")
          + "   " + AnsiColor.hot("q", "uit") + " > ");
      System.out.flush();
      String line;
      try {
        line = in.readLine();
      } catch (IOException e) {
        return 0;
      }
      if (line == null) {
        System.out.println();
        return 0;
      }
      line = line.trim();
      if (line.isEmpty() || line.equalsIgnoreCase("q")) {
        return 0;
      }
      Integer idx = parseIndex(line, plans.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + plans.size() + " or 'q' to quit.");
        continue;
      }
      showPlan(insight, plans.get(idx - 1).id());
    }
  }

  private static void showPlan(Insight insight, long id) {
    System.out.println();
    try {
      QueryPlan p = insight.plans.getPlan(id);
      PlanCommand.printPlan(p);
    } catch (HttpException e) {
      System.out.println("Failed to load plan " + id + ": HTTP " + e.statusCode());
    }
  }

  private static Integer parseIndex(String line, int size) {
    try {
      int n = Integer.parseInt(line);
      return (n >= 1 && n <= size) ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
