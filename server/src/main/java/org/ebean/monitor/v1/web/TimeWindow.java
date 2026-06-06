package org.ebean.monitor.v1.web;

import io.avaje.jex.http.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Time-window parameter normalisation for the {@code /v1} API.
 *
 * <p>Endpoints accept either {@code xxxMinutes} or {@code xxxHours}
 * (mutually exclusive). Supplying both yields {@link BadRequestException}.
 *
 * <p>{@link #from()} is null when the window is "no filter" (callers should
 * skip the lower-bound clause). {@link #minutes()} is the window size in
 * minutes (zero when "no filter").
 */
record TimeWindow(Instant from, long minutes) {

  /** Window meaning "no time filter". */
  static final TimeWindow NONE = new TimeWindow(null, 0L);

  /**
   * Build a time window from caller-supplied minute / hour parameters.
   *
   * @param minutes        optional window size in minutes
   * @param hours          optional window size in hours
   * @param defaultMinutes window applied when neither parameter is supplied;
   *                       use {@code 0} for "no default — return {@link #NONE}"
   * @throws BadRequestException if both {@code minutes} and {@code hours}
   *                             are supplied
   */
  static TimeWindow of(Long minutes, Long hours, long defaultMinutes) {
    if (minutes != null && hours != null) {
      throw new BadRequestException(
        "Supply only one of the minute / hour window parameters, not both");
    }
    final long m;
    if (minutes != null) {
      m = minutes;
    } else if (hours != null) {
      m = hours * 60L;
    } else {
      m = defaultMinutes;
    }
    if (m <= 0L) {
      return new TimeWindow(null, 0L);
    }
    return new TimeWindow(Instant.now().minus(m, ChronoUnit.MINUTES), m);
  }

  /** True when this window has a lower-bound timestamp. */
  boolean hasFrom() {
    return from != null;
  }

  Duration duration() {
    return Duration.ofMinutes(minutes);
  }
}
