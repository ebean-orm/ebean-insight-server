package org.ebean.monitor.api;

import io.avaje.jsonb.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics to ingest.
 */
@Json
public class MetricRequest {

  /**
   * The metric collection time.
   */
  public long eventTime;

  public String key;

  /**
   * The name of the application the metrics are collected for.
   */
  public String appName;

  /**
   * The application version. Used to determine when new versions are released.
   */
  public String version;

  /**
   * The name of the environment the metrics are collected for.
   */
  public String environment;

  /**
   * An Id for the server instance (eg. Kubernetes pod name).
   */
  public String instanceId;

  /**
   * General metrics for the application (Rest endpoints, JVM metrics, CGroup metrics etc).
   */
  public List<MetricData> metrics = new ArrayList<>();

  /**
   * The database metrics.
   */
  public List<MetricDbData> dbs = new ArrayList<>();

  /**
   * Optional extra resource attributes — typically parsed from the standard
   * {@code OTEL_RESOURCE_ATTRIBUTES} env var by the client. Forwarded verbatim
   * to the OTLP {@code resource.attributes} list. The reserved per-app fields
   * ({@code service.name}, {@code service.version}, {@code service.instance.id},
   * {@code deployment.environment.name}) sourced from the dedicated header
   * fields above take precedence over any matching keys here.
   */
  public Map<String, String> resAttrs = new LinkedHashMap<>();

}
