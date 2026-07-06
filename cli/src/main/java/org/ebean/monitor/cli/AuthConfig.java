package org.ebean.monitor.cli;

import java.util.Properties;

import io.avaje.oauth2.oidc.cognito.CognitoOidc;
import io.avaje.oauth2.oidc.cognito.CognitoUris;
import io.avaje.oauth2.oidc.entra.EntraOidc;
import io.avaje.oauth2.oidc.entra.EntraUris;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the OAuth2 client settings used by {@code insight login} and for
 * bearer-token injection / silent refresh. Supports two providers, selected by
 * which config keys are set:
 *
 * <p>Values come from {@code ~/.insight/config.properties} (managed with
 * {@code insight config set auth-* …}):
 * <ul>
 *   <li>{@code auth-tenant-id} — Microsoft Entra ID tenant id. When set, the
 *       CLI logs in against Entra's v2.0 endpoints instead of Cognito's Hosted
 *       UI. Note requesting only the default {@code openid} scope will not
 *       yield a JWT access token verifiable against the tenant's own JWKS —
 *       set {@code auth-scope} to the app's exposed API scope, e.g.
 *       {@code api://<clientId>/access_as_user};</li>
 *   <li>{@code auth-domain} — Cognito Hosted-UI domain
 *       (e.g. {@code https://my-app.auth.ap-southeast-2.amazoncognito.com}); or</li>
 *   <li>{@code auth-user-pool-id} — derive the Cognito domain from the user pool id;</li>
 *   <li>{@code auth-client-id} — the public app client id;</li>
 *   <li>{@code auth-scope} — requested scope (default {@code openid});</li>
 *   <li>{@code auth-redirect-ports} — comma-separated loopback callback ports
 *       (default {@code 9876,9877,9878}). The CLI tries each in order and uses
 *       the first available port. Set to {@code 0} for a random OS-assigned port
 *       (requires an RFC 8252-compliant auth server such as Entra ID).</li>
 * </ul>
 *
 * <p>{@code auth-tenant-id} takes precedence over {@code auth-domain} /
 * {@code auth-user-pool-id} when set — a config can only target one provider.
 */
final class AuthConfig {

  static final int[] DEFAULT_REDIRECT_PORTS = {9876, 9877, 9878};

  private final @Nullable String domain;
  private final @Nullable String tenantId;
  private final @Nullable String clientId;
  private final String scope;
  private final int[] redirectPorts;

  AuthConfig() {
    this(new InsightConfig().load());
  }

  AuthConfig(@Nullable String explicitProfile) {
    this(new InsightConfig().load(explicitProfile));
  }

  AuthConfig(Properties props) {
    String explicitDomain = trimToNull(props.getProperty("auth-domain"));
    String userPoolId = trimToNull(props.getProperty("auth-user-pool-id"));
    String tenantId = trimToNull(props.getProperty("auth-tenant-id"));
    this.tenantId = tenantId;
    if (tenantId != null) {
      this.domain = explicitDomain != null ? explicitDomain : EntraUris.of(tenantId).domain();
    } else if (explicitDomain != null) {
      this.domain = explicitDomain;
    } else if (userPoolId != null) {
      this.domain = CognitoUris.of(userPoolId).domain();
    } else {
      this.domain = null;
    }
    this.clientId = trimToNull(props.getProperty("auth-client-id"));
    String s = trimToNull(props.getProperty("auth-scope"));
    this.scope = s != null ? s : "openid";
    this.redirectPorts = parsePorts(props);
  }

  /** True when enough is configured to start a login / refresh. */
  boolean isConfigured() {
    return domain != null && clientId != null;
  }

  /** True when the configured provider is Microsoft Entra ID rather than Cognito. */
  boolean isEntra() {
    return tenantId != null;
  }

  void requireConfigured() {
    if (!isConfigured()) {
      throw new CliException("""
          OAuth2 login is not configured. Set the client details:
            insight config set auth-client-id <public-client-id>
            insight config set auth-domain <hosted-ui-domain>   # Cognito — or auth-user-pool-id <id>
            insight config set auth-tenant-id <tenant-id>       # Entra ID — instead of auth-domain
            insight config set auth-scope <scope>               # optional, default openid
            insight config set auth-redirect-ports <ports>      # optional, default 9876,9877,9878""");
    }
  }

  String domain() {
    return require(domain, "auth-domain");
  }

  String tenantId() {
    return require(tenantId, "auth-tenant-id");
  }

  String clientId() {
    return require(clientId, "auth-client-id");
  }

  String scope() {
    return scope;
  }

  /** The ports to try in order when binding the loopback receiver. */
  int[] redirectPorts() {
    return redirectPorts;
  }

  /** The loopback redirect URI for the given bound port. */
  String redirectUri(int port) {
    return "http://localhost:" + port + "/callback";
  }

  /**
   * Build the provider-agnostic OIDC login client (Cognito or Entra, depending
   * on configuration) for the login flow using the given loopback port.
   */
  OidcLoginClient oidcLogin(int port) {
    if (isEntra()) {
      EntraOidc entra = EntraOidc.builder()
          .tenantId(tenantId())
          .clientId(clientId())
          .scope(scope)
          .redirectUri(redirectUri(port))
          .build();
      return new EntraOidcAdapter(entra);
    }
    CognitoOidc cognito = CognitoOidc.builder()
        .domain(domain())
        .clientId(clientId())
        .scope(scope)
        .redirectUri(redirectUri(port))
        .build();
    return new CognitoOidcAdapter(cognito);
  }

  /** Build the OIDC login client for token refresh (redirect URI is not checked). */
  OidcLoginClient oidcLogin() {
    int port = redirectPorts[0] == 0 ? 9876 : redirectPorts[0];
    return oidcLogin(port);
  }

  private static int[] parsePorts(Properties props) {
    String multi = trimToNull(props.getProperty("auth-redirect-ports"));
    if (multi != null) {
      return parsePortList(multi, "auth-redirect-ports");
    }
    // Legacy single-port key
    String single = trimToNull(props.getProperty("auth-redirect-port"));
    if (single != null) {
      return new int[]{parseSinglePort(single, "auth-redirect-port")};
    }
    return DEFAULT_REDIRECT_PORTS.clone();
  }

  private static int[] parsePortList(String value, String key) {
    String[] parts = value.split(",");
    int[] ports = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      ports[i] = parseSinglePort(parts[i].trim(), key);
    }
    return ports;
  }

  private static int parseSinglePort(String value, String key) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw new CliException("config " + key + " contains invalid port: '" + value + "'");
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
