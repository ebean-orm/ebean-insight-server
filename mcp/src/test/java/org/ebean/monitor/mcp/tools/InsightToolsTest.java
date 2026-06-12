package org.ebean.monitor.mcp.tools;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightToolsTest {

  private final Jsonb jsonb = Jsonb.builder().build();
  @SuppressWarnings({"unchecked", "rawtypes"})
  private final JsonType<Map<String, Object>> mapType = (JsonType) jsonb.type(Map.class);

  private final TestApis apis = new TestApis();
  private final InsightTools tools =
      new InsightTools(apis.apps, apis.envs, apis.metrics, apis.plans, jsonb);

  @SuppressWarnings("unchecked")
  private String textOf(Map<String, Object> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  @Test
  void definitions_listAllTools() {
    List<String> names = tools.definitions().stream().map(d -> (String) d.get("name")).toList();
    assertThat(names).containsExactly(
        "apps", "envs", "metrics", "top", "plans", "plan", "missing-plans", "capture",
        "pending", "changes", "change", "trend", "metric", "stats");
  }

  @Test
  @SuppressWarnings("unchecked")
  void definitions_metricsRequiresApp_appsHasNoRequired() {
    Map<String, Object> metrics = byName("metrics");
    Map<String, Object> schema = (Map<String, Object>) metrics.get("inputSchema");
    assertThat(schema.get("type")).isEqualTo("object");
    assertThat((List<String>) schema.get("required")).containsExactly("app");

    Map<String, Object> apps = byName("apps");
    Map<String, Object> appsSchema = (Map<String, Object>) apps.get("inputSchema");
    assertThat(appsSchema).doesNotContainKey("required");
  }

  private Map<String, Object> byName(String name) {
    return tools.definitions().stream()
        .filter(d -> name.equals(d.get("name"))).findFirst().orElseThrow();
  }

  @Test
  void apps_passesActiveWindowArgs_andReturnsJson() {
    Map<String, Object> result = tools.call("apps", Map.of("activeWithinMinutes", 30));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(textOf(result)).contains("central-access");
    assertThat(apis.args("listApps")).containsExactly(30L, null);
  }

  @Test
  void envs_returnsJsonArray() {
    Map<String, Object> result = tools.call("envs", null);
    assertThat(apis.called("listEnvs")).isTrue();
    assertThat(textOf(result)).contains("test").contains("dev");
  }

  @Test
  void metrics_passesAllArgs() {
    tools.call("metrics", Map.of("app", "central-access", "label", "orm.X", "planCapable", true, "limit", 50));
    assertThat(apis.args("listAppMetrics")).containsExactly("central-access", null, "orm.X", null, null, true, 50);
  }

  @Test
  void metrics_missingApp_isError() {
    Map<String, Object> result = tools.call("metrics", Map.of());
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(textOf(result)).contains("app");
    assertThat(apis.called("listAppMetrics")).isFalse();
  }

  @Test
  void top_withApp_callsTopAppMetrics() {
    tools.call("top", Map.of("app", "central-access", "orderBy", "mean", "limit", 10));
    assertThat(apis.called("topAppMetrics")).isTrue();
    assertThat(apis.called("topMetrics")).isFalse();
    Object[] a = apis.args("topAppMetrics");
    assertThat(a[0]).isEqualTo("central-access");
    assertThat(a[5]).isEqualTo("mean");
    assertThat(a[8]).isEqualTo(10);
  }

  @Test
  void top_withoutApp_callsTopMetricsAcrossAllApps() {
    tools.call("top", Map.of("orderBy", "total"));
    assertThat(apis.called("topMetrics")).isTrue();
    assertThat(apis.called("topAppMetrics")).isFalse();
  }

  @Test
  void plans_passesFilters() {
    tools.call("plans", Map.of("app", "central-access", "env", "test", "limit", 5));
    Object[] a = apis.args("listPlans");
    assertThat(a[0]).isEqualTo("central-access");
    assertThat(a[1]).isEqualTo("test");
    assertThat(a[6]).isEqualTo(5);
  }

  @Test
  void plan_passesIdAsLong() {
    Map<String, Object> result = tools.call("plan", Map.of("id", 15));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("getPlan")).containsExactly(15L);
    assertThat(textOf(result)).contains("\"id\":15");
  }

  @Test
  void plan_missingId_isError() {
    Map<String, Object> result = tools.call("plan", Map.of());
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(apis.called("getPlan")).isFalse();
  }

  @Test
  void missingPlans_withApp_callsListMissingPlans() {
    tools.call("missing-plans", Map.of("app", "central-access"));
    assertThat(apis.called("listMissingPlans")).isTrue();
    assertThat(apis.called("topMissingPlans")).isFalse();
  }

  @Test
  void missingPlans_withoutApp_callsTopMissingPlans() {
    tools.call("missing-plans", Map.of("limit", 20));
    assertThat(apis.called("topMissingPlans")).isTrue();
    assertThat(apis.called("listMissingPlans")).isFalse();
  }

  @Test
  void unknownTool_throws() {
    assertThatThrownBy(() -> tools.call("nope", Map.of()))
        .isInstanceOf(UnknownToolException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void capture_passesAppHashEnv() {
    Map<String, Object> result = tools.call("capture",
        Map.of("app", "central-access", "hash", "abc123", "env", "test"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("requestPlanCapture")).containsExactly("central-access", "abc123", "test");
    assertThat(textOf(result)).contains("\"pending\":1");
  }

  @Test
  void capture_missingHash_isError() {
    Map<String, Object> result = tools.call("capture", Map.of("app", "central-access"));
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(apis.called("requestPlanCapture")).isFalse();
  }

  @Test
  void capture_requiresAppAndHashInSchema() {
    Map<String, Object> capture = tools.definitions().stream()
        .filter(d -> "capture".equals(d.get("name"))).findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) capture.get("inputSchema");
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertThat(required).containsExactlyInAnyOrder("app", "hash");
  }

  @Test
  void pending_passesAppEnv() {
    Map<String, Object> result = tools.call("pending", Map.of("app", "central-access", "env", "test"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("listPendingPlans")).containsExactly("central-access", "test");
  }

  @Test
  void pending_noArgs_passesNulls() {
    tools.call("pending", Map.of());
    assertThat(apis.args("listPendingPlans")).containsExactly(null, null);
  }

  @Test
  void changes_passesAllFilters() {
    tools.call("changes", Map.of("app", "central-access", "env", "test", "hash", "h1",
        "changeType", "CHANGED", "sinceHours", 24, "limit", 10));
    assertThat(apis.args("listPlanChanges"))
        .containsExactly("central-access", "test", "h1", "CHANGED", null, 24L, 10);
  }

  @Test
  void change_passesId() {
    Map<String, Object> result = tools.call("change", Map.of("id", 7));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("getPlanChange")).containsExactly(7L);
    assertThat(textOf(result)).contains("\"changeType\":\"CHANGED\"");
  }

  @Test
  void change_missingId_isError() {
    Map<String, Object> result = tools.call("change", Map.of());
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(apis.called("getPlanChange")).isFalse();
  }

  @Test
  void trend_passesAppHashWindowEnv() {
    Map<String, Object> result = tools.call("trend", Map.of("app", "central-access",
        "hash", "abc123", "sinceHours", 6, "env", "test"));
    assertThat(result.get("isError")).isEqualTo(false);
    assertThat(apis.args("getMetricTimeseries"))
        .containsExactly("central-access", "abc123", null, 6L, "test");
  }

  @Test
  void trend_missingHash_isError() {
    Map<String, Object> result = tools.call("trend", Map.of("app", "central-access"));
    assertThat(result.get("isError")).isEqualTo(true);
    assertThat(apis.called("getMetricTimeseries")).isFalse();
  }

  @Test
  void metric_passesAppHash() {
    tools.call("metric", Map.of("app", "central-access", "hash", "abc123"));
    assertThat(apis.args("getMetricByHash")).containsExactly("central-access", "abc123");
  }

  @Test
  void stats_passesAppHashWindowEnv() {
    tools.call("stats", Map.of("app", "central-access", "hash", "abc123",
        "sinceMinutes", 30, "env", "test"));
    assertThat(apis.args("getMetricStatsByHash"))
        .containsExactly("central-access", "abc123", 30L, null, "test");
  }
}
