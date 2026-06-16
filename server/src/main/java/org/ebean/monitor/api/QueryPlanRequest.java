package org.ebean.monitor.api;

import io.avaje.jsonb.Json;
import io.avaje.recordbuilder.RecordBuilder;

import java.util.ArrayList;
import java.util.List;

@RecordBuilder
@Json
public record QueryPlanRequest(
  String appName,
  String environment,
  List<QPlan> plans
) {

  public QueryPlanRequest {
    plans = (plans == null) ? new ArrayList<>() : plans;
  }

  /**
   * Create a new builder.
   */
  public static QueryPlanRequestBuilder builder() {
    return QueryPlanRequestBuilder.builder();
  }

  /**
   * A single captured query plan.
   *
   * @param hash             The hash of the plan.
   * @param label            The label of the query (prefix-free, e.g. {@code Customer.findList}).
   *                         Older clients send the flat ebean label (e.g.
   *                         {@code orm.Customer.findList}).
   * @param kind             The query kind tag (e.g. {@code orm}, {@code dto}, {@code sql}).
   *                         May be null for older clients that only send the flat {@code label}.
   * @param type             The bean type tag (e.g. {@code Customer}). May be null for older
   *                         clients or raw-SQL plans with no bean type.
   * @param sql              The sql of the query.
   * @param bind             A description of the bind values.
   * @param plan             The raw plan.
   * @param queryTimeMicros  The query execution time associated with the bind values capture.
   * @param captureCount     The total count of times bind capture has occurred.
   * @param captureMicros    The time taken to capture this plan in microseconds.
   * @param whenCaptured     The instant when the bind values were captured.
   */
  @RecordBuilder
  @Json
  public record QPlan(
    String hash,
    String label,
    String kind,
    String type,
    String sql,
    String bind,
    String plan,
    long queryTimeMicros,
    long captureCount,
    long captureMicros,
    String whenCaptured
  ) {

    /**
     * Create a new builder.
     */
    public static QueryPlanRequest$QPlanBuilder builder() {
      return QueryPlanRequest$QPlanBuilder.builder();
    }
  }
}
