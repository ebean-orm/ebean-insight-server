package org.ebean.monitor;

import io.avaje.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTest {

  @AfterEach
  void reset() {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "true");
    Config.setProperty("datasource.db.offline", "false");
    Config.setProperty("ebean.migration.run", "true");
  }

  @Test
  void bothEnabled_leavesDbOnline() {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "true");
    Config.setProperty("datasource.db.offline", "false");
    Config.setProperty("ebean.migration.run", "true");

    Application.configureForwardOnlyIfNeeded();
    assertThat(Config.getBool("datasource.db.offline")).isFalse();
    assertThat(Config.getBool("ebean.migration.run")).isTrue();
  }

  @Test
  void metricsOff_plansOn_leavesDbOnline() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.setProperty("plans.store.enabled", "true");
    Config.setProperty("datasource.db.offline", "false");
    Config.setProperty("ebean.migration.run", "true");

    Application.configureForwardOnlyIfNeeded();
    assertThat(Config.getBool("datasource.db.offline")).isFalse();
    assertThat(Config.getBool("ebean.migration.run")).isTrue();
  }

  @Test
  void bothOff_disablesDbAndMigration() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.setProperty("plans.store.enabled", "false");
    Config.setProperty("datasource.db.offline", "false");
    Config.setProperty("ebean.migration.run", "true");

    Application.configureForwardOnlyIfNeeded();
    assertThat(Config.getBool("datasource.db.offline")).isTrue();
    assertThat(Config.getBool("ebean.migration.run")).isFalse();
  }

  @Test
  void plansDefaultsToMetricsFlag_bothOff_disablesDb() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.clearProperty("plans.store.enabled");
    Config.setProperty("datasource.db.offline", "false");
    Config.setProperty("ebean.migration.run", "true");

    Application.configureForwardOnlyIfNeeded();
    assertThat(Config.getBool("datasource.db.offline")).isTrue();
    assertThat(Config.getBool("ebean.migration.run")).isFalse();
  }
}
