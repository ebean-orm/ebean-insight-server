package org.ebean.monitor.v1.web;

import io.avaje.jex.http.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

  @Test
  void absoluteWindowPreservesBoundsAndRoundsMinutesUp() {
    final Instant from = Instant.parse("2026-08-11T02:00:00Z");
    final Instant to = Instant.parse("2026-08-11T02:01:01Z");

    final TimeWindow window = TimeWindow.between(from, to);

    assertThat(window.from()).isEqualTo(from);
    assertThat(window.to()).isEqualTo(to);
    assertThat(window.minutes()).isEqualTo(2L);
  }

  @Test
  void absoluteWindowRejectsReversedBounds() {
    final Instant instant = Instant.parse("2026-08-11T02:00:00Z");

    assertThatThrownBy(() -> TimeWindow.between(instant, instant))
      .isInstanceOf(BadRequestException.class)
      .hasMessageContaining("before");
    assertThatThrownBy(() -> TimeWindow.between(instant.plusSeconds(1), instant))
      .isInstanceOf(BadRequestException.class)
      .hasMessageContaining("before");
  }
}
