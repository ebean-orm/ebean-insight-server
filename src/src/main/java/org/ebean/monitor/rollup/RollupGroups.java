package org.ebean.monitor.rollup;

import org.ebean.monitor.domain.DAppDatabase;
import org.ebean.monitor.domain.DAppMetric;
import org.ebean.monitor.domain.DGaugeRollupM1;
import org.ebean.monitor.domain.DTimedRollupM1;

import java.util.HashMap;
import java.util.Map;

/**
 * In memory rollup/aggregation for IUD & L2 cache "rollup group" metrics.
 */
public class RollupGroups {

  private final Map<String, DTimedRollupM1> timedMap = new HashMap<>();
  private final Map<String, DGaugeRollupM1> gaugeMap = new HashMap<>();
  private int count;

  public void addGauge(DAppMetric group, DGaugeRollupM1 gauge) {
    count++;
    final String key = key(group, gauge);
    final DGaugeRollupM1 agg = gaugeMap.get(key);
    if (agg == null) {
      gaugeMap.put(key, gauge.createRollupGroup(group));
    } else {
      agg.aggregate(gauge);
    }
  }

  public void addTimed(DAppMetric group, DTimedRollupM1 timed) {
    count++;
    final String key = key(group, timed);
    final DTimedRollupM1 agg = timedMap.get(key);
    if (agg == null) {
      timedMap.put(key, timed.createRollupGroup(group));
    } else {
      agg.aggregate(timed);
    }
  }

  public int getCount() {
    return count;
  }

  private String key(DAppMetric group, DGaugeRollupM1 entry) {
    return group.getName() + "|" + entry.getEnv().getId() + "|" + entry.getApp().getId();
  }

  private String key(DAppMetric group, DTimedRollupM1 entry) {
    final DAppDatabase db = entry.getDb();
    int dbId = (db == null) ? 0 : db.getId();
    return group.getName() + "|" + entry.getEnv().getId() + "|" + entry.getApp().getId() + "|" + dbId;
  }

  /**
   * Save the gauge rollup groups.
   */
  public void saveGauges() {
    for (DGaugeRollupM1 value : gaugeMap.values()) {
      value.save();
    }
    gaugeMap.clear();
  }

  /**
   * Save the timed rollup groups.
   */
  public void saveTimed() {
    for (DTimedRollupM1 entry : timedMap.values()) {
      entry.save();
    }
    timedMap.clear();
  }
}
