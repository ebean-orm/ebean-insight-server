package org.ebean.monitor.ui;

import io.avaje.config.Config;
import org.ebean.monitor.Application;

import java.time.Duration;

/**
 * Configuration for the browser-facing BFF session.
 *
 * <p>The provider keys intentionally mirror the CLI keys. UI-specific values
 * take precedence, which allows a server to use a different redirect URI or
 * client secret without changing CLI bootstrap configuration.
 */
record UiAuthSettings(
  boolean enabled,
  String domain,
  String tenantId,
  String userPoolId,
  String clientId,
  String clientSecret,
  String scope,
  String redirectUri,
  boolean secureCookie,
  String cookieName,
  boolean persistentStore,
  Duration sessionTtl,
  Duration transactionTtl,
  Duration refreshSkew) {

  static UiAuthSettings load() {
    boolean enabled = Config.getBool("insight.ui.auth.enabled", false);
    String environment = Config.get("app.environment", "prod");
    boolean secure = Config.getBool("insight.ui.auth.cookie-secure",
      "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment));
    String cookieName = value("insight.ui.auth.cookie-name", null);
    if (cookieName == null) {
      cookieName = secure ? "__Host-insight-ui-session" : "insight-ui-session";
    }
    int port = Config.getInt("server.port", 9080);
    String redirectUri = value("insight.ui.auth.redirect-uri", null);
    if (redirectUri == null && !enabled) {
      redirectUri = "http://localhost:" + port + "/auth/callback";
    }
    return new UiAuthSettings(
      enabled,
      first("insight.ui.auth.domain", "insight.cli.auth.domain"),
      first("insight.ui.auth.tenant-id", "insight.cli.auth.tenant-id"),
      first("insight.ui.auth.user-pool-id", "insight.cli.auth.user-pool-id"),
      first("insight.ui.auth.client-id", "insight.cli.auth.client-id"),
      value("insight.ui.auth.client-secret", null),
      firstOrDefault("insight.ui.auth.scope", "insight.cli.auth.scope", "openid"),
      redirectUri,
      secure,
      cookieName,
      Config.getBool("insight.ui.auth.persistent-store", enabled)
        && !Application.isForwardOnly(),
      Duration.ofSeconds(Config.getLong("insight.ui.auth.session-ttl-seconds", 28_800)),
      Duration.ofSeconds(Config.getLong("insight.ui.auth.transaction-ttl-seconds", 600)),
      Duration.ofSeconds(Config.getLong("insight.ui.auth.refresh-skew-seconds", 60)));
  }

  boolean entra() {
    return tenantId != null;
  }

  String issuer() {
    String configured = value("insight.ui.auth.issuer", null);
    if (configured != null) {
      return configured;
    }
    configured = value("insight.auth.issuer", null);
    if (configured != null) {
      return configured;
    }
    if (tenantId != null) {
      return "https://login.microsoftonline.com/" + tenantId + "/v2.0";
    }
    if (userPoolId != null && userPoolId.indexOf('_') > 0) {
      String region = userPoolId.substring(0, userPoolId.indexOf('_'));
      return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }
    return null;
  }

  String jwksUri() {
    String configured = value("insight.ui.auth.jwks-uri", null);
    if (configured != null) {
      return configured;
    }
    configured = value("insight.auth.jwks-uri", null);
    if (configured != null) {
      return configured;
    }
    if (tenantId != null) {
      return "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
    }
    return null;
  }

  private static String first(String preferred, String fallback) {
    return firstOrDefault(preferred, fallback, null);
  }

  private static String firstOrDefault(String preferred, String fallback, String defaultValue) {
    String value = value(preferred, null);
    return value != null ? value : value(fallback, defaultValue);
  }

  private static String value(String key, String defaultValue) {
    String value = Config.getNullable(key);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }
}
