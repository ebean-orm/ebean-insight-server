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
 * Shared login logic (OAuth2 PKCE flow) used by both {@link LoginCommand} and
 * {@link SetupCommand}.
 */
final class LoginHelper {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final @Nullable String profile;
  private final long timeoutSeconds;

  LoginHelper(@Nullable String profile) {
    this(profile, 300);
  }

  LoginHelper(@Nullable String profile, long timeoutSeconds) {
    this.profile = profile;
    this.timeoutSeconds = timeoutSeconds;
  }

  int login() {
    AuthConfig auth = new AuthConfig(profile);
    auth.requireConfigured();

    Pkce pkce = Pkce.generate();
    String state = randomToken();
    String nonce = randomToken();

    try (LoopbackReceiver receiver = LoopbackReceiver.start(0)) {
      CognitoOidc oidc = auth.cognitoOidc(receiver.port());
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

      TokenStore store = TokenStore.forProfile(profile);
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
