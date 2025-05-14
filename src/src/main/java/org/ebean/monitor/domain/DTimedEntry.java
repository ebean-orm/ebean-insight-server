package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.DbPartition;
import io.ebean.annotation.PartitionMode;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Metric entry.
 */
@DbPartition(mode = PartitionMode.DAY, property = "eventTime")
@Entity
@Table(name = "timed_entry")
public class DTimedEntry extends BaseTimedEntry {

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  private final DAppPod pod;

  public DTimedEntry(DAppMetric metric, DEnv env, DApp app, Instant eventTime, DAppDatabase db, DAppPod pod) {
    super(metric, env, app, eventTime, db);
    this.pod = pod;
  }

  public DAppPod getPod() {
    return pod;
  }

  public void add(DTimedEntry data) {
    count += data.count;
    total += data.total;
    max = Math.max(max, data.max);
  }

  public void reset() {
    count = 0L;
    total = 0L;
    max = 0L;
    mean = 0L;
  }
}
