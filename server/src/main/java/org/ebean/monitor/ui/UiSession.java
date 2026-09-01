package org.ebean.monitor.ui;

import io.avaje.oauth2.core.data.OidcTokens;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

record UiSession(
  String id,
  String accessToken,
  String refreshToken,
  String idToken,
  Instant accessTokenExpiresAt,
  Instant expiresAt,
  @Nullable String userSub) {

  UiSession(String id, String accessToken, String refreshToken, String idToken,
            Instant accessTokenExpiresAt, Instant expiresAt) {
    this(id, accessToken, refreshToken, idToken, accessTokenExpiresAt, expiresAt, null);
  }

  static UiSession of(String id, OidcTokens tokens, Instant now, Instant expiresAt) {
    long expiresIn = Math.max(0, tokens.expiresIn());
    return new UiSession(id, tokens.accessToken(), tokens.refreshToken(), tokens.idToken(),
      now.plusSeconds(expiresIn), expiresAt, null);
  }

  UiSession refreshed(OidcTokens tokens, Instant now) {
    String nextRefresh = tokens.refreshToken() == null ? refreshToken : tokens.refreshToken();
    return new UiSession(id, tokens.accessToken(), nextRefresh, tokens.idToken(),
      now.plusSeconds(Math.max(0, tokens.expiresIn())), expiresAt, userSub);
  }

  UiSession withUserSub(String sub) {
    return new UiSession(id, accessToken, refreshToken, idToken,
      accessTokenExpiresAt, expiresAt, sub);
  }
}
