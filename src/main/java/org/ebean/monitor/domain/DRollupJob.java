package org.ebean.monitor.domain;

import io.ebean.Model;
import io.ebean.annotation.Aggregation;
import io.ebean.annotation.Index;
import io.ebean.annotation.NotNull;
import io.ebean.annotation.WhenModified;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Effectively a log of the rollup job execution.
 */
@Entity
@Table(name = "rollup_job")
public class DRollupJob extends Model {

  @Index
  @NotNull
  private final Instant eventTime; // truncated to minute

  private final long executionTime;

  private final long rollupCount;

  @WhenModified
  private Instant whenModified;

  @Aggregation("max(eventTime)")
  private Instant maxEventTime;

  public DRollupJob(Instant eventTime, long executionTime, long rollupCount) {
    this.eventTime = eventTime;
    this.executionTime = executionTime;
    this.rollupCount = rollupCount;
  }

  public Instant getEventTime() {
    return eventTime;
  }

  public long getExecutionTime() {
    return executionTime;
  }

  public long getRollupCount() {
    return rollupCount;
  }

  public Instant getWhenModified() {
    return whenModified;
  }

  public Instant getMaxEventTime() {
    return maxEventTime;
  }

  public void setMaxEventTime(Instant maxEventTime) {
    this.maxEventTime = maxEventTime;
  }
}
