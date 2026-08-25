package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetupCommandTest {

  @Test
  void resolveScope_defaultCognito() {
    var response = new SetupCommand.CliConfigResponse(
        "https://app.auth.ap-southeast-2.amazoncognito.com",
        "client-123",
        null,
        null);

    assertThat(SetupCommand.resolveScope(response)).isEqualTo("openid");
  }

  @Test
  void resolveScope_customCognitoScope() {
    var response = new SetupCommand.CliConfigResponse(
        "https://app.auth.ap-southeast-2.amazoncognito.com",
        "client-123",
        "openid profile email",
        null);

    assertThat(SetupCommand.resolveScope(response)).isEqualTo("openid profile email");
  }

  @Test
  void resolveScope_entraPrependsOfflineAccess() {
    var response = new SetupCommand.CliConfigResponse(
        null,
        "client-123",
        "api://client-123/access_as_user",
        "tenant-456");

    assertThat(SetupCommand.resolveScope(response))
        .isEqualTo("offline_access api://client-123/access_as_user");
  }

  @Test
  void resolveScope_entraAlreadyContainsOfflineAccess() {
    var response = new SetupCommand.CliConfigResponse(
        null,
        "client-123",
        "offline_access api://client-123/access_as_user",
        "tenant-456");

    assertThat(SetupCommand.resolveScope(response))
        .isEqualTo("offline_access api://client-123/access_as_user");
  }

  @Test
  void resolveScope_entraNullScope() {
    var response = new SetupCommand.CliConfigResponse(
        null,
        "client-123",
        null,
        "tenant-456");

    assertThat(SetupCommand.resolveScope(response))
        .isEqualTo("offline_access openid");
  }
}
