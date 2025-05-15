package org.ebean.monitor.domain;

import io.ebean.annotation.Max;
import io.ebean.annotation.Sum;
import io.ebean.annotation.View;

import jakarta.persistence.Entity;
import java.math.BigDecimal;

@Entity
@View(name = "ebean_insight.gauge_entry")
public class DGaugeRollup extends BaseEntry {

  @Sum
  private long count;

  @Sum
  private BigDecimal total;

  @Max
  private BigDecimal max;

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
