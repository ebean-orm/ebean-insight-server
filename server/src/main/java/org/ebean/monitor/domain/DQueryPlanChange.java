package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A durable record of a detected query-plan shape event.
 *
 * <p>Emitted at ingest time when a captured plan's normalized shape is either
 * the FIRST shape observed for a {@code (app, env, hash)} series, or has CHANGED
 * relative to the most recent prior shape (compared within the same shape
 * algorithm). Provides durable history and is the basis for alerting.
 *
 * <p>The {@link #toPlan} is unique so that ingest retries cannot emit duplicate
 * events for the same captured plan.
 */
@Entity
@Table(name = "ebean_insight.query_plan_change")
@Index(name = "ix_query_plan_change_to", columnNames = {"to_plan_id"}, unique = true)
@Index(name = "ix_query_plan_change_series", columnNames = {"app_id", "env_id", "hash", "when_captured"})
@Index(name = "ix_query_plan_change_detected", columnNames = {"detected_at"})
@Index(name = "ix_query_plan_change_notified", columnNames = {"notified_at"})
public class DQueryPlanChange extends BaseDomain {

  public enum ChangeType {
    FIRST,
    CHANGED
  }

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DApp app;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DEnv env;

  @Column(nullable = false)
  private final String hash;

  @Column
  @Nullable
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ChangeType changeType;

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  @Nullable
  private DQueryPlan fromPlan;

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DQueryPlan toPlan;

  @Column
  @Nullable
  private String fromShapeHash;

  @Column(nullable = false)
  private String toShapeHash;

  @Column(nullable = false)
  private int algo;

  @Column
  @Nullable
  private Long fromQueryTimeMicros;

  @Column(nullable = false)
  private long toQueryTimeMicros;

  @Column(nullable = false)
  private Instant whenCaptured;

  @Column(nullable = false)
  private Instant detectedAt;

  @Column
  @Nullable
  private Instant notifiedAt;

  public DQueryPlanChange(DApp app, DEnv env, String hash, DQueryPlan toPlan) {
    this.app = app;
    this.env = env;
    this.hash = hash;
    this.toPlan = toPlan;
  }

  public DApp app() {
    return app;
  }

  public DEnv env() {
    return env;
  }

  public String hash() {
    return hash;
  }

  @Nullable
  public String label() {
    return label;
  }

  public DQueryPlanChange setLabel(@Nullable String label) {
    this.label = label;
    return this;
  }

  public ChangeType changeType() {
    return changeType;
  }

  public DQueryPlanChange setChangeType(ChangeType changeType) {
    this.changeType = changeType;
    return this;
  }

  @Nullable
  public DQueryPlan fromPlan() {
    return fromPlan;
  }

  public DQueryPlanChange setFromPlan(@Nullable DQueryPlan fromPlan) {
    this.fromPlan = fromPlan;
    return this;
  }

  public DQueryPlan toPlan() {
    return toPlan;
  }

  @Nullable
  public String fromShapeHash() {
    return fromShapeHash;
  }

  public DQueryPlanChange setFromShapeHash(@Nullable String fromShapeHash) {
    this.fromShapeHash = fromShapeHash;
    return this;
  }

  public String toShapeHash() {
    return toShapeHash;
  }

  public DQueryPlanChange setToShapeHash(String toShapeHash) {
    this.toShapeHash = toShapeHash;
    return this;
  }

  public int algo() {
    return algo;
  }

  public DQueryPlanChange setAlgo(int algo) {
    this.algo = algo;
    return this;
  }

  @Nullable
  public Long fromQueryTimeMicros() {
    return fromQueryTimeMicros;
  }

  public DQueryPlanChange setFromQueryTimeMicros(@Nullable Long fromQueryTimeMicros) {
    this.fromQueryTimeMicros = fromQueryTimeMicros;
    return this;
  }

  public long toQueryTimeMicros() {
    return toQueryTimeMicros;
  }

  public DQueryPlanChange setToQueryTimeMicros(long toQueryTimeMicros) {
    this.toQueryTimeMicros = toQueryTimeMicros;
    return this;
  }

  public Instant whenCaptured() {
    return whenCaptured;
  }

  public DQueryPlanChange setWhenCaptured(Instant whenCaptured) {
    this.whenCaptured = whenCaptured;
    return this;
  }

  public Instant detectedAt() {
    return detectedAt;
  }

  public DQueryPlanChange setDetectedAt(Instant detectedAt) {
    this.detectedAt = detectedAt;
    return this;
  }

  @Nullable
  public Instant notifiedAt() {
    return notifiedAt;
  }

  public DQueryPlanChange setNotifiedAt(@Nullable Instant notifiedAt) {
    this.notifiedAt = notifiedAt;
    return this;
  }
}
