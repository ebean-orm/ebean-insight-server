package org.ebean.monitor.ui;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import io.avaje.oauth2.oidc.entra.EntraOidc;

interface UiOidcClient {

  String loginUrl(String nonce, String state, String codeChallenge);

  OidcTokens obtainTokens(String code, String codeVerifier);

  OidcTokens refreshAccessToken(String refreshToken);

  static UiOidcClient create(UiAuthSettings settings) {
    if (!settings.enabled()) {
      return new DisabledUiOidcClient();
    }
    if (settings.clientId() == null || settings.redirectUri() == null
      || (!settings.entra() && settings.domain() == null && settings.userPoolId() == null)
      || (settings.entra() && settings.tenantId() == null)) {
      return new MisconfiguredUiOidcClient();
    }
    if (settings.entra()) {
      EntraOidc.Builder builder = EntraOidc.builder()
        .tenantId(settings.tenantId())
        .clientId(settings.clientId())
        .redirectUri(settings.redirectUri())
        .scope(settings.scope());
      if (settings.domain() != null) builder.domain(settings.domain());
      if (settings.clientSecret() != null) builder.clientSecret(settings.clientSecret());
      return new EntraUiOidcClient(builder.build());
    }
    CognitoOidc.Builder builder = CognitoOidc.builder()
      .clientId(settings.clientId())
      .redirectUri(settings.redirectUri())
      .scope(settings.scope());
    if (settings.domain() != null) {
      builder.domain(settings.domain());
    } else {
      builder.userPoolId(settings.userPoolId());
    }
    if (settings.clientSecret() != null) builder.clientSecret(settings.clientSecret());
    return new CognitoUiOidcClient(builder.build());
  }

  final class DisabledUiOidcClient implements UiOidcClient {
    @Override public String loginUrl(String nonce, String state, String codeChallenge) {
      throw new UiTokenException("UI authentication is disabled");
    }
    @Override public OidcTokens obtainTokens(String code, String codeVerifier) {
      throw new UiTokenException("UI authentication is disabled");
    }
    @Override public OidcTokens refreshAccessToken(String refreshToken) {
      throw new UiTokenException("UI authentication is disabled");
    }
  }

  final class MisconfiguredUiOidcClient implements UiOidcClient {
    private UiTokenException error() {
      return new UiTokenException("UI OAuth configuration is incomplete");
    }
    @Override public String loginUrl(String nonce, String state, String codeChallenge) { throw error(); }
    @Override public OidcTokens obtainTokens(String code, String codeVerifier) { throw error(); }
    @Override public OidcTokens refreshAccessToken(String refreshToken) { throw error(); }
  }

  final class CognitoUiOidcClient implements UiOidcClient {
    private final CognitoOidc delegate;
    CognitoUiOidcClient(CognitoOidc delegate) { this.delegate = delegate; }
    @Override public String loginUrl(String nonce, String state, String codeChallenge) {
      try {
        return delegate.loginUrl(nonce, state, codeChallenge);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to build OAuth login URL", e);
      }
    }
    @Override public OidcTokens obtainTokens(String code, String codeVerifier) {
      try {
        return delegate.obtainTokens(code, codeVerifier);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to exchange OAuth code", e);
      }
    }
    @Override public OidcTokens refreshAccessToken(String refreshToken) {
      try {
        return delegate.refreshAccessToken(refreshToken);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to refresh OAuth access token", e);
      }
    }
  }

  final class EntraUiOidcClient implements UiOidcClient {
    private final EntraOidc delegate;
    EntraUiOidcClient(EntraOidc delegate) { this.delegate = delegate; }
    @Override public String loginUrl(String nonce, String state, String codeChallenge) {
      try {
        return delegate.loginUrl(nonce, state, codeChallenge);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to build OAuth login URL", e);
      }
    }
    @Override public OidcTokens obtainTokens(String code, String codeVerifier) {
      try {
        return delegate.obtainTokens(code, codeVerifier);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to exchange OAuth code", e);
      }
    }
    @Override public OidcTokens refreshAccessToken(String refreshToken) {
      try {
        return delegate.refreshAccessToken(refreshToken);
      } catch (RuntimeException e) {
        throw new UiTokenException("Unable to refresh OAuth access token", e);
      }
    }
  }
}
