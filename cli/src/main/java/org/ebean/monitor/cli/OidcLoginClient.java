package org.ebean.monitor.cli;

import io.avaje.oauth2.core.data.OidcTokens;

/**
 * Provider-agnostic OAuth2 Authorization Code + PKCE login client, used by
 * {@link LoginHelper} and {@link AuthSession} so they don't need to know
 * whether the configured provider is Cognito or Entra ID.
 *
 * @see AuthConfig#oidcLogin(int)
 */
interface OidcLoginClient {

  /**
   * Build the authorization (login) URL including a PKCE {@code code_challenge}.
   */
  String loginUrl(String nonce, String state, String codeChallenge);

  /**
   * Exchange the authorization code for tokens, including the PKCE
   * {@code code_verifier}.
   */
  OidcTokens obtainTokens(String code, String codeVerifier);

  OidcTokens refreshAccessToken(String refreshToken);
}
