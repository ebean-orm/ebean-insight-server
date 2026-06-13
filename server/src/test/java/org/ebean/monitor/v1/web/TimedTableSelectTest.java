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

  // The single-hash timeseries uses a finer resolution than the aggregation
  // tiering above so the trend chart keeps a consistent width across windows.

  @Test
  void timeseries_upTo12Hours_useM1() {
    assertThat(V1QueryService.timeseriesTableFor(0)).isEqualTo("ebean_insight.timed_m1");
    assertThat(V1QueryService.timeseriesTableFor(3 * HOUR)).isEqualTo("ebean_insight.timed_m1");
    // 6h stays 1-minute (was 10-minute under timedTableFor) — the reported fix.
    assertThat(V1QueryService.timeseriesTableFor(6 * HOUR)).isEqualTo("ebean_insight.timed_m1");
    assertThat(V1QueryService.timeseriesTableFor(12 * HOUR)).isEqualTo("ebean_insight.timed_m1");
  }

  @Test
  void timeseries_upTo5Days_useM10() {
    assertThat(V1QueryService.timeseriesTableFor(12 * HOUR + 1)).isEqualTo("ebean_insight.timed_m10");
    assertThat(V1QueryService.timeseriesTableFor(DAY)).isEqualTo("ebean_insight.timed_m10");
    assertThat(V1QueryService.timeseriesTableFor(5 * DAY)).isEqualTo("ebean_insight.timed_m10");
  }

  @Test
  void timeseries_upTo30Days_useM60() {
    assertThat(V1QueryService.timeseriesTableFor(5 * DAY + 1)).isEqualTo("ebean_insight.timed_m60");
    assertThat(V1QueryService.timeseriesTableFor(7 * DAY)).isEqualTo("ebean_insight.timed_m60");
    assertThat(V1QueryService.timeseriesTableFor(30 * DAY)).isEqualTo("ebean_insight.timed_m60");
  }

  @Test
  void timeseries_beyond30Days_useD1() {
    assertThat(V1QueryService.timeseriesTableFor(30 * DAY + 1)).isEqualTo("ebean_insight.timed_d1");
    assertThat(V1QueryService.timeseriesTableFor(120 * DAY)).isEqualTo("ebean_insight.timed_d1");
    assertThat(V1QueryService.timeseriesTableFor(2000 * DAY)).isEqualTo("ebean_insight.timed_d1");
  }
}
