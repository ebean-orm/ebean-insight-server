package org.ebean.monitor.mcp.tools;

import io.avaje.config.Config;
import io.avaje.http.client.HttpClient;
import io.avaje.http.client.HttpClientRequest;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.http.client.RequestIntercept;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.httpclient.AppsApiHttpClient;
import org.ebean.monitor.v1.httpclient.EnvsApiHttpClient;
import org.ebean.monitor.v1.httpclient.MetricsApiHttpClient;
import org.ebean.monitor.v1.httpclient.PlansApiHttpClient;

/**
 * Builds the typed ebean-insight {@code /v1} API clients the MCP tools call.
 * <p>
 * All four clients share one avaje {@link HttpClient} pointing at
 * {@code insight.url}. When {@code insight.api.key} is set it is sent as
 * {@code Authorization: Bearer <key>} (the read-side API key introduced on the
 * server). The underlying {@code HttpClient} is intentionally <em>not</em>
 * exposed as a bean to avoid colliding with the test-server client provided by
 * avaje-jex-test under {@code @InjectTest}.
 */
@Factory
class InsightApiClients {

  private final HttpClient http;

  InsightApiClients(Jsonb jsonb) {
    this.http = build(jsonb);
  }

  @Bean
  AppsApi appsApi() {
    return new AppsApiHttpClient(http);
  }

  @Bean
  EnvsApi envsApi() {
    return new EnvsApiHttpClient(http);
  }

  @Bean
  MetricsApi metricsApi() {
    return new MetricsApiHttpClient(http);
  }

  @Bean
  PlansApi plansApi() {
    return new PlansApiHttpClient(http);
  }

  private static HttpClient build(Jsonb jsonb) {
    HttpClient.Builder builder = HttpClient.builder()
        .baseUrl(Config.get("insight.url", "http://localhost:8091"))
        .bodyAdapter(new JsonbBodyAdapter(jsonb));

    String key = Config.getNullable("insight.api.key");
    if (key != null && !key.isBlank()) {
      String bearer = "Bearer " + key.trim();
      builder.requestIntercept(new RequestIntercept() {
        @Override
        public void beforeRequest(HttpClientRequest request) {
          request.header("Authorization", bearer);
        }
      });
    }
    return builder.build();
  }
}
