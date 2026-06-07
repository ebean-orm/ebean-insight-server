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
}
