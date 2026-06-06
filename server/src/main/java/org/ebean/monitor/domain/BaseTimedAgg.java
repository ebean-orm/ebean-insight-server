package org.ebean.monitor.domain;

import io.ebean.annotation.Aggregation;
import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.Sum;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;

/**
 * Common Metric entry.
 */
@MappedSuperclass
public class BaseTimedAgg extends BaseEntry {

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  protected DAppDatabase db;

  @Sum
  protected Long count;

  @Sum
  protected Long total;

  @Aggregation("max(max)")
  protected Long max;

  public DAppDatabase getDb() {
    return db;
  }

  public Long getCount() {
    return count;
  }

  public void setCount(Long count) {
    this.count = count;
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
}
