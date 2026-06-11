package org.ebean.monitor.mcp.tools;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import jakarta.inject.Singleton;
import org.ebean.monitor.v1.AppsApi;
import org.ebean.monitor.v1.EnvsApi;
import org.ebean.monitor.v1.MetricsApi;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.App;
import org.ebean.monitor.v1.model.AppMetric;
import org.ebean.monitor.v1.model.AppMetricStats;
import org.ebean.monitor.v1.model.Env;
import org.ebean.monitor.v1.model.MissingPlanMetric;
import org.ebean.monitor.v1.model.PendingResponse;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-only MCP tool catalog backed by the ebean-insight {@code /v1} API.
 * <p>
 * Each tool wraps one or more {@code /v1} calls and returns the result as a JSON
 * string in an MCP {@code tools/call} text content block. Tool execution errors
 * are reported as {@code isError:true} results (so the calling model sees them)
 * rather than JSON-RPC protocol errors; an unknown tool name throws
 * {@link UnknownToolException} for the caller to map to a protocol error.
 */
@Singleton
public class InsightTools {

  private final AppsApi appsApi;
  private final EnvsApi envsApi;
  private final MetricsApi metricsApi;
  private final PlansApi plansApi;

  private final JsonType<List<App>> appList;
  private final JsonType<List<Env>> envList;
  private final JsonType<List<AppMetric>> metricList;
  private final JsonType<List<AppMetricStats>> statsList;
  private final JsonType<List<QueryPlanSummary>> planSummaryList;
  private final JsonType<List<MissingPlanMetric>> missingList;
  private final JsonType<QueryPlan> plan;
  private final JsonType<PendingResponse> pendingResponse;

  private final Map<String, McpTool> tools = new LinkedHashMap<>();

  public InsightTools(AppsApi appsApi, EnvsApi envsApi, MetricsApi metricsApi, PlansApi plansApi, Jsonb jsonb) {
    this.appsApi = appsApi;
    this.envsApi = envsApi;
    this.metricsApi = metricsApi;
    this.plansApi = plansApi;
    this.appList = listType(jsonb, App.class);
    this.envList = listType(jsonb, Env.class);
    this.metricList = listType(jsonb, AppMetric.class);
    this.statsList = listType(jsonb, AppMetricStats.class);
    this.planSummaryList = listType(jsonb, QueryPlanSummary.class);
    this.missingList = listType(jsonb, MissingPlanMetric.class);
    this.plan = jsonb.type(QueryPlan.class);
    this.pendingResponse = jsonb.type(PendingResponse.class);
    register();
  }

