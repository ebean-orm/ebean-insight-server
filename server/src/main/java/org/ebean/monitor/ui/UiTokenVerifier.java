package org.ebean.monitor.ui;

import io.avaje.oauth2.core.jwt.JwtVerifier;
import io.avaje.oauth2.core.jwt.SignedJwt;

interface UiTokenVerifier {

  void verifyAccessToken(String token);

  void verifyIdToken(String token);

  static UiTokenVerifier create(UiAuthSettings settings) {
    if (!settings.enabled()) {
      return accepting();
    }
    String issuer = settings.issuer();
    if (issuer == null) {
      throw new UiTokenException("UI OAuth issuer is not configured");
    }
    JwtVerifier.Builder builder = JwtVerifier.builder().issuer(issuer);
    String jwksUri = settings.jwksUri();
    if (jwksUri != null) {
      builder.jwksUri(jwksUri);
    }
    JwtVerifier verifier = builder.build();
    return new UiTokenVerifier() {
      @Override
      public void verifyAccessToken(String token) {
        try {
          verifier.verifyAccessToken(token);
        } catch (RuntimeException e) {
          throw new UiTokenException("Invalid OAuth access token", e);
        }
      }

      @Override
      public void verifyIdToken(String token) {
        try {
          verifier.verify(SignedJwt.parse(token));
        } catch (RuntimeException e) {
          throw new UiTokenException("Invalid OIDC ID token", e);
        }
      }
    };
  }

  static UiTokenVerifier accepting() {
    return new UiTokenVerifier() {
      @Override
      public void verifyAccessToken(String token) {
      }

      @Override
      public void verifyIdToken(String token) {
      }
    };
  }
}
