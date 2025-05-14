package org.ebean.monitor.domain;

import io.ebean.annotation.DbPartition;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

/**
 * Timed metric 1 minute rollup aggregation.
 */
@DbPartition(mode = DAY, property = "eventTime")
@Entity
@Table(name = "timed_m1")
public class DTimedRollupM1 extends BaseTimedEntry {

  public DTimedRollupM1(DAppMetric metric, DEnv env, DApp app, Instant eventTime, DAppDatabase db) {
    super(metric, env, app, eventTime, db);
  }

  /**
   * Create and return a rollup group metric.
   */
  public DTimedRollupM1 createRollupGroup(DAppMetric groupMetric) {
    DTimedRollupM1 copy = new DTimedRollupM1(groupMetric, env, app, eventTime, db);
    copy.setCount(count);
    copy.setTotal(total);
    copy.setMax(max);
    return copy;
  }

  /**
   * Add the entry to the rollup group.
   */
  public void aggregate(DTimedRollupM1 entry) {
    final Long entryCount = entry.getCount();
    if (entryCount != null && entryCount > 0) {
      this.count += entryCount;
      final Long entryTotal = entry.getTotal();
      if (entryTotal != null) {
        this.total += entryTotal;
        this.max = Math.max(max, entry.getMax());
        this.mean = Math.floorDiv(this.total, this.count);
      }
    }
  }
}