  /** Tool definitions for the MCP {@code tools/list} response. */
  public List<Map<String, Object>> definitions() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (McpTool tool : tools.values()) {
      Map<String, Object> def = new LinkedHashMap<>();
      def.put("name", tool.name());
      def.put("description", tool.description());
      def.put("inputSchema", tool.inputSchema());
      list.add(def);
    }
    return list;
  }

  /**
   * Execute a tool and return the MCP {@code tools/call} result ({@code content}
   * + {@code isError}). Execution failures become {@code isError:true} results;
   * an unknown name throws {@link UnknownToolException}.
   */
  public Map<String, Object> call(String name, Map<String, Object> arguments) {
    McpTool tool = tools.get(name);
    if (tool == null) {
      throw new UnknownToolException(name);
    }
    try {
      return content(tool.handler().handle(arguments), false);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      return content("Error: " + message, true);
    }
  }

  private void register() {
    add("apps", "List known applications reporting to ebean-insight.",
        new Schema()
            .prop("activeWithinMinutes", "integer", "Only apps active within the last N minutes.")
            .prop("activeWithinHours", "integer", "Only apps active within the last N hours."),
        a -> appList.toJson(appsApi.listApps(lng(a, "activeWithinMinutes"), lng(a, "activeWithinHours"))));

    add("envs", "List known environments.",
        new Schema(),
        a -> envList.toJson(envsApi.listEnvs()));

    add("metrics", "List the metrics known for an application.",
        new Schema()
            .req("app", "string", "Application name.")
            .prop("label", "string", "Filter by query label.")
            .prop("planCapable", "boolean", "Filter to plan-capable metrics only.")
            .prop("limit", "integer", "Maximum rows."),
        a -> metricList.toJson(metricsApi.listAppMetrics(
            reqStr(a, "app"), str(a, "label"), bool(a, "planCapable"), intg(a, "limit"))));

    add("top", "Top metrics by total/mean/max time or call count over a recent window. "
            + "Ranks across all apps unless 'app' is given.",
        new Schema()
            .prop("app", "string", "Limit to one application.")
            .prop("orderBy", "string", "Rank by: total, mean, max, count (default total).")
            .prop("env", "string", "Limit to one environment.")
            .prop("limit", "integer", "Maximum rows.")
            .prop("sinceMinutes", "integer", "Window size in minutes.")
            .prop("sinceHours", "integer", "Window size in hours.")
            .prop("planCapable", "boolean", "Filter to plan-capable metrics only."),
        a -> {
          String app = str(a, "app");
          if (app != null && !app.isBlank()) {
            return statsList.toJson(metricsApi.topAppMetrics(app, str(a, "orderBy"),
                lng(a, "sinceMinutes"), lng(a, "sinceHours"), intg(a, "limit"), bool(a, "planCapable"), str(a, "env")));
          }
          return statsList.toJson(metricsApi.topMetrics(str(a, "orderBy"),
              lng(a, "sinceMinutes"), lng(a, "sinceHours"), intg(a, "limit"), bool(a, "planCapable"), str(a, "env")));
        });

    add("plans", "List recently captured query plans.",
        new Schema()
            .prop("app", "string", "Filter by application.")
            .prop("env", "string", "Filter by environment.")
            .prop("label", "string", "Filter by query label.")
            .prop("hash", "string", "Filter by plan hash.")
            .prop("sinceMinutes", "integer", "Window size in minutes.")
            .prop("sinceHours", "integer", "Window size in hours.")
            .prop("limit", "integer", "Maximum rows."),
        a -> planSummaryList.toJson(plansApi.listPlans(
            str(a, "app"), str(a, "env"), str(a, "label"), str(a, "hash"),
            lng(a, "sinceMinutes"), lng(a, "sinceHours"), intg(a, "limit"))));

    add("plan", "Fetch a single captured query plan by id (SQL, bind values, plan text).",
        new Schema().req("id", "integer", "Plan id."),
        a -> plan.toJson(plansApi.getPlan(reqLong(a, "id"))));

    add("missing-plans", "Plan-capable metrics with no recently captured query plan. "
            + "Ranks across all apps unless 'app' is given.",
        new Schema()
            .prop("app", "string", "Limit to one application.")
            .prop("orderBy", "string", "Rank by: total, mean, max, count (default total).")
            .prop("sinceMinutes", "integer", "Window size in minutes.")
            .prop("sinceHours", "integer", "Window size in hours.")
            .prop("olderThanMinutes", "integer", "Only metrics whose last plan is older than N minutes (or never).")
            .prop("olderThanHours", "integer", "Only metrics whose last plan is older than N hours (or never).")
            .prop("limit", "integer", "Maximum rows."),
        a -> {
          String app = str(a, "app");
          if (app != null && !app.isBlank()) {
            return missingList.toJson(metricsApi.listMissingPlans(app, str(a, "orderBy"),
                lng(a, "sinceMinutes"), lng(a, "sinceHours"),
                lng(a, "olderThanMinutes"), lng(a, "olderThanHours"), intg(a, "limit")));
          }
          return missingList.toJson(metricsApi.topMissingPlans(str(a, "orderBy"),
              lng(a, "sinceMinutes"), lng(a, "sinceHours"),
              lng(a, "olderThanMinutes"), lng(a, "olderThanHours"), intg(a, "limit")));
        });

    add("capture", "Request a fresh query-plan capture for a metric. "
            + "WRITE OPERATION: this asks the target application to EXPLAIN its next execution of "
            + "the query (identified by hash) and report the plan back. Use 'plans' afterwards to "
            + "retrieve the captured plan. Find the hash via 'metrics', 'top' or 'missing-plans'.",
        new Schema()
            .req("app", "string", "Application name.")
            .req("hash", "string", "Metric/plan hash to capture (the metric 'key').")
            .prop("env", "string", "Limit the capture request to one environment."),
        a -> pendingResponse.toJson(plansApi.requestPlanCapture(
            reqStr(a, "app"), reqStr(a, "hash"), str(a, "env"))));
  }

  private void add(String name, String description, Schema schema, McpTool.Handler handler) {
    tools.put(name, new McpTool(name, description, schema.build(), handler));
  }

  private static Map<String, Object> content(String text, boolean isError) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "text");
    block.put("text", text);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("content", List.of(block));
    result.put("isError", isError);
    return result;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <T> JsonType<List<T>> listType(Jsonb jsonb, Class<T> cls) {
    return (JsonType) jsonb.type(Types.listOf(cls));
  }

  // --- argument extraction helpers (JSON values arrive as String/Number/Boolean) ---

  private static String str(Map<String, Object> a, String key) {
    return a != null && a.get(key) instanceof String s ? s : null;
  }

  private static Boolean bool(Map<String, Object> a, String key) {
    return a != null && a.get(key) instanceof Boolean b ? b : null;
  }

  private static Long lng(Map<String, Object> a, String key) {
    return a != null && a.get(key) instanceof Number n ? n.longValue() : null;
  }

  private static Integer intg(Map<String, Object> a, String key) {
    return a != null && a.get(key) instanceof Number n ? n.intValue() : null;
  }

  private static String reqStr(Map<String, Object> a, String key) {
    String value = str(a, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: " + key);
    }
    return value;
  }

  private static long reqLong(Map<String, Object> a, String key) {
    Long value = lng(a, key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required argument: " + key);
    }
    return value;
  }

  /** Minimal JSON-Schema object builder for tool input schemas. */
  private static final class Schema {
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    Schema prop(String name, String type, String description) {
      properties.put(name, Map.of("type", type, "description", description));
      return this;
    }

    Schema req(String name, String type, String description) {
      prop(name, type, description);
      required.add(name);
      return this;
    }

    Map<String, Object> build() {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "object");
      schema.put("properties", properties);
      if (!required.isEmpty()) {
        schema.put("required", required);
      }
      return schema;
    }
  }
}
