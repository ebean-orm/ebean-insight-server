package org.ebean.monitor.v1.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimedTableSelectTest {

  private static final long HOUR = 60L;
  private static final long DAY = 24L * 60;

  @Test
  void smallWindows_useM1() {
    assertThat(V1QueryService.timedTableFor(0)).isEqualTo("ebean_insight.timed_m1");
    assertThat(V1QueryService.timedTableFor(1)).isEqualTo("ebean_insight.timed_m1");
    assertThat(V1QueryService.timedTableFor(60)).isEqualTo("ebean_insight.timed_m1");
    assertThat(V1QueryService.timedTableFor(3 * HOUR)).isEqualTo("ebean_insight.timed_m1");
  }

  @Test
  void upToTwoDays_useM10() {
    assertThat(V1QueryService.timedTableFor(3 * HOUR + 1)).isEqualTo("ebean_insight.timed_m10");
    assertThat(V1QueryService.timedTableFor(6 * HOUR)).isEqualTo("ebean_insight.timed_m10");
    assertThat(V1QueryService.timedTableFor(2 * DAY)).isEqualTo("ebean_insight.timed_m10");
  }

  @Test
  void upTo120Days_useM60() {
    assertThat(V1QueryService.timedTableFor(2 * DAY + 1)).isEqualTo("ebean_insight.timed_m60");
    assertThat(V1QueryService.timedTableFor(30 * DAY)).isEqualTo("ebean_insight.timed_m60");
    assertThat(V1QueryService.timedTableFor(120 * DAY)).isEqualTo("ebean_insight.timed_m60");
  }

  @Test
  void beyond120Days_useD1() {
    assertThat(V1QueryService.timedTableFor(120 * DAY + 1)).isEqualTo("ebean_insight.timed_d1");
    assertThat(V1QueryService.timedTableFor(365 * DAY)).isEqualTo("ebean_insight.timed_d1");
    assertThat(V1QueryService.timedTableFor(5000 * DAY)).isEqualTo("ebean_insight.timed_d1");
  }
}
