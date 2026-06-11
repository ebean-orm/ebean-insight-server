package org.ebean.monitor.mcp.protocol;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.ebean.monitor.mcp.tools.InsightTools;
import org.ebean.monitor.mcp.tools.TestApis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonRpcTest {

  private final Jsonb jsonb = Jsonb.builder().build();
  @SuppressWarnings({"unchecked", "rawtypes"})
  private final JsonType<Map<String, Object>> mapType = (JsonType) jsonb.type(Map.class);
  private final TestApis apis = new TestApis();
  private final InsightTools tools =
      new InsightTools(apis.apps, apis.envs, apis.metrics, apis.plans, jsonb);
  private final McpJsonRpc rpc = new McpJsonRpc(new McpServer(), tools, jsonb);

  private Map<String, Object> handleToMap(String body) {
    Optional<String> response = rpc.handle(body);
    assertThat(response).isPresent();
    return mapType.fromJson(response.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  void initialize_negotiatesRequestedVersion_andReturnsServerInfo() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":1,"method":"initialize",
         "params":{"protocolVersion":"2025-06-18",
                   "capabilities":{},
                   "clientInfo":{"name":"test","version":"1.0"}}}
        """);

    assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
    assertThat(resp.get("id")).isEqualTo(1L);
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo("2025-06-18");
    assertThat(result).containsKey("capabilities");
    Map<String, Object> info = (Map<String, Object>) result.get("serverInfo");
    assertThat(info.get("name")).isEqualTo("ebean-insight-mcp");
    assertThat(info.get("version")).isEqualTo("0.1.0");
  }

  @Test
  @SuppressWarnings("unchecked")
  void initialize_unsupportedVersion_fallsBackToLatest() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":"abc","method":"initialize",
         "params":{"protocolVersion":"1.0.0"}}
        """);

    assertThat(resp.get("id")).isEqualTo("abc");
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo(McpServer.LATEST_PROTOCOL);
  }

  @Test
  void initialize_missingParams_stillNegotiatesLatest() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":2,"method":"initialize"}
        """);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertThat(result.get("protocolVersion")).isEqualTo(McpServer.LATEST_PROTOCOL);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ping_returnsEmptyResult() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":7,"method":"ping"}
        """);
    assertThat(resp.get("id")).isEqualTo(7L);
    assertThat((Map<String, Object>) resp.get("result")).isEmpty();
  }

  @Test
  void notification_hasNoResponse() {
    assertThat(rpc.handle("""
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        """)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void unknownMethod_returnsMethodNotFound() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":9,"method":"does/notExist"}
        """);
    Map<String, Object> error = (Map<String, Object>) resp.get("error");
    assertThat(error.get("code")).isEqualTo((long) McpJsonRpc.METHOD_NOT_FOUND);
    assertThat((String) error.get("message")).contains("does/notExist");
    assertThat(resp).doesNotContainKey("result");
  }

  @Test
  @SuppressWarnings("unchecked")
  void parseError_returnsParseError() {
    Map<String, Object> resp = handleToMap("not json");
    Map<String, Object> error = (Map<String, Object>) resp.get("error");
    assertThat(error.get("code")).isEqualTo((long) McpJsonRpc.PARSE_ERROR);
    assertThat(resp.get("id")).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsList_returnsCatalog() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":10,"method":"tools/list"}
        """);
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    List<Map<String, Object>> toolList = (List<Map<String, Object>>) result.get("tools");
    assertThat(toolList).extracting(t -> t.get("name"))
        .contains("apps", "envs", "metrics", "top", "plans", "plan", "missing-plans");
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsCall_invokesTool_andReturnsContent() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":11,"method":"tools/call",
         "params":{"name":"envs","arguments":{}}}
        """);
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertThat(result.get("isError")).isEqualTo(false);
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    assertThat(content.get(0).get("type")).isEqualTo("text");
    assertThat((String) content.get(0).get("text")).contains("test");
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsCall_unknownTool_returnsInvalidParams() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":12,"method":"tools/call",
         "params":{"name":"nope","arguments":{}}}
        """);
    Map<String, Object> error = (Map<String, Object>) resp.get("error");
    assertThat(error.get("code")).isEqualTo((long) McpJsonRpc.INVALID_PARAMS);
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolsCall_missingName_returnsInvalidParams() {
    Map<String, Object> resp = handleToMap("""
        {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{}}
        """);
    Map<String, Object> error = (Map<String, Object>) resp.get("error");
    assertThat(error.get("code")).isEqualTo((long) McpJsonRpc.INVALID_PARAMS);
  }
}
