package org.ebean.monitor.domain;

import io.ebean.annotation.Aggregation;
import io.ebean.annotation.View;

import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@View(name = "gauge_entry")
public class DGaugeAgg extends BaseEntry {

  @Aggregation("count(value)")
  private long count;

  @Aggregation("sum(value)")
  private BigDecimal total;

  @Aggregation("max(value)")
  private BigDecimal max;

  public DGaugeRollupM1 asRollup() {
    DGaugeRollupM1 rollup = new DGaugeRollupM1(metric, env, app, eventTime);
    rollup.setCount(getCount());
    rollup.setTotal(getTotal());
    rollup.setMax(getMax());
    rollup.setMean(total.divide(BigDecimal.valueOf(count), RoundingMode.HALF_UP));
    return rollup;
  }

  public long getCount() {
    return count;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public BigDecimal getMax() {
    return max;
  }
}
