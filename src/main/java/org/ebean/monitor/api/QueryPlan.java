package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.time.Instant;

/**
 * A captured query plan including SQL, bind values and the raw plan text.
 */
@Json
public class QueryPlan {

  public long id;
  public String hash;
  public String label;
  public long appMetricId;
  public String envName;
  public long queryTimeMicros;
  public long captureCount;
  public long captureMicros;
  public Instant whenCaptured;
  public String sql;
  public String bind;
  public String plan;
}
