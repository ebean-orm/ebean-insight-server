package org.ebean.monitor.domain;

import io.ebean.annotation.NotNull;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static java.math.BigDecimal.ZERO;

@MappedSuperclass
public abstract class BaseGaugeEntry extends BaseEntry {

  protected long count;

  @NotNull
  @Column(precision = 18, scale = 3)
  protected BigDecimal total;

  @NotNull
  @Column(precision = 18, scale = 3)
  protected BigDecimal max;

  @NotNull
  @Column(precision = 18, scale = 3)
  protected BigDecimal mean;

  BaseGaugeEntry(DAppMetric metric, DEnv env, DApp app, Instant eventTime) {
    super(metric, env, app, eventTime);
  }

  public long getCount() {
    return count;
  }

  public void setCount(long count) {
    this.count = count;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public void setTotal(BigDecimal total) {
    this.total = total;
  }

  public BigDecimal getMax() {
    return max;
  }

  public void setMax(BigDecimal max) {
    this.max = max;
  }

  public BigDecimal getMean() {
    return mean;
  }

  public void setMean(BigDecimal mean) {
    this.mean = mean;
  }

  BigDecimal safeMean(long count, BigDecimal total) {
    if (count == 0) {//count == null || ZERO.equals(count)) {
      return ZERO;
    } else {
      return total.divide(BigDecimal.valueOf(count), RoundingMode.HALF_UP);
    }
  }
}
