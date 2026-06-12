package org.ebean.monitor.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        "  insight metrics --app myapp -i        # interactive: pick a row to view its SQL",
        "  insight metrics --app myapp -o json   # includes full SQL"
    })
final class MetricsCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--app", required = true, description = "The application name.")
  String app;

  @Option(names = "--name", description = "Filter by metric family name (e.g. ebean.query).")
  @Nullable String name;

  @Option(names = "--label", description = "Filter by the 'label' tag.")
  @Nullable String label;

  @Option(names = "--kind", description = "Filter by the 'kind' tag (e.g. orm).")
  @Nullable String kind;

  @Option(names = "--type", description = "Filter by the 'type' tag (e.g. a bean type).")
  @Nullable String type;

  @Option(names = "--plan-capable", arity = "0..1", fallbackValue = "true",
      description = "Filter to plan-capable metrics. Omit for no filter; use --plan-capable=false for the inverse.")
  @Nullable Boolean planCapable;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum rows (default: ${DEFAULT-VALUE}).")
  Integer limit = 20;

  @Option(names = {"-i", "--interactive"}, description = "Browse metrics interactively; pick a row to view its SQL.")
  boolean interactive;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<AppMetric> metrics = insight.metrics.listAppMetrics(app, name, label, kind, type, planCapable, limit);
      if (out.json()) {
        out.printJsonList(AppMetric.class, metrics);
        return 0;
      }
      if (metrics.isEmpty()) {
        System.out.println("No metrics found.");
        return 0;
      }
      if (interactive) {
        return interactiveLoop(metrics);
      }
      printTable(metrics, false);
      return 0;
    }
  }

  /** Render the metric list. When {@code indexed}, prepend a 1-based {@code #} column. */
  private static void printTable(List<AppMetric> metrics, boolean indexed) {
    int nameWidth = "NAME".length();
    for (AppMetric m : metrics) {
      if (m.name() != null) {
        nameWidth = Math.max(nameWidth, m.name().length());
      }
    }
    int idxWidth = Math.max(1, Integer.toString(metrics.size()).length());
    String idxHead = indexed ? "%-" + idxWidth + "s  " : "%s";
    String idxRow = indexed ? "%-" + idxWidth + "d  " : "%s";
    String headFmt = idxHead + "%-8s  %-" + nameWidth + "s  %-34s  %s%n";
    String rowFmt = idxRow + "%-8s  %-" + nameWidth + "s  %-34s  %s%n";
    System.out.printf(headFmt, indexed ? "#" : "", "ID", "NAME", "HASH", "LOC");
    int i = 1;
    for (AppMetric m : metrics) {
      System.out.printf(rowFmt, indexed ? i++ : "", m.id(), m.name(),
          m.key() == null ? "" : m.key(), m.loc() == null ? "" : m.loc());
    }
  }

  private int interactiveLoop(List<AppMetric> metrics) {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (true) {
      System.out.println();
      System.out.println("Metrics " + metrics.size() + " — " + app);
      System.out.println();
      printTable(metrics, true);
      System.out.print("Select " + AnsiColor.hot("1-" + metrics.size(), "")
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
      Integer idx = parseIndex(line, metrics.size());
      if (idx == null) {
        System.out.println("Enter a number 1-" + metrics.size() + " or 'q' to quit.");
        continue;
      }
      System.out.println();
      MetricCommand.printMetric(metrics.get(idx - 1));
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
