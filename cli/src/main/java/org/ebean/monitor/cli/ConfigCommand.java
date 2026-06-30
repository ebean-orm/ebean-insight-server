package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Manage persisted CLI settings in {@code ~/.insight/config.properties} so that
 * deployment-specific values (namespace, service, …) don't need to be passed on
 * every command.
 *
 * <p>Named profiles let you maintain separate connection settings for different
 * targets (e.g. prod vs test) and switch between them with
 * {@code insight config use <name>}. Profile files live in
 * {@code ~/.insight/profiles/<name>.properties}; the active profile's settings
 * are merged over the base config at runtime. Tokens are stored per-profile as
 * {@code ~/.insight/token-<name>.json}.
 */
@Command(name = "config", mixinStandardHelpOptions = true,
    description = "Manage persisted CLI settings (~/.insight/config.properties).",
    footerHeading = "%nSetup examples:%n",
    footer = {
        "  # URL + OAuth2 (most common):",
        "  insight config set url https://central-insight.example.com",
        "  insight config set auth-client-id <cognito-client-id>",
        "  insight config set auth-domain https://my-app.auth.<region>.amazoncognito.com",
        "  insight config set auth-scope openid",
        "  insight login                    # authenticate once (opens browser)",
        "",
        "  # kubectl port-forward:",
        "  insight config set namespace <namespace>",
        "  insight config set service ebean-insight",
        "  insight config set context <kube-context>  # optional",
        "",
        "  # Profiles — maintain separate settings per target:",
        "  insight config set --profile prod url https://prod.example.com",
        "  insight config set --profile prod auth-client-id <id>",
        "  insight config set --profile test url https://test.example.com",
        "  insight config set --profile test auth-client-id <id>",
        "  insight config use prod          # activate prod profile",
        "  insight config use --none        # back to base config",
        "  insight config profiles          # list available profiles",
        "  insight config which             # show active profile",
        "",
        "  # Other:",
        "  insight config set output json   # default all commands to JSON",
        "  insight config list              # show all effective (merged) settings",
        "  insight config path              # where settings are stored",
        "",
        "Keys:",
        "  url, namespace, service, target-port, local-port, context, ready-timeout, output, env, app",
        "  auth-domain, auth-user-pool-id, auth-client-id, auth-scope, auth-redirect-port",
    },
    subcommands = {
        ConfigCommand.Set.class,
        ConfigCommand.Get.class,
        ConfigCommand.Unset.class,
        ConfigCommand.List.class,
        ConfigCommand.Path.class,
        ConfigCommand.Use.class,
        ConfigCommand.Profiles.class,
        ConfigCommand.Which.class
    })
final class ConfigCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
  }

  private static String render(String key, String value) {
    return key + " = " + value;
  }

  @Command(name = "set", mixinStandardHelpOptions = true,
      description = "Set a config value. Without --profile writes to the base config; with --profile writes to that profile.")
  static final class Set implements Callable<Integer> {

    @Option(names = "--profile", paramLabel = "NAME",
        description = "Write to a named profile instead of the base config.")
    String profile;

    @Parameters(index = "0", paramLabel = "KEY",
        description = "One of: ${COMPLETION-CANDIDATES}", completionCandidates = Keys.class)
    String key;

    @Parameters(index = "1", paramLabel = "VALUE", description = "Value to store.")
    String value;

    @Override
    public Integer call() {
      var config = new InsightConfig();
      if (profile != null && !profile.isBlank()) {
        config.setInProfile(profile.trim(), key, value);
        System.out.println("[" + profile.trim() + "] " + render(key, value));
      } else {
        config.set(key, value);
        System.out.println(render(key, value));
      }
      return 0;
    }
  }

  @Command(name = "get", mixinStandardHelpOptions = true, description = "Print a config value (effective / merged).")
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

  @Command(name = "unset", mixinStandardHelpOptions = true,
      description = "Remove a config value. Without --profile removes from the base config; with --profile removes from that profile.")
  static final class Unset implements Callable<Integer> {

    @Option(names = "--profile", paramLabel = "NAME",
        description = "Remove from a named profile instead of the base config.")
    String profile;

    @Parameters(index = "0", paramLabel = "KEY",
        description = "One of: ${COMPLETION-CANDIDATES}", completionCandidates = Keys.class)
    String key;

    @Override
    public Integer call() {
      var config = new InsightConfig();
      boolean removed = (profile != null && !profile.isBlank())
          ? config.unsetInProfile(profile.trim(), key)
          : config.unset(key);
      String prefix = (profile != null && !profile.isBlank()) ? "[" + profile.trim() + "] " : "";
      System.out.println(removed ? prefix + "unset " + key : prefix + key + " was not set");
      return 0;
    }
  }

  @Command(name = "list", aliases = {"ls"}, mixinStandardHelpOptions = true,
      description = "List all effective (merged) settings.")
  static final class List implements Callable<Integer> {

    @Override
    public Integer call() {
      var config = new InsightConfig();
      String active = config.activeProfile();
      if (active != null) {
        System.out.println("# active profile: " + active);
      }
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

  @Command(name = "use", mixinStandardHelpOptions = true,
      description = "Activate a named profile, or deactivate with --none.",
      footerHeading = "%nExamples:%n",
      footer = {
          "  insight config use prod",
          "  insight config use test",
          "  insight config use --none   # go back to base config"
      })
  static final class Use implements Callable<Integer> {

    @Option(names = "--none", description = "Deactivate the current profile.")
    boolean none;

    @Parameters(index = "0", paramLabel = "NAME", description = "Profile name to activate.",
        arity = "0..1")
    String name;

    @Override
    public Integer call() {
      var config = new InsightConfig();
      if (none) {
        config.clearProfile();
        System.out.println("Profile deactivated. Using base config.");
        return 0;
      }
      if (name == null || name.isBlank()) {
        String active = config.activeProfile();
        System.out.println(active != null ? "Active profile: " + active : "No profile active.");
        return 0;
      }
      config.useProfile(name.trim());
      System.out.println("Active profile: " + name.trim());
      System.out.println("Token file:     " + TokenStore.tokenFile(name.trim()));
      System.out.println("Run `insight login` to authenticate for this profile.");
      return 0;
    }
  }

  @Command(name = "profiles", mixinStandardHelpOptions = true,
      description = "List available profiles (~/.insight/profiles/).")
  static final class Profiles implements Callable<Integer> {

    @Override
    public Integer call() {
      var config = new InsightConfig();
      java.util.List<String> profiles = config.listProfiles();
      String active = config.activeProfile();
      if (profiles.isEmpty()) {
        System.out.println("No profiles found in " + InsightConfig.profilesDir());
        System.out.println("Create one with: insight config set --profile <name> url <url>");
        return 0;
      }
      for (String p : profiles) {
        System.out.println(p.equals(active) ? "* " + p : "  " + p);
      }
      return 0;
    }
  }

  @Command(name = "which", mixinStandardHelpOptions = true,
      description = "Show the active profile name.")
  static final class Which implements Callable<Integer> {

    @Override
    public Integer call() {
      String active = new InsightConfig().activeProfile();
      if (active == null) {
        System.out.println("(no profile active — using base config)");
        return 1;
      }
      System.out.println(active);
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
