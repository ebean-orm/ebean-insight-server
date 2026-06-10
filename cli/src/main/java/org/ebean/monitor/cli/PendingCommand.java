package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.PendingPlan;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * List query-plan capture requests queued on the server awaiting delivery.
 *
 * <p>The server holds capture requests in memory until the application's
 * forwarder next polls (usually within seconds), then drains them. A zero
 * result therefore does not prove nothing is in flight — a request may already
 * have been delivered and still be inside its bind-collection window. Use this
 * mainly to confirm a (bulk) capture was queued and that a consumer is draining
 * it.
 */
@Command(name = "pending", mixinStandardHelpOptions = true,
    description = "List plan captures queued on the server awaiting delivery to the forwarder.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight pending",
        "  insight pending --app myapp --env test",
        "  # right after a bulk request, confirm it queued:",
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
        System.out.println("No pending captures queued.");
        return 0;
      }
      System.out.printf("%-24s  %-16s  %s%n", "APP", "ENV", "HASH");
      for (PendingPlan p : pending) {
        System.out.printf("%-24s  %-16s  %s%n", p.app(), p.env(), p.hash());
      }
      System.out.printf("%n%d pending capture(s) queued.%n", pending.size());
      return 0;
    }
  }
}
