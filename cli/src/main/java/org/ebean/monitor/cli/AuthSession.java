package org.ebean.monitor.cli;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.oidc.cognito.CognitoOidc;

/**
 * Provides the current bearer access token for outgoing requests, performing a
 * silent refresh (via the OAuth2 refresh token) when the cached access token has
 * expired.
 *
 * <p>Never throws: when no token is cached it yields {@link Optional#empty()};
 * when a refresh fails it falls back to the (expired) cached token so the server
 * returns a clear {@code 401} prompting the user to run {@code insight login}.
 */
final class AuthSession {

  private final TokenStore store;
  private final AuthConfig authConfig;
  private final LongSupplier clock;
  private final Function<AuthConfig, CognitoOidc> oidcFactory;

  AuthSession() {
    this(new TokenStore(), new AuthConfig());
  }

  AuthSession(TokenStore store, AuthConfig authConfig) {
    this(store, authConfig, () -> Instant.now().getEpochSecond(), AuthConfig::cognitoOidc);
  }

  AuthSession(TokenStore store, AuthConfig authConfig, LongSupplier clock,
      Function<AuthConfig, CognitoOidc> oidcFactory) {
    this.store = store;
    this.authConfig = authConfig;
    this.clock = clock;
    this.oidcFactory = oidcFactory;
  }

  /** The access token to send as {@code Authorization: Bearer}, refreshing if needed. */
  Optional<String> bearerToken() {
    Optional<TokenData> cached = store.load();
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    TokenData token = cached.get();
    long now = clock.getAsLong();
    if (!token.isExpired(now)) {
      return Optional.of(token.accessToken());
    }
    if (token.refreshToken() != null && authConfig.isConfigured()) {
      TokenData refreshed = tryRefresh(token, now);
      if (refreshed != null) {
        return Optional.of(refreshed.accessToken());
      }
    }
    // expired with no usable refresh path — send it anyway for a clear 401
    return Optional.of(token.accessToken());
  }

  private TokenData tryRefresh(TokenData token, long now) {
    try {
      OidcTokens tokens = oidcFactory.apply(authConfig).refreshAccessToken(token.refreshToken());
      String refreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : token.refreshToken();
      TokenData updated = new TokenData(
          tokens.accessToken(),
          refreshToken,
          tokens.idToken() != null ? tokens.idToken() : token.idToken(),
          tokens.tokenType() != null ? tokens.tokenType() : token.tokenType(),
          now + tokens.expiresIn(),
          now);
      store.save(updated);
      return updated;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
