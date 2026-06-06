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
  }

  @Test
  void bothEnabled_isForwardOnlyFalse() {
    Config.setProperty("metrics.store.enabled", "true");
    Config.setProperty("plans.store.enabled", "true");
    assertThat(Application.isForwardOnly()).isFalse();
  }

  @Test
  void metricsOff_plansOn_isForwardOnlyFalse() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.setProperty("plans.store.enabled", "true");
    assertThat(Application.isForwardOnly()).isFalse();
  }

  @Test
  void bothOff_isForwardOnlyTrue() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.setProperty("plans.store.enabled", "false");
    assertThat(Application.isForwardOnly()).isTrue();
  }

  @Test
  void plansDefaultsToMetricsFlag_metricsOff_isForwardOnlyTrue() {
    Config.setProperty("metrics.store.enabled", "false");
    Config.clearProperty("plans.store.enabled");
    assertThat(Application.isForwardOnly()).isTrue();
  }
}
