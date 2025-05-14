package org.ebean.monitor.rollup;

import org.ebean.monitor.domain.DGaugeRollup;
import org.ebean.monitor.domain.DGaugeRollupD1;
import org.ebean.monitor.domain.DTimedAgg;
import org.ebean.monitor.domain.DTimedRollupD1;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class RollupD1 extends RollupMBase {

  public RollupD1(Instant endTime) {
    super(endTime, endTime.minus(24, ChronoUnit.HOURS));
  }

  @Override
  String label() {
    return "D1";
  }

  protected String baseTableTimed() {
    return "timed_m60";
  }

  @Override
  void saveTimed(DTimedAgg timed) {
    timedCount++;
    new DTimedRollupD1(endTime, timed).save();
  }

  @Override
  String baseTableGauge() {
    return "gauge_m60";
  }

  @Override
  void saveGauge(DGaugeRollup gauge) {
    gaugeCount++;
    new DGaugeRollupD1(endTime, gauge).save();
  }
}
