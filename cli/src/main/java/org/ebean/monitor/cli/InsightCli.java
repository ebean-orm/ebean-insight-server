package org.ebean.monitor.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root command for the ebean-insight CLI.
 *
 * <p>Connects to the server either via a static {@code --url} or, by default, a
 * supervised {@code kubectl port-forward} (reusing cluster RBAC for auth).
 */
@Command(
    name = "insight",
    mixinStandardHelpOptions = true,
    version = "ebean-insight-cli",
    description = "Inspect ebean-insight metrics and captured query plans.",
    subcommands = {
        PlansCommand.class,
        PlanCommand.class,
        CaptureCommand.class,
        AppsCommand.class,
        EnvsCommand.class,
        ForwardCommand.class
    })
public final class InsightCli implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new InsightCli()).execute(args);
    System.exit(exitCode);
  }
}
