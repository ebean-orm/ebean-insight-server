package org.ebean.monitor.domain;

import io.ebean.annotation.DbPartition;
import io.ebean.annotation.Index;

import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

@DbPartition(mode = DAY, property = "eventTime")
@Index(columnNames = "app_id")
@Index(columnNames = "env_id")
@Index(columnNames = "metric_id")
@MappedSuperclass
public abstract class BaseGaugeRollup extends BaseGaugeEntry {

  public BaseGaugeRollup(Instant endTime, DGaugeRollup gauge) {
    super(gauge.metric, gauge.env, gauge.app, endTime);
    this.max = gauge.getMax();
    this.count = gauge.getCount();
    this.total = gauge.getTotal();
    this.mean = safeMean(count, total);
  }

}
