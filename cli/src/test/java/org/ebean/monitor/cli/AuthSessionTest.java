package org.ebean.monitor.cli;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Function;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionTest {

  private static AuthConfig configured() {
    var p = new Properties();
    p.setProperty("auth-domain", "https://app.example.com");
    p.setProperty("auth-client-id", "client-abc");
    return new AuthConfig(p);
  }

  private static TokenStore store(Path dir) {
    return new TokenStore(dir.resolve("token.json"));
  }

  @Test
  void noToken_yieldsEmpty(@TempDir Path dir) {
    var session = new AuthSession(store(dir), configured(), () -> 1_000L, failFactory());
    assertThat(session.bearerToken()).isEmpty();
  }

  @Test
  void validToken_returnedWithoutRefresh(@TempDir Path dir) {
    var store = store(dir);
    store.save(new TokenData("access-valid", "refresh", null, "Bearer", 5_000L, 0L));

    var session = new AuthSession(store, configured(), () -> 1_000L, failFactory());
    assertThat(session.bearerToken()).contains("access-valid");
  }

  @Test
  void expiredToken_refreshed(@TempDir Path dir) {
    var store = store(dir);
    store.save(new TokenData("access-old", "refresh-old", null, "Bearer", 1_000L, 0L));

    Function<AuthConfig, CognitoOidc> factory = cfg ->
        fake(() -> new OidcTokens("id-new", "access-new", "refresh-new", 3_600L, "Bearer"));

    var session = new AuthSession(store, configured(), () -> 2_000L, factory);
    assertThat(session.bearerToken()).contains("access-new");

    var saved = store.load().orElseThrow();
    assertThat(saved.accessToken()).isEqualTo("access-new");
    assertThat(saved.refreshToken()).isEqualTo("refresh-new");
    assertThat(saved.expiresAt()).isEqualTo(2_000L + 3_600L);
  }

  @Test
  void expiredToken_refreshKeepsOldRefreshTokenWhenNull(@TempDir Path dir) {
    var store = store(dir);
    store.save(new TokenData("access-old", "refresh-old", null, "Bearer", 1_000L, 0L));

    Function<AuthConfig, CognitoOidc> factory = cfg ->
        fake(() -> new OidcTokens(null, "access-new", null, 3_600L, "Bearer"));

    var session = new AuthSession(store, configured(), () -> 2_000L, factory);
    assertThat(session.bearerToken()).contains("access-new");
    assertThat(store.load().orElseThrow().refreshToken()).isEqualTo("refresh-old");
  }

  @Test
  void expiredToken_refreshFailure_fallsBackToCached(@TempDir Path dir) {
    var store = store(dir);
    store.save(new TokenData("access-old", "refresh-old", null, "Bearer", 1_000L, 0L));

    Function<AuthConfig, CognitoOidc> factory = cfg -> fake(() -> {
      throw new RuntimeException("network down");
    });

    var session = new AuthSession(store, configured(), () -> 2_000L, factory);
    assertThat(session.bearerToken()).contains("access-old");
  }

  @Test
  void expiredToken_noRefreshToken_returnsCached(@TempDir Path dir) {
    var store = store(dir);
    store.save(new TokenData("access-old", null, null, "Bearer", 1_000L, 0L));

    var session = new AuthSession(store, configured(), () -> 2_000L, failFactory());
    assertThat(session.bearerToken()).contains("access-old");
  }

  private static Function<AuthConfig, CognitoOidc> failFactory() {
    return cfg -> {
      throw new AssertionError("CognitoOidc should not be built");
    };
  }

  private static CognitoOidc fake(java.util.function.Supplier<OidcTokens> onRefresh) {
    return new CognitoOidc() {
      @Override public String loginUrl(String nonce, String state) { throw new UnsupportedOperationException(); }
      @Override public String loginUrl(String nonce, String state, String codeChallenge) { throw new UnsupportedOperationException(); }
      @Override public OidcTokens obtainTokens(String code) { throw new UnsupportedOperationException(); }
      @Override public OidcTokens obtainTokens(String code, String codeVerifier) { throw new UnsupportedOperationException(); }
      @Override public OidcTokens refreshAccessToken(String refreshToken) { return onRefresh.get(); }
    };
  }
}
