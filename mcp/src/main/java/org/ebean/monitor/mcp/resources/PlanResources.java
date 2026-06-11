package org.ebean.monitor.mcp.resources;

import jakarta.inject.Singleton;
import org.ebean.monitor.v1.PlansApi;
import org.ebean.monitor.v1.model.QueryPlan;
import org.ebean.monitor.v1.model.QueryPlanSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes captured query plans as MCP resources so an agent can attach a plan's
 * full SQL / bind / plan text as context via {@code resources/read} rather than
 * only through {@code tools/call}.
 * <p>
 * Each plan is addressed by the URI {@code insight://plan/{id}}. {@code list()}
 * enumerates recent plans; {@code read(uri)} fetches one and renders it as
 * markdown. A resource template advertises the {@code insight://plan/{id}} shape
 * so clients know any plan id is addressable.
 */
@Singleton
public class PlanResources {

  static final String SCHEME_PREFIX = "insight://plan/";
  private static final String MIME_MARKDOWN = "text/markdown";
  private static final int LIST_LIMIT = 50;

  private final PlansApi plansApi;

  public PlanResources(PlansApi plansApi) {
    this.plansApi = plansApi;
  }

  /** Recent captured plans as MCP resource descriptors. */
  public List<Map<String, Object>> list() {
    List<QueryPlanSummary> plans =
        plansApi.listPlans(null, null, null, null, null, null, LIST_LIMIT);
    List<Map<String, Object>> resources = new ArrayList<>();
    for (QueryPlanSummary p : plans) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("uri", SCHEME_PREFIX + p.id());
      r.put("name", "plan-" + p.id());
      r.put("title", p.label() + " (" + p.envName() + ")");
      r.put("description", "Captured query plan for " + p.label()
          + " in env " + p.envName() + " (hash " + p.hash() + ").");
      r.put("mimeType", MIME_MARKDOWN);
      resources.add(r);
    }
    return resources;
  }

  /** The {@code insight://plan/{id}} resource template. */
  public List<Map<String, Object>> templates() {
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("uriTemplate", SCHEME_PREFIX + "{id}");
    template.put("name", "query-plan");
    template.put("title", "ebean-insight query plan");
    template.put("description", "A captured query plan by id. Find plan ids via the 'plans' tool.");
    template.put("mimeType", MIME_MARKDOWN);
    return List.of(template);
  }

  /**
   * Read one plan resource, returning the MCP {@code resources/read} result
   * ({@code contents} array with a single markdown text entry).
   *
   * @throws UnknownResourceException if the URI is not a valid plan URI.
   */
  public Map<String, Object> read(String uri) {
    long id = parseId(uri);
    QueryPlan plan = plansApi.getPlan(id);

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("uri", uri);
    entry.put("mimeType", MIME_MARKDOWN);
    entry.put("text", markdown(plan));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("contents", List.of(entry));
    return result;
  }

  private long parseId(String uri) {
    if (uri == null || !uri.startsWith(SCHEME_PREFIX)) {
      throw new UnknownResourceException(uri);
    }
    try {
      return Long.parseLong(uri.substring(SCHEME_PREFIX.length()));
    } catch (NumberFormatException e) {
      throw new UnknownResourceException(uri);
    }
  }

  private static String markdown(QueryPlan p) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Query plan ").append(p.id()).append("\n\n");
    sb.append("- **label:** ").append(p.label()).append('\n');
    sb.append("- **env:** ").append(p.envName()).append('\n');
    sb.append("- **hash:** ").append(p.hash()).append('\n');
    sb.append("- **queryTime:** ").append(p.queryTimeMicros()).append("us\n");
    sb.append("- **captured:** ").append(p.whenCaptured()).append("\n\n");
    if (p.sql() != null) {
      sb.append("## SQL\n\n```sql\n").append(p.sql()).append("\n```\n\n");
    }
    if (p.bind() != null) {
      sb.append("## Bind\n\n```\n").append(p.bind()).append("\n```\n\n");
    }
    if (p.plan() != null) {
      sb.append("## Plan\n\n```\n").append(p.plan()).append("\n```\n");
    }
    return sb.toString();
  }
}
