package org.ebean.monitor.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightConfigTest {

  @Test
  void setGetUnset_roundTrips(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));

    assertThat(config.get("namespace")).isNull();

    config.set("namespace", "dev-core");
    config.set("service", "central-insight");
    assertThat(config.get("namespace")).isEqualTo("dev-core");
    assertThat(config.get("service")).isEqualTo("central-insight");

    assertThat(config.all())
        .containsEntry("namespace", "dev-core")
        .containsEntry("service", "central-insight");

    assertThat(config.unset("namespace")).isTrue();
    assertThat(config.get("namespace")).isNull();
    assertThat(config.unset("namespace")).isFalse();
  }

  @Test
  void unknownKey_rejected(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));
    assertThatThrownBy(() -> config.set("bogus", "x"))
        .isInstanceOf(CliException.class)
        .hasMessageContaining("Unknown config key 'bogus'");
  }

  @Test
  void load_onMissingFile_isEmpty(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("does-not-exist.properties"));
    assertThat(config.all()).isEmpty();
  }

  @Test
  void profile_setInProfile_mergesOverBase(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));

    config.set("url", "https://base.example.com");
    config.set("output", "json");
    config.setInProfile("prod", "url", "https://prod.example.com");
    config.setInProfile("prod", "auth-client-id", "prod-client");

    // before activating a profile, base values are returned
    assertThat(config.get("url")).isEqualTo("https://base.example.com");

    config.useProfile("prod");
    assertThat(config.activeProfile()).isEqualTo("prod");

    // profile overrides base for url but not output
    assertThat(config.get("url")).isEqualTo("https://prod.example.com");
    assertThat(config.get("auth-client-id")).isEqualTo("prod-client");
    assertThat(config.get("output")).isEqualTo("json");

    // all() returns merged view, excludes internal active-profile key
    assertThat(config.all())
        .containsEntry("url", "https://prod.example.com")
        .containsEntry("output", "json")
        .containsEntry("auth-client-id", "prod-client")
        .doesNotContainKey(InsightConfig.ACTIVE_PROFILE_KEY);
  }

  @Test
  void profile_clearProfile_restoresBase(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));
    config.set("url", "https://base.example.com");
    config.setInProfile("prod", "url", "https://prod.example.com");
    config.useProfile("prod");

    assertThat(config.get("url")).isEqualTo("https://prod.example.com");

    config.clearProfile();
    assertThat(config.activeProfile()).isNull();
    assertThat(config.get("url")).isEqualTo("https://base.example.com");
  }

  @Test
  void profile_listProfiles_sortsNames(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));

    assertThat(config.listProfiles()).isEmpty();

    config.setInProfile("prod", "url", "https://prod.example.com");
    config.setInProfile("test", "url", "https://test.example.com");
    config.setInProfile("apac", "url", "https://apac.example.com");

    assertThat(config.listProfiles()).containsExactly("apac", "prod", "test");
  }

  @Test
  void profile_unsetInProfile_removesKey(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));
    config.setInProfile("prod", "url", "https://prod.example.com");
    config.setInProfile("prod", "auth-client-id", "prod-client");

    assertThat(config.unsetInProfile("prod", "auth-client-id")).isTrue();
    assertThat(config.unsetInProfile("prod", "auth-client-id")).isFalse();

    config.useProfile("prod");
    assertThat(config.get("auth-client-id")).isNull();
    assertThat(config.get("url")).isEqualTo("https://prod.example.com");
  }

  @Test
  void profile_missingProfileFile_fallsBackToBase(@TempDir Path dir) {
    var config = new InsightConfig(dir.resolve("config.properties"));
    config.set("url", "https://base.example.com");
    // activate a profile that has no file — should silently fall back to base
    config.useProfile("ghost");
    assertThat(config.get("url")).isEqualTo("https://base.example.com");
  }
}
