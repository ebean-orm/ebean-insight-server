package org.ebean.monitor.cli;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.PlanChange;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** List recently detected query-plan shape changes (FIRST / CHANGED), newest first. */
@Command(name = "changes", mixinStandardHelpOptions = true,
    description = "List recently changed query plans (plan-shape change events), newest first.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight changes                              # most recent shape changes, all apps",
        "  insight changes --app myapp --env prod       # scope to one app/env",
        "  insight changes --type CHANGED               # only shape changes (exclude first-seen)",
        "  insight changes --type FIRST                 # only first-observed shapes",
        "  insight changes --hash <hash>                # one query's change history",
        "  insight changes --since-hours 24             # detected in the last 24h",
        "  insight changes -i                           # interactive: pick a change, diff it, drill into the query",
        "  insight plan <id>                            # inspect a plan referenced by a change"
    })
final class ChangesCommand implements Callable<Integer> {

  private static final DateTimeFormatter WHEN_FMT =
      DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", description = "Filter by application name.")
  @Nullable String app;

  @Option(names = "--env", description = "Filter by environment name.")
  @Nullable String env;

  @Option(names = "--hash", description = "Filter by plan hash.")
  @Nullable String hash;

  @Option(names = "--type", description = "Filter by change type: FIRST or CHANGED.")
  @Nullable String type;

  @Option(names = "--since-minutes", description = "Only changes detected within the last N minutes.")
  @Nullable Long sinceMinutes;

  @Option(names = "--since-hours", description = "Only changes detected within the last N hours.")
  @Nullable Long sinceHours;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Option(names = {"-i", "--interactive"},
      description = "Browse changes interactively: pick a change to diff, then drill into the query.")
  boolean interactive;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<PlanChange> changes =
          insight.plans.listPlanChanges(app, env, hash, type, sinceMinutes, sinceHours, limit);
      if (out.json()) {
        out.printJsonList(PlanChange.class, changes);
        return 0;
      }
      if (changes.isEmpty()) {
        System.out.println("No plan changes found.");
        return 0;
      }
      if (interactive) {
        return Interactive.changesLoop(insight, changes, env);
      }
      printTable(changes);
      return 0;
    }
  }

  private static void printTable(List<PlanChange> changes) {
    System.out.print(renderTable(changes));
  }

  static String renderTable(List<PlanChange> changes) {
    int appWidth = "APP".length();
    int labelWidth = "LABEL".length();
    int shapeWidth = "SHAPE".length();
    for (PlanChange c : changes) {
      appWidth = Math.max(appWidth, c.appName().length());
      if (c.label() != null) {
        labelWidth = Math.max(labelWidth, c.label().length());
      }
      shapeWidth = Math.max(shapeWidth, shapeCell(c).length());
    }
    String headFmt = "%-11s  %-" + appWidth + "s  %-10s  %-7s  %-" + labelWidth
        + "s  %-8s  %-" + shapeWidth + "s  %12s%n";
    String rowFmt = "%-11s  %-" + appWidth + "s  %-10s  %-7s  %-" + labelWidth
        + "s  %-8s  %-" + shapeWidth + "s  %12d%n";
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(headFmt, "DETECTED", "APP", "ENV", "TYPE", "LABEL", "HASH", "SHAPE", "TIME(us)"));
    for (PlanChange c : changes) {
      sb.append(String.format(rowFmt,
          fmtWhen(c.detectedAt()), c.appName(), c.envName(), c.changeType(),
          c.label() == null ? "" : c.label(),
          Interactive.shortShape(c.hash()), shapeCell(c), c.toQueryTimeMicros()));
    }
    return sb.toString();
  }

  /** For CHANGED, render {@code from→to} short shape hashes; for FIRST just the new shape. */
  private static String shapeCell(PlanChange c) {
    String to = Interactive.shortShape(c.toShapeHash());
    if (c.fromShapeHash() == null) {
      return to;
    }
    return Interactive.shortShape(c.fromShapeHash()) + "\u2192" + to;
  }

  private static String fmtWhen(Instant when) {
    return when == null ? "-" : WHEN_FMT.format(when);
  }
}
