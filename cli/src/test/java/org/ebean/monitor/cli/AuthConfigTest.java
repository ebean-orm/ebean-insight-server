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
    assertThat(auth.scope()).isEqualTo("openid");
    assertThat(auth.redirectPorts()).isEqualTo(AuthConfig.DEFAULT_REDIRECT_PORTS);
    assertThat(auth.redirectUri(54321)).isEqualTo("http://localhost:54321/callback");
  }

  @Test
  void domainDerivedFromUserPoolId() {
    var auth = new AuthConfig(props(
        "auth-user-pool-id", "ap-southeast-2_AbCdEf123",
        "auth-client-id", "client-abc",
        "auth-scope", "insight/read"));

    assertThat(auth.domain()).contains("amazoncognito.com");
    assertThat(auth.scope()).isEqualTo("insight/read");
    assertThat(auth.redirectUri(9999)).isEqualTo("http://localhost:9999/callback");
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
  void multiPort_defaultPorts() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc"));

    assertThat(auth.redirectPorts()).containsExactly(9876, 9877, 9878);
  }

  @Test
  void multiPort_explicitPorts() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc",
        "auth-redirect-ports", "9000,9001,9002"));

    assertThat(auth.redirectPorts()).containsExactly(9000, 9001, 9002);
  }

  @Test
  void multiPort_singlePort() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc",
        "auth-redirect-ports", "8888"));

    assertThat(auth.redirectPorts()).containsExactly(8888);
    assertThat(auth.redirectUri(8888)).isEqualTo("http://localhost:8888/callback");
  }

  @Test
  void multiPort_zeroMeansRandom() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc",
        "auth-redirect-ports", "0"));

    assertThat(auth.redirectPorts()).containsExactly(0);
  }

  @Test
  void multiPort_legacySinglePortKey() {
    var auth = new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc",
        "auth-redirect-port", "7777"));

    assertThat(auth.redirectPorts()).containsExactly(7777);
  }

  @Test
  void multiPort_invalidPortThrows() {
    assertThatThrownBy(() -> new AuthConfig(props(
        "auth-domain", "https://app.auth.ap-southeast-2.amazoncognito.com",
        "auth-client-id", "client-abc",
        "auth-redirect-ports", "abc")))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("invalid port");
  }
}
