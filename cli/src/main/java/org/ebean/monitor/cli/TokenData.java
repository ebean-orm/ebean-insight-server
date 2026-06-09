package org.ebean.monitor.cli;

import io.avaje.jsonb.Json;
import org.jspecify.annotations.Nullable;

/**
 * Cached OAuth2 tokens persisted to {@code ~/.insight/token.json}.
 *
 * <p>{@code expiresAt} is the absolute epoch-second at which the access token
 * expires (derived from the {@code expires_in} returned at token exchange), so
 * expiry can be evaluated without re-decoding the JWT.
 */
@Json
record TokenData(
    String accessToken,
    @Nullable String refreshToken,
    @Nullable String idToken,
    @Nullable String tokenType,
    long expiresAt,
    long obtainedAt) {

  /** True when the access token is at or past its expiry (with a small skew). */
  boolean isExpired(long nowEpochSeconds) {
    return nowEpochSeconds >= (expiresAt - 30);
  }
}
