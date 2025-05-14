package org.ebean.monitor;

import io.avaje.jsonb.Jsonb;
import io.ebean.test.DbJson;
import org.ebean.monitor.api.MetricRequest;

/**
 * Utility to read test resources.
 */
public class ResourceHelp {

  private static final Jsonb mapper = Jsonb.builder().build();

  public static MetricRequest metricRequest(String resourcePath) {
    final String json1 = read(resourcePath);
    return mapper.type(MetricRequest.class).fromJson(json1);
  }

  /**
   * Read the content for the given resource path.
   */
  public static String read(String resourcePath) {
    return DbJson.readResource(resourcePath);
  }

}
