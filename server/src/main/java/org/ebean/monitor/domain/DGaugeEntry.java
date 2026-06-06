package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.DbPartition;
import io.ebean.annotation.NotNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

@DbPartition(mode = DAY, property = "eventTime")
@Entity
@Table(name = "ebean_insight.gauge_entry")
public class DGaugeEntry extends BaseEntry {

  @NotNull
  @Column(precision = 18, scale = 3)
  private final BigDecimal value;

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  private final DAppPod pod;

  public DGaugeEntry(DAppMetric metric, DEnv env, DApp app, Instant eventTime, DAppPod pod, BigDecimal value) {
    super(metric, env, app, eventTime);
    this.pod = pod;
    this.value = value;
  }

  public BigDecimal getValue() {
    return value;
  }

  public DAppPod getPod() {
    return pod;
  }
}
