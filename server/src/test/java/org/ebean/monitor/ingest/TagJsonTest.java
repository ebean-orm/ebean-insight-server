package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TagJsonTest {

  @Test
  void toJson_null() {
    assertThat(TagJson.toJson(null)).isNull();
  }

  @Test
  void toJson_empty() {
    assertThat(TagJson.toJson(Map.of())).isNull();
  }

  @Test
  void toJson_singleEntry() {
    assertThat(TagJson.toJson(TagString.parse("label:CustomerController.getList")))
      .isEqualTo("{\"label\":\"CustomerController.getList\"}");
  }

  @Test
  void toJson_preservesInsertionOrder() {
    assertThat(TagJson.toJson(TagString.parse("kind:orm,type:Customer,label:Customer.findList")))
      .isEqualTo("{\"kind\":\"orm\",\"type\":\"Customer\",\"label\":\"Customer.findList\"}");
  }

  @Test
  void toJson_nullValueBecomesEmptyString() {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("k", null);
    assertThat(TagJson.toJson(tags)).isEqualTo("{\"k\":\"\"}");
  }

  @Test
  void toJson_escapesQuotesAndBackslash() {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("label", "a\"b\\c");
    assertThat(TagJson.toJson(tags)).isEqualTo("{\"label\":\"a\\\"b\\\\c\"}");
  }

  @Test
  void toJson_escapesControlChars() {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("k", "a\nb\tc");
    assertThat(TagJson.toJson(tags)).isEqualTo("{\"k\":\"a\\nb\\tc\"}");
  }
}
