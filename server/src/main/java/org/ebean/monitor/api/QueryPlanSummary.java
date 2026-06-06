package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.time.Instant;

/**
 * Summary of a captured query plan (no SQL/bind/plan text).
 */
@Json
public class QueryPlanSummary {

  public long id;
  public long appMetricId;
  public String envName;
  public String hash;
  public String label;
  public long queryTimeMicros;
  public long captureCount;
  public Instant whenCaptured;
}
