package org.ebean.monitor.mcp.protocol;

import io.avaje.http.client.HttpClient;
import io.avaje.inject.test.InjectTest;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the fully wired MCP server (controller + bearer-auth filter + DI)
 * via {@code @InjectTest}: avaje-jex-test boots the server and provides the
 * {@link HttpClient}. Auth is enabled by {@code application-test.yaml}
 * ({@code mcp.tokens=test:test-secret}).
 */
@InjectTest
class McpControllerTest {

  private static final String BEARER = "Bearer test-secret";

  private static final Jsonb JSONB = Jsonb.builder().build();
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final JsonType<Map<String, Object>> MAP = (JsonType) JSONB.type(Map.class);

  private static final String INITIALIZE = """
      {"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-06-18","capabilities":{},
                 "clientInfo":{"name":"test","version":"1.0"}}}
      """;

  @Inject HttpClient httpClient;

  private HttpResponse<String> postMcp(String body, boolean withAuth) {
    var request = httpClient.request()
        .path("mcp")
        .header("Content-Type", "application/json");
    if (withAuth) {
      request.header("Authorization", BEARER);
    }
    return request.body(body).POST().asString();
  }

  @Test
  void initialize_withToken_completesHandshake() {
    HttpResponse<String> res = postMcp(INITIALIZE, true);
    assertThat(res.statusCode()).isEqualTo(200);

    Map<String, Object> resp = MAP.fromJson(res.body());
    assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
    assertThat(resp.get("id")).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
    assertThat(result).containsKey("serverInfo");
  }

  @Test
  void initialize_withoutToken_401() {
    assertThat(postMcp(INITIALIZE, false).statusCode()).isEqualTo(401);
  }

  @Test
  void notification_withToken_accepted() {
    HttpResponse<String> res = postMcp("""
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        """, true);
    assertThat(res.statusCode()).isEqualTo(202);
    assertThat(res.body()).isEmpty();
  }

  @Test
  void healthLiveness_isOpenWithoutToken() {
    HttpResponse<String> res = httpClient.request()
        .path("health").path("liveness")
        .GET().asString();
    assertThat(res.statusCode()).isEqualTo(200);
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsList_withToken_returnsCatalog() {
    HttpResponse<String> res = postMcp("""
        {"jsonrpc":"2.0","id":5,"method":"tools/list"}
        """, true);
    assertThat(res.statusCode()).isEqualTo(200);

    Map<String, Object> resp = MAP.fromJson(res.body());
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
    assertThat(tools).extracting(t -> t.get("name"))
        .contains("apps", "top", "plans", "plan", "missing-plans");
  }
}
