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
    versionProvider = VersionProvider.class,
    description = "Inspect ebean-insight metrics and captured query plans.",
    subcommands = {
        PlansCommand.class,
        PlanCommand.class,
        CaptureCommand.class,
        TopCommand.class,
        MetricsCommand.class,
        MissingPlansCommand.class,
        AppsCommand.class,
        EnvsCommand.class,
        ForwardCommand.class,
        ConfigCommand.class,
        LoginCommand.class,
        LogoutCommand.class,
        WhoamiCommand.class
    },
    footerHeading = "%nGetting started:%n",
    footer = {
        "  # One-time: persist the cluster target so you can omit --namespace/--service",
        "  insight config set namespace dev",
        "  insight config set service ebean-insight",
        "  insight config set context <kube-context>    # optional",
        "",
        "  # Optional: hold one shared port-forward open; other commands reuse it",
        "  insight forward &",
        "",
        "  # Find the most expensive queries, then inspect or capture a plan",
        "  insight top --by total",
        "  insight capture <app> <hash> --env <env>",
        "  insight plans --app <app> --env <env>",
        "  insight plan <id>",
        "",
        "  Connect without kube access via a direct URL:  --url http://host:8091",
        "  JSON for scripting:  add -o json  (or persist: insight config set output json)"
    })
public final class InsightCli implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new InsightCli())
        .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
          if (ex instanceof CliException) {
            commandLine.getErr().println("error: " + ex.getMessage());
            return 2;
          }
          throw ex;
        })
        .execute(args);
    System.exit(exitCode);
  }
}
