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
     * Return the label of the query (prefix-free, e.g. {@code Customer.findList}).
     * Older clients send the flat ebean label (e.g. {@code orm.Customer.findList}).
     */
    public String label;

    /**
     * Return the query kind tag (e.g. {@code orm}, {@code dto}, {@code sql}).
     * May be null for older clients that only send the flat {@link #label}.
     */
    public String kind;

    /**
     * Return the bean type tag (e.g. {@code Customer}).
     * May be null for older clients or raw-SQL plans with no bean type.
     */
    public String type;

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
