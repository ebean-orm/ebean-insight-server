package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.AppMetric;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Show the details of a single metric identified by its hash.
 *
 * <p>Drills into one metric (the {@code HASH} reported by {@code top},
 * {@code metrics} or {@code missing-plans}) — its name, key, source location
 * and the full SQL. Use {@code -o json} for the raw record.
 */
@Command(name = "metric", mixinStandardHelpOptions = true,
    description = "Show one metric (name, location and full SQL) by its hash.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight metric myapp <hash>",
        "  insight metric --app myapp --hash <hash>",
        "  insight metric myapp <hash> -i           # interactive drill-down (sql/plan/capture/trend/history)",
        "  insight metric myapp <hash> -o json",
        "  # find the hash first:  insight top --app myapp   (HASH column)"
    })
final class MetricCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", arity = "0..1", description = "Application name (falls back to --app or config).")
  @Nullable String appArg;

  @Parameters(index = "1", arity = "0..1", description = "Metric hash (falls back to --hash).")
  @Nullable String hashArg;

  @Option(names = "--app", description = "Application name (alternative to the positional).")
  @Nullable String appOpt;

  @Option(names = "--hash", description = "Metric hash (alternative to the positional).")
  @Nullable String hashOpt;

  @Option(names = "--env",
      description = "Limit to one environment (used for capture/trend in interactive mode).")
  @Nullable String env;

  @Option(names = {"-i", "--interactive"},
      description = "Drill down interactively: sql/plan/capture/trend/history for this hash.")
  boolean interactive;

  @Override
  public Integer call() {
    String app = appOpt != null ? appOpt : appArg;
    if (app == null) {
      app = ConfigDefaults.appOrNull();
    }
    if (app == null) {
      throw new CliException("No application given. Pass it positionally, with --app, or set 'app' in config.");
    }
    String hash = hashOpt != null ? hashOpt : hashArg;
    if (hash == null) {
      throw new CliException("No metric hash given. Pass it positionally or with --hash.");
    }
    try (Insight insight = Insight.open(conn)) {
      if (interactive && !out.json()) {
        return Interactive.inspectLoop(insight, app, hash, env);
      }
      List<AppMetric> metrics = insight.metrics.getMetricByHash(app, hash);
      if (out.json()) {
        out.printJsonList(AppMetric.class, metrics);
        return 0;
      }
      if (metrics.isEmpty()) {
        System.out.println("No metric found for hash " + hash + " in app " + app + ".");
        return 1;
      }
      printMetric(metrics.get(0));
      return 0;
    }
  }

  static void printMetric(AppMetric m) {
    System.out.println("Name: " + m.name());
    System.out.println("Hash: " + (m.key() == null ? "" : m.key()));
    if (m.loc() != null && !m.loc().isBlank()) {
      System.out.println("Loc:  " + m.loc());
    }
    System.out.println();
    System.out.println("SQL:");
    System.out.println(m.sql() == null ? "(none)" : m.sql());
  }
}
