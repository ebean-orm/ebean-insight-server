package org.ebean.monitor.api;

import io.avaje.jsonb.Json;
import io.avaje.recordbuilder.RecordBuilder;

/**
 * A timed metric with combined count, mean, max and total.
 * <p>
 * Times are generally expected to be in microseconds.
 * </p>
 *
 * @param name  The metric name.
 * @param hash  Optional hash used to identify a query metric.
 * @param loc   Optional location attribute. Code location of query or {@code @Transactional}.
 * @param sql   SQL only supplied for SQL query metrics.
 * @param tags  Optional canonical tags for v2 payloads, as a sorted delimited
 *              {@code "key:value,key2:value2"} string (e.g.
 *              {@code "kind:orm,label:Customer.findList,type:Customer"}). Null/absent for
 *              legacy v1 payloads.
 * @param count Timed metric count.
 * @param mean  Timed metric mean.
 * @param max   Timed metric max.
 * @param total Timed metric total.
 * @param value Non timed metrics just use the value attribute.
 */
@RecordBuilder
@Json
public record MetricData(
  String name,
  String hash,
  String loc,
  String sql,
  String tags,
  Long count,
  Long mean,
  Long max,
  Long total,
  Double value
) {

  /**
   * Create a new builder.
   */
  public static MetricDataBuilder builder() {
    return MetricDataBuilder.builder();
  }
}
