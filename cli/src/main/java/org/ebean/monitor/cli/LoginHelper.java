package org.ebean.monitor.cli;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.core.pkce.Pkce;
import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Shared login logic used by both {@link LoginCommand} and {@link SetupCommand}.
 * Supports two flows:
 * <ul>
 *   <li>PKCE (default) — opens a browser, captures the authorization-code redirect
 *       on a loopback receiver; tries each configured port in order.</li>
 *   <li>Device code (--device) — prints a short code for the user to enter at a
 *       hosted URL; no local port needed.</li>
 * </ul>
 */
final class LoginHelper {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final @Nullable String profile;
  private final long timeoutSeconds;
  private final boolean device;

  LoginHelper(@Nullable String profile) {
    this(profile, 300, false);
  }

  LoginHelper(@Nullable String profile, long timeoutSeconds, boolean device) {
    this.profile = profile;
    this.timeoutSeconds = timeoutSeconds;
    this.device = device;
  }

  int login() {
    AuthConfig auth = new AuthConfig(profile);
    auth.requireConfigured();
    return device ? loginDevice(auth) : loginPkce(auth);
  }

  private int loginPkce(AuthConfig auth) {
    Pkce pkce = Pkce.generate();
    String state = randomToken();
    String nonce = randomToken();

    try (LoopbackReceiver receiver = LoopbackReceiver.startFirst(auth.redirectPorts())) {
      int port = receiver.port();
      CognitoOidc oidc = auth.cognitoOidc(port);
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

      return saveTokens(auth.cognitoOidc(port).obtainTokens(cb.code(), pkce.verifier()));
    }
  }

  private int loginDevice(AuthConfig auth) {
    return new DeviceCodeFlow(auth, profile).login(timeoutSeconds);
  }

  int saveTokens(OidcTokens tokens) {
    long now = Instant.now().getEpochSecond();
    TokenData data = new TokenData(
        tokens.accessToken(),
        tokens.refreshToken(),
        tokens.idToken(),
        tokens.tokenType(),
        now + tokens.expiresIn(),
        now);

    TokenStore store = TokenStore.forProfile(profile);
    store.save(data);

    System.out.println("Logged in. Token cached at " + store.file());
    System.out.println("Access token expires at " + Instant.ofEpochSecond(data.expiresAt()));
    return 0;
  }

  private static String randomToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
