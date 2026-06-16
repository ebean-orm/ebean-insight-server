package org.ebean.monitor.api;

import io.avaje.jsonb.Json;
import io.avaje.recordbuilder.RecordBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Database metrics.
 *
 * @param db      The Db name.
 * @param metrics The metrics for the database.
 */
@RecordBuilder
@Json
public record MetricDbData(
  String db,
  List<MetricData> metrics
) {

  public MetricDbData {
    metrics = (metrics == null) ? new ArrayList<>() : metrics;
  }

  /**
   * Create a new builder.
   */
  public static MetricDbDataBuilder builder() {
    return MetricDbDataBuilder.builder();
  }
}
