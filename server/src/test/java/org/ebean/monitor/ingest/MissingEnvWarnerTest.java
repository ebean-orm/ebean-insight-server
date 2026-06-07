package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissingEnvWarnerTest {

  @Test
  void warnsOncePerApp_whenEnvironmentMissing() {
    var warner = new MissingEnvWarner();
    assertThat(warner.check("app-a", null)).isTrue();
    assertThat(warner.check("app-a", null)).isFalse();
    assertThat(warner.check("app-a", "")).isFalse();
  }

  @Test
  void treatsBlankAsMissing() {
    var warner = new MissingEnvWarner();
    assertThat(warner.check("app-b", "   ")).isTrue();
  }

  @Test
  void doesNotWarn_whenEnvironmentPresent() {
    var warner = new MissingEnvWarner();
    assertThat(warner.check("app-c", "prod")).isFalse();
  }

  @Test
  void warnsPerDistinctApp() {
    var warner = new MissingEnvWarner();
    assertThat(warner.check("app-d", null)).isTrue();
    assertThat(warner.check("app-e", null)).isTrue();
  }

  @Test
  void handlesMissingAppName() {
    var warner = new MissingEnvWarner();
    assertThat(warner.check(null, null)).isTrue();
    assertThat(warner.check("", null)).isFalse();
  }
}
