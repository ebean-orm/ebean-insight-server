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
 * <p>{@link #from()} and {@link #to()} are null when the window is "no filter"
 * (callers should skip the time-bound clauses). {@link #minutes()} is the
 * window size in minutes (zero when "no filter").
 */
public record TimeWindow(Instant from, Instant to, long minutes) {

  /**
   * Build a time window from caller-supplied minute / hour parameters.
   *
   * @param minutes        optional window size in minutes
   * @param hours          optional window size in hours
   * @param defaultMinutes window applied when neither parameter is supplied;
   *                       use {@code 0} for "no default — no time filter"
   * @throws BadRequestException if both {@code minutes} and {@code hours}
   *                             are supplied
   */
  public static TimeWindow of(Long minutes, Long hours, long defaultMinutes) {
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
      return new TimeWindow(null, null, 0L);
    }
    final Instant to = Instant.now();
    return new TimeWindow(to.minus(m, ChronoUnit.MINUTES), to, m);
  }

  /**
   * Build a window from absolute UTC timestamps.
   *
   * @throws BadRequestException if either timestamp is missing or the window
   *                             is empty or reversed
   */
  public static TimeWindow between(Instant from, Instant to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new BadRequestException("The from timestamp must be before the to timestamp");
    }
    final long seconds = Duration.between(from, to).toSeconds();
    final long minutes = Math.max(1L, (seconds + 59L) / 60L);
    return new TimeWindow(from, to, minutes);
  }

  /** True when this window has a lower-bound timestamp. */
  public boolean hasFrom() {
    return from != null;
  }

  /** True when this window has an upper-bound timestamp. */
  public boolean hasTo() {
    return to != null;
  }
}
