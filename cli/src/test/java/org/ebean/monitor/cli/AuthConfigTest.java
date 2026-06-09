package org.ebean.monitor.cli;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigTest {

  private static Properties props(String... kv) {
    var p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }

  @Test
  void notConfigured_byDefault() {
    var auth = new AuthConfig(new Properties());
    assertThat(auth.isConfigured()).isFalse();
    assertThatThrownBy(auth::requireConfigured)
        .isInstanceOf(CliException.class)
        .hasMessageContaining("not configured");
  }

  @Test
  void explicitDomain_andDefaults() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc"));

    assertThat(auth.isConfigured()).isTrue();
    assertThat(auth.domain()).isEqualTo("https://app.auth.ap-southeast-2.amazoncognito.com");
    assertThat(auth.clientId()).isEqualTo("client-abc");
    assertThat(auth.scope()).isEqualTo("default/default");
    assertThat(auth.redirectPort()).isEqualTo(AuthConfig.DEFAULT_REDIRECT_PORT);
    assertThat(auth.redirectUri()).isEqualTo("http://localhost:9876/callback");
  }

  @Test
  void domainDerivedFromUserPoolId() {
    var auth = new AuthConfig(props(
        "auth-user-pool-id", "ap-southeast-2_AbCdEf123",
        "auth-client-id", "client-abc",
        "auth-scope", "insight/read",
        "auth-redirect-port", "9123"));

    assertThat(auth.domain()).contains("amazoncognito.com");
    assertThat(auth.scope()).isEqualTo("insight/read");
    assertThat(auth.redirectPort()).isEqualTo(9123);
    assertThat(auth.redirectUri()).isEqualTo("http://localhost:9123/callback");
  }

  @Test
  void explicitDomain_winsOverUserPoolId() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://explicit.example.com",
        "auth-user-pool-id", "ap-southeast-2_AbCdEf123",
        "auth-client-id", "client-abc"));

    assertThat(auth.domain()).isEqualTo("https://explicit.example.com");
  }

  @Test
  void invalidRedirectPort_rejected() {
    assertThatThrownBy(() -> new AuthConfig(props(
        "auth-domain", "https://x", "auth-client-id", "c", "auth-redirect-port", "abc")))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("auth-redirect-port");
  }
}
