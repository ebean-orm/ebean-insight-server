package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The "Application" metrics relate to.
 */
@Entity
@Table(name = "ebean_insight.query_plan")
public class DQueryPlan extends BaseDomain {

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  private final DAppMetric metric;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DEnv env;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DApp app;

  @Column
  private String hash;
  @Column
  private String label;

  @Column
  private long queryTimeMicros;

  @Column
  private long captureCount;

  @Column
  private long captureMicros;

  @Column
  private Instant whenCaptured;

  @Column
  private String sql;
  @Column
  private String bind;
  @Column
  private String plan;

  public DQueryPlan(DApp app, DEnv env, DAppMetric metric) {
    this.app = app;
    this.env = env;
    this.metric = metric;
  }

  public DAppMetric metric() {
    return metric;
  }

  public DEnv env() {
    return env;
  }

  public DApp app() {
    return app;
  }

  public String hash() {
    return hash;
  }

  public DQueryPlan setHash(String hash) {
    this.hash = hash;
    return this;
  }

  public String label() {
    return label;
  }

  public DQueryPlan setLabel(String label) {
    this.label = label;
    return this;
  }

  public long queryTimeMicros() {
    return queryTimeMicros;
  }

  public DQueryPlan setQueryTimeMicros(long queryTimeMicros) {
    this.queryTimeMicros = queryTimeMicros;
    return this;
  }

  public long captureCount() {
    return captureCount;
  }

  public DQueryPlan setCaptureCount(long captureCount) {
    this.captureCount = captureCount;
    return this;
  }

  public long captureMicros() {
    return captureMicros;
  }

  public DQueryPlan setCaptureMicros(long captureMicros) {
    this.captureMicros = captureMicros;
    return this;
  }

  public Instant whenCaptured() {
    return whenCaptured;
  }

  public DQueryPlan setWhenCaptured(Instant whenCaptured) {
    this.whenCaptured = whenCaptured;
    return this;
  }

  public String sql() {
    return sql;
  }

  public DQueryPlan setSql(String sql) {
    this.sql = sql;
    return this;
  }

  public String bind() {
    return bind;
  }

  public DQueryPlan setBind(String bind) {
    this.bind = bind;
    return this;
  }

  public String plan() {
    return plan;
  }

  public DQueryPlan setPlan(String plan) {
    this.plan = plan;
    return this;
  }
}
