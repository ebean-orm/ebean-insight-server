package org.ebean.monitor.domain;

import io.ebean.annotation.Index;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Durable minute-level aggregate of authenticated requests.
 */
@Entity
@Table(name = "ebean_insight.user_usage")
@Index(name = "ix_user_usage_minute", columnNames = {"minute_at"})
@Index(name = "ix_user_usage_user_minute", columnNames = {"user_id", "minute_at"})
public class DUserUsage {

  @Column(nullable = false)
  private Instant minuteAt;

  @Column(nullable = false, length = 200)
  private String userId;

  @Column(nullable = false, length = 10)
  private String method;

  @Column(nullable = false, length = 300)
  private String path;

  @Column(nullable = false)
  private long requestCount;

  @Column(nullable = false)
  private long totalMicros;

  @Column(nullable = false)
  private long maxMicros;

  public Instant getMinuteAt() {
    return minuteAt;
  }

  public void setMinuteAt(Instant minuteAt) {
    this.minuteAt = minuteAt;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public long getRequestCount() {
    return requestCount;
  }

  public void setRequestCount(long requestCount) {
    this.requestCount = requestCount;
  }

  public long getTotalMicros() {
    return totalMicros;
  }

  public void setTotalMicros(long totalMicros) {
    this.totalMicros = totalMicros;
  }

  public long getMaxMicros() {
    return maxMicros;
  }

  public void setMaxMicros(long maxMicros) {
    this.maxMicros = maxMicros;
  }
}
