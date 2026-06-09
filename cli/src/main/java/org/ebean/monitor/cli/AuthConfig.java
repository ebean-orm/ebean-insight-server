package org.ebean.monitor.cli;

import java.util.Properties;

import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import io.avaje.oauth2.oidc.cognito.CognitoUris;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the OAuth2 (Cognito) client settings used by {@code insight login}
 * and for bearer-token injection / silent refresh.
 *
 * <p>Values come from {@code ~/.insight/config.properties} (managed with
 * {@code insight config set auth-* …}):
 * <ul>
 *   <li>{@code auth-domain} — Cognito Hosted-UI domain
 *       (e.g. {@code https://my-app.auth.ap-southeast-2.amazoncognito.com}); or</li>
 *   <li>{@code auth-user-pool-id} — derive the domain from the user pool id;</li>
 *   <li>{@code auth-client-id} — the public app client id;</li>
 *   <li>{@code auth-scope} — requested scope (default {@code default/default});</li>
 *   <li>{@code auth-redirect-port} — loopback callback port (default {@code 9876}).
 *       Must match a callback URL registered on the Cognito app client.</li>
 * </ul>
 */
final class AuthConfig {

  static final int DEFAULT_REDIRECT_PORT = 9876;

  private final @Nullable String domain;
  private final @Nullable String clientId;
  private final String scope;
  private final int redirectPort;

  AuthConfig() {
    this(new InsightConfig().load());
  }

  AuthConfig(Properties props) {
    String explicitDomain = trimToNull(props.getProperty("auth-domain"));
    String userPoolId = trimToNull(props.getProperty("auth-user-pool-id"));
    this.domain = explicitDomain != null ? explicitDomain
        : (userPoolId != null ? CognitoUris.of(userPoolId).domain() : null);
    this.clientId = trimToNull(props.getProperty("auth-client-id"));
    String s = trimToNull(props.getProperty("auth-scope"));
    this.scope = s != null ? s : "default/default";
    this.redirectPort = parsePort(props.getProperty("auth-redirect-port"));
  }

  /** True when enough is configured to start a login / refresh. */
  boolean isConfigured() {
    return domain != null && clientId != null;
  }

  void requireConfigured() {
    if (!isConfigured()) {
      throw new CliException("""
          OAuth2 login is not configured. Set the Cognito client details:
            insight config set auth-domain <hosted-ui-domain>   # or auth-user-pool-id <id>
            insight config set auth-client-id <public-client-id>
            insight config set auth-scope <scope>               # optional, default default/default
            insight config set auth-redirect-port <port>        # optional, default 9876""");
    }
  }

  String domain() {
    return require(domain, "auth-domain");
  }

  String clientId() {
    return require(clientId, "auth-client-id");
  }

  String scope() {
    return scope;
  }

  int redirectPort() {
    return redirectPort;
  }

  /** The loopback redirect URI; must match a Cognito app-client callback URL. */
  String redirectUri() {
    return "http://localhost:" + redirectPort + "/callback";
  }

  /** Build the Cognito OIDC client for a public (PKCE) app client. */
  CognitoOidc cognitoOidc() {
    return CognitoOidc.builder()
        .domain(domain())
        .clientId(clientId())
        .scope(scope)
        .redirectUri(redirectUri())
        .build();
  }

  private static int parsePort(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_REDIRECT_PORT;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new CliException("config auth-redirect-port is not a number: '" + value + "'");
    }
  }

  private static @Nullable String trimToNull(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String require(@Nullable String value, String key) {
    if (value == null) {
      throw new CliException("config " + key + " is not set");
    }
    return value;
  }
}
