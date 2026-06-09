package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Manage persisted CLI settings in {@code ~/.insight/config.properties} so that
 * deployment-specific values (namespace, service, …) don't need to be passed on
 * every command.
 */
@Command(name = "config", mixinStandardHelpOptions = true,
    description = "Manage persisted CLI settings (~/.insight/config.properties).",
    footerHeading = "%nKeys:%n",
    footer = {
        "  url, namespace, service, target-port, local-port, context, ready-timeout, insight-key, output",
        "  auth-domain, auth-user-pool-id, auth-client-id, auth-scope, auth-redirect-port",
        "",
        "Examples:",
        "  insight config set namespace dev",
        "  insight config set service ebean-insight",
        "  insight config set output json      # default all commands to JSON",
        "  insight config list",
        "  insight config path                 # where settings are stored"
    },
    subcommands = {
        ConfigCommand.Set.class,
        ConfigCommand.Get.class,
        ConfigCommand.Unset.class,
        ConfigCommand.List.class,
        ConfigCommand.Path.class
    })
final class ConfigCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  private static String render(String key, String value) {
    return key + " = " + ("insight-key".equals(key) ? mask(value) : value);
  }

  private static String mask(String value) {
    if (value.length() <= 4) {
      return "****";
    }
    return "****" + value.substring(value.length() - 4);
  }

  @Command(name = "set", mixinStandardHelpOptions = true, description = "Set a config value.")
  static final class Set implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "KEY",
        description = "One of: ${COMPLETION-CANDIDATES}", completionCandidates = Keys.class)
    String key;

    @Parameters(index = "1", paramLabel = "VALUE", description = "Value to store.")
    String value;

    @Override
    public Integer call() {
      new InsightConfig().set(key, value);
      System.out.println(render(key, value));
      return 0;
    }
  }

  @Command(name = "get", mixinStandardHelpOptions = true, description = "Print a config value.")
  static final class Get implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "KEY",
        description = "One of: ${COMPLETION-CANDIDATES}", completionCandidates = Keys.class)
    String key;

    @Override
    public Integer call() {
      String value = new InsightConfig().get(key);
      if (value == null) {
        System.out.println("(unset)");
        return 1;
      }
      System.out.println(value);
      return 0;
    }
  }

  @Command(name = "unset", mixinStandardHelpOptions = true, description = "Remove a config value.")
  static final class Unset implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "KEY",
        description = "One of: ${COMPLETION-CANDIDATES}", completionCandidates = Keys.class)
    String key;

    @Override
    public Integer call() {
      boolean removed = new InsightConfig().unset(key);
      System.out.println(removed ? "unset " + key : key + " was not set");
      return 0;
    }
  }

  @Command(name = "list", aliases = {"ls"}, mixinStandardHelpOptions = true,
      description = "List all persisted settings.")
  static final class List implements Callable<Integer> {

    @Override
    public Integer call() {
      var config = new InsightConfig();
      var all = config.all();
      if (all.isEmpty()) {
        System.out.println("No settings in " + config.file());
        return 0;
      }
      all.forEach((k, v) -> System.out.println(render(k, v)));
      return 0;
    }
  }

  @Command(name = "path", mixinStandardHelpOptions = true,
      description = "Print the config file path.")
  static final class Path implements Callable<Integer> {

    @Override
    public Integer call() {
      System.out.println(InsightConfig.defaultFile());
      return 0;
    }
  }

  /** Completion candidates for the KEY parameter. */
  static final class Keys extends java.util.ArrayList<String> {
    Keys() {
      super(InsightConfig.KEYS);
    }
  }
}
