package org.ebean.monitor.mcp.resources;

import org.ebean.monitor.mcp.tools.TestApis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanResourcesTest {

  private final TestApis apis = new TestApis();
  private final PlanResources resources = new PlanResources(apis.plans);

  @Test
  void list_mapsPlansToResourceUris() {
    List<Map<String, Object>> list = resources.list();
    assertThat(list).hasSize(1);
    Map<String, Object> r = list.get(0);
    assertThat(r.get("uri")).isEqualTo("insight://plan/15");
    assertThat(r.get("name")).isEqualTo("plan-15");
    assertThat(r.get("mimeType")).isEqualTo("text/markdown");
    assertThat((String) r.get("title")).contains("orm.X.find").contains("test");
  }

  @Test
  void templates_advertisesPlanTemplate() {
    List<Map<String, Object>> templates = resources.templates();
    assertThat(templates).hasSize(1);
    assertThat(templates.get(0).get("uriTemplate")).isEqualTo("insight://plan/{id}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void read_fetchesPlanAndRendersMarkdown() {
    Map<String, Object> result = resources.read("insight://plan/15");
    List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("contents");
    assertThat(contents).hasSize(1);
    Map<String, Object> entry = contents.get(0);
    assertThat(entry.get("uri")).isEqualTo("insight://plan/15");
    assertThat(entry.get("mimeType")).isEqualTo("text/markdown");
    String text = (String) entry.get("text");
    assertThat(text)
        .contains("# Query plan 15")
        .contains("## SQL")
        .contains("select 1")
        .contains("## Plan")
        .contains("Seq Scan");
    assertThat(apis.args("getPlan")).containsExactly(15L);
  }

  @Test
  void read_nonPlanUri_throws() {
    assertThatThrownBy(() -> resources.read("file:///etc/passwd"))
        .isInstanceOf(UnknownResourceException.class);
  }

  @Test
  void read_nonNumericId_throws() {
    assertThatThrownBy(() -> resources.read("insight://plan/abc"))
        .isInstanceOf(UnknownResourceException.class);
  }
}
