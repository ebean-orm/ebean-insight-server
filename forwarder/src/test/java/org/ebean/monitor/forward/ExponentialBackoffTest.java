package org.ebean.monitor.forward;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExponentialBackoffTest {

  @Test
  void withinCapAndNonNegative() {
    var backoff = new ExponentialBackoff(Duration.ofMillis(200), Duration.ofSeconds(10));
    for (int attempt = 1; attempt <= 50; attempt++) {
      var delay = backoff.nextDelay(attempt);
      assertThat(delay).isBetween(Duration.ZERO, Duration.ofSeconds(10));
    }
  }

  @Test
  void largeAttemptDoesNotOverflowAndStaysCapped() {
    var backoff = new ExponentialBackoff(Duration.ofMillis(200), Duration.ofSeconds(10));
    assertThat(backoff.nextDelay(Integer.MAX_VALUE)).isBetween(Duration.ZERO, Duration.ofSeconds(10));
  }

  @Test
  void defaultsCapAtTenSeconds() {
    var backoff = BackoffPolicy.defaults();
    for (int attempt = 1; attempt <= 20; attempt++) {
      assertThat(backoff.nextDelay(attempt)).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }
  }
}
