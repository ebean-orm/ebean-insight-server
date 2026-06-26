package org.ebean.monitor.ingest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class TagStringTest {

  @Test
  void parsesCanonicalString() {
    Map<String, String> map = TagString.parse("kind:orm,label:Customer.findList,type:Customer");
    assertThat(map).containsExactly(
      entry("kind", "orm"),
      entry("label", "Customer.findList"),
      entry("type", "Customer"));
  }

  @Test
  void blankReturnsNull() {
    assertThat(TagString.parse(null)).isNull();
    assertThat(TagString.parse("")).isNull();
  }

  @Test
  void valueWithColonKeepsRemainder() {
    // only the first ':' separates key from value
    Map<String, String> map = TagString.parse("label:Foo:Bar");
    assertThat(map).containsExactly(entry("label", "Foo:Bar"));
  }
}
