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
        ChangesCommand.class,
        ChangeCommand.class,
        PendingCommand.class,
        CaptureCommand.class,
        TopCommand.class,
        MetricsCommand.class,
        MetricCommand.class,
        TrendCommand.class,
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
        "  # Common use cases:",
        "  insight apps                                # what's reporting metrics",
        "  insight top --by total                      # most expensive queries",
        "  insight missing-plans --app <app>           # expensive queries, no plan",
        "  insight capture <app> --env <env> <hash>    # request a plan capture",
        "  insight pending --app <app>                 # captures awaiting collection",
        "  insight plans --app <app> --env <env>       # recently captured plans",
        "  insight plan <id>                           # SQL + binds + EXPLAIN",
        "  insight changes --change-type CHANGED       # query plans whose shape changed",
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
    // Captured query plans (SQL + binds + EXPLAIN) can exceed avaje-jsonb's default
    // 50k char string-buffer limit; raise it before any HTTP/JSON parsing.
    System.setProperty("jsonb.parserMaxStringBuffer", "2000000");
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
