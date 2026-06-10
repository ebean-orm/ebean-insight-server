package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.AppMetric;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * List the metrics known for an application.
 *
 * <p>The {@code HASH} column can be fed into {@code insight capture},
 * {@code insight plans --hash} or {@code insight top}. Full details (including
 * SQL) are available with {@code -o json}.
 */
@Command(name = "metrics", mixinStandardHelpOptions = true, description = "List the metrics known for an application.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight metrics --app myapp",
        "  insight metrics --app myapp --plan-capable",
        "  insight metrics --app myapp -o json   # includes full SQL"
    })
final class MetricsCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", required = true, description = "The application name.")
  String app;

  @Option(names = "--label", description = "Filter by query label.")
  @Nullable String label;

  @Option(names = "--plan-capable", arity = "0..1", fallbackValue = "true",
      description = "Filter to plan-capable metrics. Omit for no filter; use --plan-capable=false for the inverse.")
  @Nullable Boolean planCapable;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<AppMetric> metrics = insight.metrics.listAppMetrics(app, label, planCapable, limit);
      if (out.json()) {
        out.printJsonList(AppMetric.class, metrics);
        return 0;
      }
      if (metrics.isEmpty()) {
        System.out.println("No metrics found.");
        return 0;
      }
      System.out.printf("%-8s  %-40s  %-34s  %s%n", "ID", "NAME", "HASH", "LOC");
      for (AppMetric m : metrics) {
        System.out.printf("%-8d  %-40s  %-34s  %s%n",
            m.id(), m.name(), m.key() == null ? "" : m.key(), m.loc() == null ? "" : m.loc());
      }
      return 0;
    }
  }
}
