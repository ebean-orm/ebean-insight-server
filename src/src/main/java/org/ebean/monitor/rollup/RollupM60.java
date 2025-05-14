package org.ebean.monitor.rollup;

import org.ebean.monitor.domain.DGaugeRollup;
import org.ebean.monitor.domain.DGaugeRollupM60;
import org.ebean.monitor.domain.DTimedAgg;
import org.ebean.monitor.domain.DTimedRollupM60;

import java.time.Instant;

public class RollupM60 extends RollupMBase {

  public RollupM60(Instant endTime) {
    super(endTime, 60);
  }

  @Override
  String label() {
    return "M60";
  }

  protected String baseTableTimed() {
    return "timed_m10";
  }

  @Override
  void saveTimed(DTimedAgg timed) {
    timedCount++;
    new DTimedRollupM60(endTime, timed).save();
  }

  @Override
  String baseTableGauge() {
    return "gauge_m10";
  }

  @Override
  void saveGauge(DGaugeRollup gauge) {
    gaugeCount++;
    new DGaugeRollupM60(endTime, gauge).save();
  }

}
