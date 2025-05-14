package org.ebean.monitor.domain;

import io.ebean.annotation.DbPartition;
import io.ebean.annotation.Index;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

/**
 * Timed metric 10 minute rollup aggregation.
 */
@DbPartition(mode = DAY, property = "eventTime")
@Index(columnNames = "app_id")
@Index(columnNames = "env_id")
@Index(columnNames = "metric_id")
@Entity
@Table(name = "timed_m10")
public class DTimedRollupM10 extends BaseTimedEntry {

  public DTimedRollupM10(Instant eventTime, DTimedAgg timed) {
    super(timed.getMetric(), timed.getEnv(), timed.getApp(), eventTime, timed.getDb());
    this.max = timed.getMax();
    this.count = timed.getCount();
    this.total = timed.getTotal();
    this.mean = safeMean(count, total);
  }
}
