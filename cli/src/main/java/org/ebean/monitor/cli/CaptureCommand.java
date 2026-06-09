package org.ebean.monitor.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import io.avaje.http.client.HttpException;
import org.ebean.monitor.v1.model.PendingResponse;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Request fresh query plan captures for one or more metric hashes. */
@Command(name = "capture", mixinStandardHelpOptions = true,
    description = "Request a fresh query plan capture for one or more hashes.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  # find plan-capable hashes lacking a recent plan, then capture one:",
        "  insight missing-plans --app myapp",
        "  insight capture myapp a2e2082d... --env test",
        "  # capture several at once (space or comma separated):",
        "  insight capture myapp hashA hashB hashC --env test",
        "  insight capture myapp hashA,hashB,hashC --env test",
        "  # flag forms (alternative to positionals):",
        "  insight capture --app myapp --hash hashA --hash hashB --env test",
        "  # pipe hashes straight from missing-plans:",
        "  insight missing-plans --app myapp -o json | jq -r '.[].key' \\",
        "    | insight capture myapp --stdin --env test",
        "  # the plans appear after the next executions (a short bind-collection window):",
        "  insight plans --app myapp --env test"
    })
final class CaptureCommand implements Callable<Integer> {

  @Mixin ConnectionOptions conn = new ConnectionOptions();
  @Mixin OutputOptions out = new OutputOptions();

  @Parameters(index = "0", arity = "0..1", paramLabel = "<app>",
      description = "Application name (or use --app / the persisted 'app' config).")
  @Nullable String app;

  @Parameters(index = "1..*", arity = "0..*", paramLabel = "<hash>",
      description = "One or more query plan hashes (space or comma separated; or use --hash / --stdin).")
  List<String> hashes = new ArrayList<>();

  @Option(names = "--app",
      description = "Application name (alternative to the positional <app>).")
  @Nullable String appOption;

  @Option(names = "--hash",
      description = "Query plan hash to capture (repeatable; comma/space separated also accepted).")
  List<String> hashOptions = new ArrayList<>();

  @Option(names = "--stdin",
      description = "Also read hashes (whitespace/comma/newline separated) from standard input.")
  boolean stdin;

  @Option(names = "--env", description = "Environment name.")
  @Nullable String env;

  @Override
  public Integer call() {
    if (env == null) {
      env = ConfigDefaults.envOrNull();
    }
    final Set<String> targets = new LinkedHashSet<>();
    String targetApp = appOption;
    if (targetApp == null) {
      targetApp = app;
    } else if (app != null) {
      // --app was supplied, so the leading positional is actually a hash
      addTokens(targets, app);
    }
    if (targetApp == null || targetApp.isBlank()) {
      targetApp = ConfigDefaults.appOrNull();
    }
    if (targetApp == null || targetApp.isBlank()) {
      throw new CliException(
          "No application supplied. Pass <app>, --app, or set 'insight config set app <name>'.");
    }
    for (String h : hashes) {
      addTokens(targets, h);
    }
    for (String h : hashOptions) {
      addTokens(targets, h);
    }
    if (stdin) {
      targets.addAll(readStdinHashes());
    }
    if (targets.isEmpty()) {
      throw new CliException("No hashes supplied. Pass hash arguments, --hash and/or --stdin.");
    }
    try (Insight insight = Insight.open(conn)) {
      final List<CaptureResult> results = new ArrayList<>(targets.size());
      boolean anyError = false;
      for (String hash : targets) {
        try {
          PendingResponse pending = insight.plans.requestPlanCapture(targetApp, hash, env);
          results.add(new CaptureResult(hash, pending.pending(), null));
        } catch (HttpException e) {
          anyError = true;
          results.add(new CaptureResult(hash, null, "HTTP " + e.statusCode()));
        }
      }
      if (out.json()) {
        out.printJsonList(CaptureResult.class, results);
        return anyError ? 1 : 0;
      }
      for (CaptureResult r : results) {
        if (r.error() == null) {
          System.out.printf("%-34s requested (pending=%d)%n", r.hash(), r.pending());
        } else {
          System.out.printf("%-34s FAILED (%s)%n", r.hash(), r.error());
        }
      }
      if (targets.size() > 1) {
        final long ok = results.stream().filter(r -> r.error() == null).count();
        System.out.printf("%nRequested %d of %d captures.%n", ok, results.size());
      }
      return anyError ? 1 : 0;
    }
  }

  private static List<String> readStdinHashes() {
    final List<String> result = new ArrayList<>();
    try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        addTokens(result, line);
      }
    } catch (java.io.IOException e) {
      throw new CliException("Failed reading hashes from stdin: " + e.getMessage());
    }
    return result;
  }

  /** Split raw input on commas and/or whitespace, adding each non-blank hash. */
  static void addTokens(Collection<String> target, String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    for (String token : raw.trim().split("[\\s,]+")) {
      if (!token.isBlank()) {
        target.add(token.trim());
      }
    }
  }
}
