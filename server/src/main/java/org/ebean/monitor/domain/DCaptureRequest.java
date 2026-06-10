package org.ebean.monitor.domain;

import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A durable record of a query-plan capture request.
 *
 * <p>Created when a capture is requested and marked collected once the
 * matching plan is ingested. Unlike the in-memory delivery queue this row
 * survives forwarder polls and server restarts, so it backs a reliable
 * "in-flight capture" view for the whole ~5 minute collection window.
 *
 * <p>A null {@link #env} means the capture was requested for "any environment":
 * it is delivered to the app's next poll regardless of which environment it
 * reports, and the env is filled in from the plan that is ultimately collected.
 */
@Entity
@Table(name = "ebean_insight.capture_request")
@Index(name = "ix_capture_request_open", columnNames = {"collected_at", "requested_at"})
public class DCaptureRequest extends BaseDomain {

  @DbForeignKey(noConstraint = true)
  @ManyToOne(optional = false)
  private final DApp app;

  @DbForeignKey(noConstraint = true)
  @ManyToOne
  @Nullable
  private DEnv env;

  @Column(nullable = false)
  private final String hash;

  @Column
  @Nullable
  private String label;

  @Column(nullable = false)
  private Instant requestedAt;

  @Column
  @Nullable
  private Instant collectedAt;

  public DCaptureRequest(DApp app, String hash) {
    this.app = app;
    this.hash = hash;
  }

  public DApp app() {
    return app;
  }

  @Nullable
  public DEnv env() {
    return env;
  }

  public DCaptureRequest setEnv(@Nullable DEnv env) {
    this.env = env;
    return this;
  }

  public String hash() {
    return hash;
  }

  @Nullable
  public String label() {
    return label;
  }

  public DCaptureRequest setLabel(@Nullable String label) {
    this.label = label;
    return this;
  }

  public Instant requestedAt() {
    return requestedAt;
  }

  public DCaptureRequest setRequestedAt(Instant requestedAt) {
    this.requestedAt = requestedAt;
    return this;
  }

  @Nullable
  public Instant collectedAt() {
    return collectedAt;
  }

  public DCaptureRequest setCollectedAt(@Nullable Instant collectedAt) {
    this.collectedAt = collectedAt;
    return this;
  }
}
