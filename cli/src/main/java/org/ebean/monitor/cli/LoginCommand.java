package org.ebean.monitor.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.jspecify.annotations.Nullable;

/**
 * Authenticate via the OAuth2 Authorization-Code flow with PKCE (default) or
 * Device Authorization Grant (--device), caching the resulting tokens for
 * subsequent commands.
 *
 * <p>PKCE flow: starts a loopback receiver, opens the browser, captures the
 * redirected authorization code, exchanges it for tokens and writes them to
 * {@code ~/.insight/token.json}.
 *
 * <p>Device flow: prints a short code for the user to enter at the auth server's
 * activation URL — no local port needed, works in headless / SSH environments.
 */
@Command(name = "login", mixinStandardHelpOptions = true,
    description = "Log in via OAuth2 and cache the bearer token.",
    footerHeading = "%nSetup (one-time):%n",
    footer = {
        "  insight setup <url>                          # bootstrap url + auth + login in one step",
        "  insight config set auth-domain <hosted-ui-domain>",
        "  insight config set auth-client-id <public-client-id>",
        "  insight config set auth-redirect-ports <ports>  # optional, default 9876,9877,9878"
    })
final class LoginCommand implements Callable<Integer> {

  @Option(names = "--timeout-seconds",
      description = "Seconds to wait for the login to complete (default: 300).")
  long timeoutSeconds = 300;

  @Option(names = "--profile", paramLabel = "NAME",
      description = "Log in for a named profile instead of the active one.")
  @Nullable String profile;

  @Option(names = "--device",
      description = "Use device authorization flow (RFC 8628) instead of browser PKCE."
          + " No local port needed — useful in headless or SSH environments.")
  boolean device;

  @Override
  public Integer call() {
    return new LoginHelper(profile, timeoutSeconds, device).login();
  }
}
