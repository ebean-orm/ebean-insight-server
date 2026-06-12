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
   * True when this metric is "plan capable" - i.e. a SELECT-style query that
   * supports query plan capture. Derived from the name prefix at insert time:
   * {@code orm.} (ORM entity queries, excluding {@code orm.update.}) and
   * {@code dto.} (DtoQuery). Native-SQL DTO queries capture directly; ORM-backed
   * DTO queries ({@code Query.asDto(...)}) are flagged plan capable too but are
   * captured via their underlying {@code orm.} plan. Raw SQL and update/delete
   * metrics are not plan capable.
   */
  @NotNull
  @Index
  private final boolean planCapable;

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

  /**
   * Per-query auto-plan-capture threshold (mean duration in microseconds).
   * When non-null, overrides the global {@code autoplan.defaultThresholdMicros}.
   * Null means "use global default". Editable via SQL / future CLI / future UI.
   */
  private Long planThresholdMicros;

  public DAppMetric(DApp app, String key, String name) {
    this.app = app;
    this.key = key;
    this.name = name;
    this.planCapable = name != null
      && (name.startsWith("orm.") || name.startsWith("dto.") || name.startsWith("sql.query."))
      && !name.startsWith("orm.update.");
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

  public boolean isPlanCapable() {
    return planCapable;
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

  public Long getPlanThresholdMicros() {
    return planThresholdMicros;
  }

  public void setPlanThresholdMicros(Long planThresholdMicros) {
    this.planThresholdMicros = planThresholdMicros;
  }
}
