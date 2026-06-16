package org.ebean.monitor.api;

import io.avaje.jsonb.Json;
import io.avaje.recordbuilder.RecordBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics to ingest.
 *
 * @param v              Metric payload format version. {@code 0}/absent means legacy v1 (flat
 *                       names, no tags); {@code 2} means v2 (canonical family {@code name} +
 *                       delimited {@code tags} string on each {@link MetricData}).
 * @param startEventTime Optional start of the metric window provided by the client (epoch
 *                       millis, typically the {@code eventTime} of the prior successful send,
 *                       or the reporter start time on the first send). When non-zero, the OTLP
 *                       forwarder uses this as {@code startTimeUnixNano} so consecutive deltas
 *                       are truly disjoint and survive
 *                       {@code otelcol.processor.deltatocumulative}.
 * @param eventTime      The metric collection time.
 * @param appName        The name of the application the metrics are collected for.
 * @param version        The application version. Used to determine when new versions are released.
 * @param environment    The name of the environment the metrics are collected for.
 * @param instanceId     An Id for the server instance (eg. Kubernetes pod name).
 * @param metrics        General metrics for the application (Rest endpoints, JVM metrics,
 *                       CGroup metrics etc).
 * @param dbs            The database metrics.
 * @param resAttrs       Optional extra resource attributes — typically parsed from the standard
 *                       {@code OTEL_RESOURCE_ATTRIBUTES} env var by the client. Forwarded
 *                       verbatim to the OTLP {@code resource.attributes} list. The reserved
 *                       per-app fields ({@code service.name}, {@code service.version},
 *                       {@code service.instance.id}, {@code deployment.environment.name})
 *                       sourced from the dedicated header fields above take precedence over any
 *                       matching keys here.
 */
@RecordBuilder
@Json
public record MetricRequest(
  int v,
  long startEventTime,
  long eventTime,
  String appName,
  String version,
  String environment,
  String instanceId,
  List<MetricData> metrics,
  List<MetricDbData> dbs,
  Map<String, String> resAttrs
) {

  public MetricRequest {
    metrics = (metrics == null) ? new ArrayList<>() : metrics;
    dbs = (dbs == null) ? new ArrayList<>() : dbs;
    resAttrs = (resAttrs == null) ? new LinkedHashMap<>() : resAttrs;
  }

  /**
   * Create a new builder.
   */
  public static MetricRequestBuilder builder() {
    return MetricRequestBuilder.builder();
  }
}
