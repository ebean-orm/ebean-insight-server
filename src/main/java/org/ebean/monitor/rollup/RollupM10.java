package org.ebean.monitor.rollup;

import org.ebean.monitor.domain.DGaugeRollup;
import org.ebean.monitor.domain.DGaugeRollupM10;
import org.ebean.monitor.domain.DTimedAgg;
import org.ebean.monitor.domain.DTimedRollupM10;

import java.time.Instant;

public class RollupM10 extends RollupMBase {

  public RollupM10(Instant endTime) {
    super(endTime, 10);
  }

  @Override
  String label() {
    return "M10";
  }

  protected String baseTableTimed() {
    return "timed_m1";
  }

  @Override
  void saveTimed(DTimedAgg timed) {
    timedCount++;
    new DTimedRollupM10(endTime, timed).save();
  }

  @Override
  String baseTableGauge() {
    return "gauge_m1";
  }

  @Override
  void saveGauge(DGaugeRollup gauge) {
    gaugeCount++;
    new DGaugeRollupM10(endTime, gauge).save();
  }

}
