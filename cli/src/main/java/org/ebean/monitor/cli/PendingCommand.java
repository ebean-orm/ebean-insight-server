package org.ebean.monitor.cli;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.PendingPlan;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * List in-flight query-plan capture requests tracked durably on the server.
 *
 * <p>The server records each capture request in the database and clears it once
 * the matching plan is ingested, so this view survives forwarder polls and
 * server restarts and covers the whole bind-collection window. A request that is
 * never collected (its query did not execute) ages out after ~15 minutes. The
 * AGE column shows how long each request has been in flight.
 */
@Command(name = "pending", mixinStandardHelpOptions = true,
    description = "List in-flight plan captures (requested but not yet collected).",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight pending",
        "  insight pending --app myapp --env test",
        "  # right after a bulk request, confirm it is in flight:",
        "  insight missing-plans --app myapp --env test -n 10 --capture --yes",
        "  insight pending --app myapp"
    })
final class PendingCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", description = "Filter by application name.")
  @Nullable String app;

  @Option(names = "--env", description = "Filter by environment name.")
  @Nullable String env;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<PendingPlan> pending = insight.plans.listPendingPlans(app, env);
      if (out.json()) {
        out.printJsonList(PendingPlan.class, pending);
        return 0;
      }
      if (pending.isEmpty()) {
        System.out.println("No captures in flight.");
        return 0;
      }
      Instant now = Instant.now();
      int appWidth = "APP".length();
      int envWidth = "ENV".length();
      int labelWidth = "LABEL".length();
      for (PendingPlan p : pending) {
        if (p.app() != null) {
          appWidth = Math.max(appWidth, p.app().length());
        }
        if (p.env() != null) {
          envWidth = Math.max(envWidth, p.env().length());
        }
        if (p.label() != null) {
          labelWidth = Math.max(labelWidth, p.label().length());
        }
      }
      String fmt = "%-" + appWidth + "s  %-" + envWidth + "s  %-32s  %-" + labelWidth + "s  %s%n";
      System.out.printf(fmt, "APP", "ENV", "HASH", "LABEL", "AGE");
      for (PendingPlan p : pending) {
        System.out.printf(fmt, p.app(), p.env(), p.hash(), p.label() == null ? "" : p.label(), age(now, p.requestedAt()));
      }
      System.out.printf("%n%d capture(s) in flight.%n", pending.size());
      return 0;
    }
  }

  private static String age(Instant now, @Nullable Instant requestedAt) {
    if (requestedAt == null) {
      return "-";
    }
    long seconds = Math.max(0, Duration.between(requestedAt, now).getSeconds());
    if (seconds < 60) {
      return seconds + "s";
    }
    if (seconds < 3600) {
      return (seconds / 60) + "m";
    }
    return (seconds / 3600) + "h";
  }
}

