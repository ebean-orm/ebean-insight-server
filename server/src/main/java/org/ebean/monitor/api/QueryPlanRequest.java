package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.util.ArrayList;
import java.util.List;

@Json
public class QueryPlanRequest {

  public String appName;
  public String environment;
  public List<QPlan> plans = new ArrayList<>();

  public static class QPlan {

    /**
     * Return the hash of the plan.
     */
    public String hash;

    /**
     * Return the label of the query.
     */
    public String label;

    /**
     * Return the sql of the query.
     */
    public String sql;

    /**
     * Return a description of the bind values.
     */
    public String bind;

    /**
     * Return the raw plan.
     */
    public String plan;

    /**
     * Return the query execution time associated with the bind values capture.
     */
    public long queryTimeMicros;

    /**
     * Return the total count of times bind capture has occurred.
     */
    public long captureCount;

    /**
     * Return the time taken to capture this plan in microseconds.
     */
    public long captureMicros;

    /**
     * Return the instant when the bind values were captured.
     */
    public String whenCaptured;
  }
}
