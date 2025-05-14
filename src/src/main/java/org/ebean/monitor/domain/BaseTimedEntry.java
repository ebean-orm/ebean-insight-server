package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * Common Metric entry.
 */
@MappedSuperclass
public class BaseTimedEntry extends BaseEntry {

  private static final Long ZERO = 0L;

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  final DAppDatabase db;

  Long count;
  Long mean;
  Long max;
  Long total;

  BaseTimedEntry(DAppMetric metric, DEnv env, DApp app, Instant eventTime, DAppDatabase db) {
    super(metric, env, app, eventTime);
    this.db = db;
  }

  public DAppDatabase getDb() {
    return db;
  }

  public Long getCount() {
    return count;
  }

  public void setCount(Long count) {
    this.count = count;
  }

  public Long getMean() {
    return mean;
  }

  public void setMean(Long mean) {
    this.mean = mean;
  }

  public Long getMax() {
    return max;
  }

  public void setMax(Long max) {
    this.max = max;
  }

  public Long getTotal() {
    return total;
  }

  public void setTotal(Long total) {
    this.total = total;
  }

  long safeMean(Long count, Long total) {
    if (count == null || ZERO.equals(count)) {
      return 0L;
    } else {
      return Math.floorDiv(total, count);
    }
  }
}
