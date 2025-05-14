package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Database metrics.
 */
@Json
public class MetricDbData {

  /**
   * The Db name.
   */
  public String db;

  /**
   * The metrics for the database.
   */
  public List<MetricData> metrics = new ArrayList<>();
}
