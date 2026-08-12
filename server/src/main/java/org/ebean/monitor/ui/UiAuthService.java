package org.ebean.monitor.ui;

import io.avaje.jsonb.Jsonb;
import io.avaje.jex.http.Context;
import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.core.pkce.Pkce;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

final class UiAuthService {
  private static final long RANDOM_BYTES = 32;
  private final UiAuthSettings settings;
  private final UiOidcClient oidc;
  private final UiTokenVerifier tokenVerifier;
  private final UiSessionStore sessions;
  private final UiLoginTransactionStore transactions;
  private final Jsonb jsonb;
  private final SecureRandom random;

  UiAuthService(
    UiAuthSettings settings,
    UiOidcClient oidc,
    UiTokenVerifier tokenVerifier,
    UiSessionStore sessions,
    UiLoginTransactionStore transactions,
    Jsonb jsonb) {
    this.settings = settings;
    this.oidc = oidc;
    this.tokenVerifier = tokenVerifier;
    this.sessions = sessions;
    this.transactions = transactions;
    this.jsonb = jsonb;
    this.random = new SecureRandom();
  }

  boolean enabled() {
    return settings.enabled();
  }

  String beginLogin(String requestedReturnPath, String previousSessionId) {
    String returnPath = safeReturnPath(requestedReturnPath);
    String state = randomValue();
    String nonce = randomValue();
    Pkce pkce = Pkce.generate();
    transactions.save(new UiLoginTransaction(state, nonce, pkce.verifier(), returnPath,
      Instant.now().plus(settings.transactionTtl()), previousSessionId));
    return oidc.loginUrl(nonce, state, pkce.challenge());
  }

  UiLoginResult completeLogin(String state, String code) {
    Optional<UiLoginTransaction> transaction = transactions.consume(state);
    if (transaction.isEmpty()) {
      throw new UiTokenException("Invalid or expired login state");
    }
    UiLoginTransaction login = transaction.get();
    OidcTokens tokens = oidc.obtainTokens(code, login.codeVerifier());
    tokenVerifier.verifyAccessToken(tokens.accessToken());
    tokenVerifier.verifyIdToken(tokens.idToken());
    validateNonce(tokens.idToken(), login.nonce());
    String sessionId = randomValue();
    Instant now = Instant.now();
    UiSession session = UiSession.of(sessionId, tokens, now, now.plus(settings.sessionTtl()));
    sessions.save(session);
    if (login.previousSessionId() != null) sessions.delete(login.previousSessionId());
    return new UiLoginResult(session, login.returnPath());
  }

  synchronized Optional<UiSession> authenticate(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) return Optional.empty();
    Optional<UiSession> found = sessions.find(sessionId);
    if (found.isEmpty()) return found;
    UiSession session = found.get();
    Instant now = Instant.now();
    if (session.accessTokenExpiresAt().isAfter(now.plus(settings.refreshSkew()))) {
      return found;
    }
    if (session.refreshToken() == null || session.refreshToken().isBlank()) {
      sessions.delete(sessionId);
      return Optional.empty();
    }
    try {
      OidcTokens tokens = oidc.refreshAccessToken(session.refreshToken());
      tokenVerifier.verifyAccessToken(tokens.accessToken());
      UiSession refreshed = session.refreshed(tokens, now);
      sessions.save(refreshed);
      return Optional.of(refreshed);
    } catch (UiTokenException e) {
      sessions.delete(sessionId);
      return Optional.empty();
    }
  }

  void logout(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) sessions.delete(sessionId);
  }

  Context.Cookie sessionCookie(String sessionId) {
    return Context.Cookie.of(settings.cookieName(), sessionId)
      .path("/")
      .httpOnly(true)
      .secure(settings.secureCookie())
      .sameSite(Context.Cookie.SameSite.Lax)
      .maxAge(settings.sessionTtl());
  }

  Context.Cookie expiredSessionCookie() {
    return Context.Cookie.expired(settings.cookieName())
      .path("/")
      .httpOnly(true)
      .secure(settings.secureCookie())
      .sameSite(Context.Cookie.SameSite.Lax);
  }

  String cookieName() {
    return settings.cookieName();
  }

  String safeReturnPath(String requested) {
    if (requested == null || requested.isBlank()) return "/ux";
    if (requested.indexOf('\\') >= 0 || requested.indexOf('#') >= 0
      || requested.chars().anyMatch(Character::isISOControl)) {
      return "/ux";
    }
    try {
      URI uri = URI.create(requested);
      if (uri.isAbsolute() || uri.getRawAuthority() != null
        || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
        || uri.getRawPath().startsWith("//")) {
        return "/ux";
      }
      return requested;
    } catch (IllegalArgumentException e) {
      return "/ux";
    }
  }

  static String encodedReturnPath(String path) {
    return URLEncoder.encode(path, StandardCharsets.UTF_8);
  }

  private void validateNonce(String idToken, String nonce) {
    if (idToken == null || idToken.isBlank()) {
      throw new UiTokenException("OIDC response did not contain an ID token");
    }
    String[] parts = idToken.split("\\.", -1);
    if (parts.length != 3) throw new UiTokenException("Invalid OIDC ID token");
    try {
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      Map<?, ?> claims = jsonb.type(Map.class).fromJson(payload);
      Object returnedNonce = claims.get("nonce");
      if (!(returnedNonce instanceof String returned)
        || !MessageDigest.isEqual(returned.getBytes(StandardCharsets.UTF_8),
        nonce.getBytes(StandardCharsets.UTF_8))) {
        throw new UiTokenException("OIDC nonce validation failed");
      }
    } catch (IllegalArgumentException e) {
      throw new UiTokenException("Invalid OIDC ID token");
    }
  }

  private String randomValue() {
    byte[] bytes = new byte[(int) RANDOM_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
