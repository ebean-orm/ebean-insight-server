package org.ebean.monitor.forward;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.Objects.requireNonNull;

/**
 * Exponential backoff with full jitter: the delay for attempt {@code n} is a
 * uniformly random value in {@code [0, min(cap, base * 2^(n-1))]}.
 */
public record ExponentialBackoff(Duration base, Duration cap) implements BackoffPolicy {

  public ExponentialBackoff {
    requireNonNull(base, "base");
    requireNonNull(cap, "cap");
  }

  @Override
  public Duration nextDelay(int attempt) {
    int shift = Math.min(Math.max(attempt - 1, 0), 30);
    long expMs = Math.min(cap.toMillis(), base.toMillis() << shift);
    long jittered = ThreadLocalRandom.current().nextLong(Math.max(1, expMs) + 1);
    return Duration.ofMillis(jittered);
  }
}
