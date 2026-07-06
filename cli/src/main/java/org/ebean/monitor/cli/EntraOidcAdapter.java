package org.ebean.monitor.cli;

import io.avaje.oauth2.core.data.OidcTokens;
import io.avaje.oauth2.oidc.entra.EntraOidc;

/** Adapts {@link EntraOidc} to the provider-agnostic {@link OidcLoginClient}. */
final class EntraOidcAdapter implements OidcLoginClient {

  private final EntraOidc delegate;

  EntraOidcAdapter(EntraOidc delegate) {
    this.delegate = delegate;
  }

  @Override
  public String loginUrl(String nonce, String state, String codeChallenge) {
    return delegate.loginUrl(nonce, state, codeChallenge);
  }

  @Override
  public OidcTokens obtainTokens(String code, String codeVerifier) {
    return delegate.obtainTokens(code, codeVerifier);
  }

  @Override
  public OidcTokens refreshAccessToken(String refreshToken) {
    return delegate.refreshAccessToken(refreshToken);
  }
}
