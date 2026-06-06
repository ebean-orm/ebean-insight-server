package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

/**
 * A timed metric with combined count, mean, max and total.
 * <p>
 * Times are generally expected to be in microseconds.
 * </p>
 */
@Json
public class MetricData {

  /**
   * The metric name.
   */
  public String name;

  /**
   * Optional hash used to identify a query metric.
   */
  public String hash;

  /**
   * Optional location attribute. Code location of query or @Transactional.
   */
  public String loc;

  /**
   * SQL only supplied for SQL query metrics.
   */
  public String sql;

  /**
   * Timed metrics have attributes of count, mean, max and total.
   */
  public Long count;
  public Long mean;
  public Long max;
  public Long total;

  /**
   * Non timed metrics just use the value attribute.
   */
  public Double value;
}
