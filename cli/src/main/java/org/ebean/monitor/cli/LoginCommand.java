package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.jspecify.annotations.Nullable;

/**
 * Authenticate via the Cognito Hosted UI using the OAuth2 Authorization-Code
 * flow with PKCE (public client), caching the resulting tokens for subsequent
 * commands.
 *
 * <p>Starts a loopback receiver, opens the browser, captures the redirected
 * authorization code, exchanges it for tokens and writes them to
 * {@code ~/.insight/token.json}.
 */
@Command(name = "login", mixinStandardHelpOptions = true,
    description = "Log in via Cognito (OAuth2 + PKCE) and cache the bearer token.",
    footerHeading = "%nSetup (one-time):%n",
    footer = {
        "  insight setup <url>                          # bootstrap url + auth + login in one step",
        "  insight config set auth-domain <hosted-ui-domain>",
        "  insight config set auth-client-id <public-client-id>",
        "  insight config set auth-redirect-port <port> # optional, default 9876"
    })
final class LoginCommand implements Callable<Integer> {

  @Option(names = "--timeout-seconds",
      description = "Seconds to wait for the browser login to complete (default: 300).")
  long timeoutSeconds = 300;

  @Option(names = "--profile", paramLabel = "NAME",
      description = "Log in for a named profile instead of the active one.")
  @Nullable String profile;

  @Override
  public Integer call() {
    return new LoginHelper(profile, timeoutSeconds).login();
  }
}
