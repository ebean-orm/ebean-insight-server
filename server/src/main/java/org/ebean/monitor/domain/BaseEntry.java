package org.ebean.monitor.domain;

import io.ebean.Model;
import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.Index;
import io.ebean.annotation.NotNull;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * Common Metric entry.
 */
@MappedSuperclass
public class BaseEntry extends Model {

  /**
   * Time of metric collection truncated to the minute.
   */
  @Index
  @NotNull
  protected Instant eventTime; // truncated to minute

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  protected DAppMetric metric;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  protected DEnv env;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  protected DApp app;

  BaseEntry(DAppMetric metric, DEnv env, DApp app, Instant eventTime) {
    this.metric = metric;
    this.env = env;
    this.app = app;
    this.eventTime = eventTime;
  }

  BaseEntry() {
  }

  public DAppMetric getMetric() {
    return metric;
  }

  public DEnv getEnv() {
    return env;
  }

  public DApp getApp() {
    return app;
  }

  public Instant getEventTime() {
    return eventTime;
  }
}
