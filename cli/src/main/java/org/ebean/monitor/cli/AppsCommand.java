package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.App;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** List known applications. */
@Command(name = "apps", mixinStandardHelpOptions = true, description = "List known applications.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight apps",
        "  insight apps --active-within-hours 24    # only recently active apps",
        "  insight apps -o json"
    })
final class AppsCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Option(names = "--active-within-minutes", description = "Only apps active within the last N minutes.")
  @Nullable Long activeWithinMinutes;

  @Option(names = "--active-within-hours", description = "Only apps active within the last N hours.")
  @Nullable Long activeWithinHours;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<App> apps = insight.apps.listApps(activeWithinMinutes, activeWithinHours);
      if (out.json()) {
        out.printJsonList(App.class, apps);
        return 0;
      }
      if (apps.isEmpty()) {
        System.out.println("No apps found.");
        return 0;
      }
      for (App app : apps) {
        System.out.printf("%-8d  %s%n", app.id(), app.name());
      }
      return 0;
    }
  }
}
