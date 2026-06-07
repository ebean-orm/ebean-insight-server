package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.PendingResponse;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Request a fresh query plan capture for a metric hash. */
@Command(name = "capture", mixinStandardHelpOptions = true, description = "Request a fresh query plan capture.")
final class CaptureCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", description = "The application name.")
  String app;

  @Parameters(index = "1", description = "The query plan hash.")
  String hash;

  @Option(names = "--env", description = "Environment name.")
  @Nullable String env;

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      PendingResponse pending = insight.plans.requestPlanCapture(app, hash, env);
      if (out.json()) {
        out.printJson(PendingResponse.class, pending);
        return 0;
      }
      System.out.println(pending);
      return 0;
    }
  }
}
