package org.ebean.monitor.domain;

import io.ebean.annotation.View;

import jakarta.persistence.Entity;

/**
 * Aggregation on timed_entry.
 */
@Entity
@View(name = "ebean_insight.timed_entry")
public class DTimedAgg extends BaseTimedAgg {

  public DTimedRollupM1 asRollup() {
    final DTimedRollupM1 rollup = new DTimedRollupM1(metric, env, app, eventTime, db);
    rollup.setCount(getCount());
    if (max != null) {
      rollup.setMax(max);
    } else {
      rollup.setMax(0L);
    }
    if (total != null) {
      rollup.setTotal(total);
      rollup.setMean(total / count);
    } else {
      rollup.setTotal(0L);
      rollup.setMean(0L);
    }
    return rollup;
  }
}
