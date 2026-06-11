package org.ebean.monitor.mcp.tools;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Inject;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full end-to-end integration test of the MCP tool path.
 * <p>
 * {@code @InjectTest} boots the whole MCP server on a random port (via
 * avaje-jex-test) and injects the {@link HttpClient} bound to it. The four
 * {@code /v1} API beans are overridden with {@link TestApis} test doubles, so
 * {@code InsightTools} (the real bean) is wired with them — no upstream
 * ebean-insight server is required. Each test drives a real
 * {@code POST /mcp tools/call} over HTTP and asserts both the JSON-RPC response
 * and that the underlying API was invoked with the mapped arguments.
 */
@InjectTest
class McpToolsIntegrationTest {

  private static final String BEARER = "Bearer test-secret";

  private static final Jsonb JSONB = Jsonb.builder().build();
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final JsonType<Map<String, Object>> MAP = (JsonType) JSONB.type(Map.class);

  private final TestApis apis = new TestApis();

  // Field-initialised @Inject test doubles override the real /v1 client beans.
  @Inject AppsApi appsApi = apis.apps;
  @Inject EnvsApi envsApi = apis.envs;
  @Inject MetricsApi metricsApi = apis.metrics;
  @Inject PlansApi plansApi = apis.plans;

  @Inject HttpClient httpClient;

  private HttpResponse<String> toolsCall(String name, String argumentsJson) {
    String body = """
        {"jsonrpc":"2.0","id":1,"method":"tools/call",
         "params":{"name":"%s","arguments":%s}}
        """.formatted(name, argumentsJson);
    return httpClient.request()
        .path("mcp")
        .header("Authorization", BEARER)
        .header("Content-Type", "application/json")
        .body(body)
        .POST()
        .asString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resultOf(HttpResponse<String> res) {
    assertThat(res.statusCode()).isEqualTo(200);
    Map<String, Object> resp = MAP.fromJson(res.body());
    return (Map<String, Object>) resp.get("result");
  }

  @SuppressWarnings("unchecked")
  private String textOf(Map<String, Object> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  @Test
  void apps_fullStack_returnsDataAndCallsApi() {
    Map<String, Object> result = resultOf(toolsCall("apps", "{\"activeWithinHours\":24}"));

    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(textOf(result)).contains("central-access");
    // arg mapping: activeWithinMinutes=null, activeWithinHours=24
    assertThat(apis.args("listApps")).containsExactly(null, 24L);
  }

  @Test
  void envs_fullStack() {
    Map<String, Object> result = resultOf(toolsCall("envs", "{}"));
    assertThat(apis.called("listEnvs")).isTrue();
    assertThat(textOf(result)).contains("test").contains("dev");
  }

  @Test
  void top_fullStack_withoutApp_callsTopMetrics() {
    Map<String, Object> result = resultOf(toolsCall("top", "{\"orderBy\":\"total\",\"limit\":5}"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.called("topMetrics")).isTrue();
    Object[] a = apis.args("topMetrics");
    assertThat(a[0]).isEqualTo("total");
    assertThat(a[3]).isEqualTo(5);
  }

  @Test
  void plan_fullStack_passesIdAsLong() {
    Map<String, Object> result = resultOf(toolsCall("plan", "{\"id\":15}"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("getPlan")).containsExactly(15L);
    assertThat(textOf(result)).contains("\"id\":15");
  }

  @Test
  void metrics_missingRequiredApp_isErrorResult() {
    Map<String, Object> result = resultOf(toolsCall("metrics", "{}"));
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(apis.called("listAppMetrics")).isFalse();
  }

  @Test
  void capture_fullStack_requestsCapture() {
    Map<String, Object> result = resultOf(
        toolsCall("capture", "{\"app\":\"central-access\",\"hash\":\"abc123\",\"env\":\"test\"}"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("requestPlanCapture")).containsExactly("central-access", "abc123", "test");
    assertThat(textOf(result)).contains("\"pending\":1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void resourcesRead_fullStack_returnsPlanMarkdown() {
    String body = """
        {"jsonrpc":"2.0","id":1,"method":"resources/read",
         "params":{"uri":"insight://plan/15"}}
        """;
    HttpResponse<String> res = httpClient.request()
        .path("mcp")
        .header("Authorization", BEARER)
        .header("Content-Type", "application/json")
        .body(body)
        .POST()
        .asString();

    Map<String, Object> result = resultOf(res);
    List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("contents");
    assertThat((String) contents.get(0).get("text")).contains("# Query plan 15");
    assertThat(apis.args("getPlan")).containsExactly(15L);
  }
}
