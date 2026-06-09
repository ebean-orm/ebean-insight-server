package org.ebean.monitor.cli;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Callable;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.core.pkce.Pkce;
import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
        "  insight config set auth-domain <hosted-ui-domain>   # or auth-user-pool-id <id>",
        "  insight config set auth-client-id <public-client-id>",
        "  insight config set auth-redirect-port <port>        # optional, default 9876"
    })
final class LoginCommand implements Callable<Integer> {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Option(names = "--timeout-seconds",
      description = "Seconds to wait for the browser login to complete (default: 300).")
  long timeoutSeconds = 300;

  @Override
  public Integer call() {
    AuthConfig auth = new AuthConfig();
    auth.requireConfigured();

    Pkce pkce = Pkce.generate();
    String state = randomToken();
    String nonce = randomToken();

    try (LoopbackReceiver receiver = LoopbackReceiver.start(auth.redirectPort())) {
      CognitoOidc oidc = auth.cognitoOidc();
      String loginUrl = oidc.loginUrl(nonce, state, pkce.challenge());

      if (BrowserLauncher.open(loginUrl)) {
        System.out.println("Opening your browser to complete login…");
      } else {
        System.out.println("Open this URL in your browser to complete login:");
        System.out.println("  " + loginUrl);
      }

      LoopbackReceiver.CallbackResult cb = receiver.await(Duration.ofSeconds(timeoutSeconds));
      if (cb == null) {
        throw new CliException("Timed out waiting for the browser login to complete.");
      }
      if (cb.error() != null) {
        String detail = cb.errorDescription() != null ? " - " + cb.errorDescription() : "";
        throw new CliException("Login failed: " + cb.error() + detail);
      }
      if (cb.code() == null) {
        throw new CliException("Login failed: no authorization code returned.");
      }
      if (!state.equals(cb.state())) {
        throw new CliException("Login failed: state mismatch (possible CSRF); please retry.");
      }

      long now = Instant.now().getEpochSecond();
      OidcTokens tokens = oidc.obtainTokens(cb.code(), pkce.verifier());

      TokenData data = new TokenData(
          tokens.accessToken(),
          tokens.refreshToken(),
          tokens.idToken(),
          tokens.tokenType(),
          now + tokens.expiresIn(),
          now);

      TokenStore store = new TokenStore();
      store.save(data);

      System.out.println("Logged in. Token cached at " + store.file());
      System.out.println("Access token expires at " + Instant.ofEpochSecond(data.expiresAt()));
      return 0;
    }
  }

  private static String randomToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
