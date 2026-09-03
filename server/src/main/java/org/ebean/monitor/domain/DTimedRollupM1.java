package org.ebean.monitor.domain;

import io.ebean.annotation.DbPartition;
import io.ebean.annotation.Index;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

import static io.ebean.annotation.PartitionMode.DAY;

/**
 * Timed metric 1 minute rollup aggregation.
 */
@DbPartition(mode = DAY, property = "eventTime")
@Index(columnNames = "app_id")
@Index(columnNames = "env_id")
@Index(columnNames = "metric_id")
@Entity
@Table(name = "ebean_insight.timed_m1")
public class DTimedRollupM1 extends BaseTimedEntry {

  public DTimedRollupM1(DAppMetric metric, DEnv env, DApp app, Instant eventTime, DAppDatabase db) {
    super(metric, env, app, eventTime, db);
  }
}
