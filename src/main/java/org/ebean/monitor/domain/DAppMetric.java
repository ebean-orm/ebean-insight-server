package org.ebean.monitor.domain;

import io.ebean.annotation.Cache;
import io.ebean.annotation.DbForeignKey;
import io.ebean.annotation.Identity;
import io.ebean.annotation.Index;
import io.ebean.annotation.Length;
import io.ebean.annotation.NotNull;
import org.ebean.monitor.domain.finder.DAppMetricFinder;

import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The application metric.
 * <p>
 * The unique key for metric is a combination of name + type + hash + loc.
 * </p>
 */
@Cache(nearCache = true, naturalKey = {"app", "key"})
@Entity
@Identity(start = 1000)
@Table(name = "ebean_insight.app_metric")
public class DAppMetric extends BaseDomain {

  public static final DAppMetricFinder find = new DAppMetricFinder();

  /**
   * The Application this metric belongs to or null for "global" metrics.
   */
  @DbForeignKey(noConstraint = true)
  @ManyToOne
  private final DApp app;

  /**
   * Derived as hash or concatenation of name + type.
   * Used as unique lookup as part of ingestion.
   */
  @Index
  @NotNull
  @Length(40)
  private final String key;

  /**
   * The metric name.
   */
  @Index
  @Length(300)
  private final String name;

  /**
   * The derived "rollup group" this metric will aggregate into.
   */
  @Length(300)
  private String rollupGroup;

  /**
   * The code location if supplied. Expected to be class and line of code.
   */
  @Length(300)
  private String loc;

  /**
   * The SQL of the metric if supplied.
   */
  @Lob
  private String sql;

  public DAppMetric(DApp app, String key, String name) {
    this.app = app;
    this.key = key;
    this.name = name;
  }

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public DApp getApp() {
    return app;
  }

  public String getRollupGroup() {
    return rollupGroup;
  }

  public void setRollupGroup(String rollupGroup) {
    this.rollupGroup = rollupGroup;
  }

  public String getLoc() {
    return loc;
  }

  public void setLoc(String loc) {
    this.loc = loc;
  }

  public String getSql() {
    return sql;
  }

  public void setSql(String sql) {
    this.sql = sql;
  }
}
