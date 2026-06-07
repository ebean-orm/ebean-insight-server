package org.ebean.monitor.forward;

import java.time.Duration;

/** Computes the delay before the next reconnect attempt. */
@FunctionalInterface
public interface BackoffPolicy {

  /**
   * @param attempt the 1-based consecutive-failure count
   * @return the delay to wait before the next attempt
   */
  Duration nextDelay(int attempt);

  /** Full-jitter exponential backoff, 200ms base capped at 10s. */
  static BackoffPolicy defaults() {
    return new ExponentialBackoff(Duration.ofMillis(200), Duration.ofSeconds(10));
  }
}
