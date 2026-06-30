package org.ebean.monitor.cli;

import io.avaje.http.client.HttpClient;
import io.avaje.http.client.HttpException;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.jsonb.Json;
import io.avaje.jsonb.Jsonb;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.http.HttpResponse;
import java.util.concurrent.Callable;

/**
 * One-command bootstrap: sets the server URL, fetches {@code /api/cli-config} to
 * obtain the OAuth2 client settings, writes them all to the local config, then
 * launches the browser login flow so the user is fully set up in one step.
 *
 * <pre>
 *   insight setup https://ebean-insight.example.com
 *   insight setup https://ebean-insight.example.com --profile prod
 * </pre>
 */
@Command(name = "setup", mixinStandardHelpOptions = true,
    description = "Bootstrap the CLI from a server URL: sets url, fetches auth config, and logs in.",
    footerHeading = "%nExamples:%n",
    footer = {
        "  insight setup https://ebean-insight.example.com",
        "  insight setup https://ebean-insight.example.com --profile prod",
        "  insight setup https://ebean-insight.example.com --no-login",
    })
final class SetupCommand implements Callable<Integer> {

  @Json
  record CliConfigResponse(
      @Nullable String authDomain,
      @Nullable String authClientId,
      @Nullable String authScope) {
  }

  @Parameters(index = "0", paramLabel = "URL",
      description = "Base URL of the insight server (e.g. https://central-insight.example.com).")
  String url;

  @Option(names = "--profile", paramLabel = "NAME",
      description = "Write settings into a named profile instead of the base config.")
  @Nullable String profile;

  @Option(names = "--no-login",
      description = "Write config only; skip the browser login step.")
  boolean noLogin;

  @Override
  public Integer call() {
    String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

    // 1. Fetch auth config from the server (no credentials required)
    CliConfigResponse remote = fetchCliConfig(baseUrl);

    // 2. Write all settings to config (profile or base)
    InsightConfig config = new InsightConfig();
    write(config, "url", baseUrl);
    if (remote.authDomain() != null) {
      write(config, "auth-domain", remote.authDomain());
    }
    if (remote.authClientId() != null) {
      write(config, "auth-client-id", remote.authClientId());
    }
    String scope = remote.authScope() != null ? remote.authScope() : "openid";
    write(config, "auth-scope", scope);

    String profileLabel = profile != null ? " [" + profile + "]" : "";
    System.out.println("Configuration written" + profileLabel + ":");
    System.out.println("  url          = " + baseUrl);
    if (remote.authDomain() != null)   System.out.println("  auth-domain  = " + remote.authDomain());
    if (remote.authClientId() != null) System.out.println("  auth-client-id = " + remote.authClientId());
    System.out.println("  auth-scope   = " + scope);

    // 3. Optionally activate the profile
    if (profile != null) {
      System.out.println();
      System.out.println("To activate: insight config use " + profile);
    }

    if (noLogin) {
      System.out.println();
      System.out.println("Run `insight login" + (profile != null ? " --profile " + profile : "") + "` to authenticate.");
      return 0;
    }

    // 4. Run the login flow
    System.out.println();
    AuthConfig authConfig = new AuthConfig(profile);
    if (!authConfig.isConfigured()) {
      System.out.println("Auth not configured on this server — no login required.");
      return 0;
    }
    return new LoginHelper(profile).login();
  }

  private CliConfigResponse fetchCliConfig(String baseUrl) {
    HttpClient client = HttpClient.builder()
        .baseUrl(baseUrl)
        .bodyAdapter(new JsonbBodyAdapter(Jsonb.builder().build()))
        .build();
    try {
      HttpResponse<CliConfigResponse> res = client.request()
          .path("api/cli-config")
          .GET().as(CliConfigResponse.class);
      return res.body();
    } catch (HttpException e) {
      throw new CliException("Failed to fetch CLI config from " + baseUrl + "/api/cli-config: HTTP " + e.statusCode());
    } catch (Exception e) {
      throw new CliException("Failed to reach " + baseUrl + "/api/cli-config: " + e.getMessage());
    }
  }

  private void write(InsightConfig config, String key, String value) {
    if (profile != null && !profile.isBlank()) {
      config.setInProfile(profile.trim(), key, value);
    } else {
      config.set(key, value);
    }
  }
}
