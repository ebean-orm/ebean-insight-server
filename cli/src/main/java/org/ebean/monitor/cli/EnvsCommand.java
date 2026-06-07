package org.ebean.monitor.cli;

import java.util.List;
import java.util.concurrent.Callable;

import org.ebean.monitor.v1.model.Env;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** List known environments. */
@Command(name = "envs", mixinStandardHelpOptions = true, description = "List known environments.")
final class EnvsCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();

  @Override
  public Integer call() {
    try (Insight insight = Insight.open(conn)) {
      List<Env> envs = insight.envs.listEnvs();
      if (envs.isEmpty()) {
        System.out.println("No envs found.");
        return 0;
      }
      for (Env env : envs) {
        System.out.println(env.name());
      }
      return 0;
    }
  }
}
