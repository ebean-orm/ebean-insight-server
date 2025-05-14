package org.ebean.monitor.domain;

import io.ebean.annotation.DbPartition;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

@DbPartition(mode = DAY, property = "eventTime")
@Entity
@Table(name = "gauge_m1")
public class DGaugeRollupM1 extends BaseGaugeEntry {

  DGaugeRollupM1(DAppMetric metric, DEnv env, DApp app, Instant eventTime) {
    super(metric, env, app, eventTime);
  }

  /**
   * Return a copy of this metric for the rollup group.
   */
  public DGaugeRollupM1 createRollupGroup(DAppMetric groupMetric) {
    DGaugeRollupM1 copy = new DGaugeRollupM1(groupMetric, env, app, eventTime);
    copy.setCount(count);
    copy.setTotal(total);
    copy.setMax(max);
    return copy;
  }

  /**
   * Add the entry values to the rollup group metric.
   */
  public void aggregate(DGaugeRollupM1 entry) {
    final long entryCount = entry.getCount();
    if (entryCount != 0) {
      this.count = count + entryCount;
      final BigDecimal eTotal = entry.getTotal();
      if (eTotal != null) {
        this.total = this.total.add(eTotal);
        final BigDecimal eMax = entry.getMax();
        if (eMax != null && eMax.compareTo(max) > 0) {
          this.max = eMax;
        }
      }
    }
  }
}
