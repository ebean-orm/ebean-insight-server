package org.ebean.monitor.ingest;

import org.ebean.monitor.api.MetricData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricKeyTest {

  private static MetricData metric(String name, String hash, String tags) {
    return MetricData.builder()
      .name(name)
      .hash(hash)
      .tags(tags)
      .build();
  }

  @Test
  void hashTakesPrecedence() {
    assertThat(MetricKey.of(metric("ebean.query", "h1", "kind:orm,label:X"))).isEqualTo("h1");
  }

  @Test
  void v1NameOnly_stableKey() {
    String a = MetricKey.of(metric("iud.User.save", null, null));
    String b = MetricKey.of(metric("iud.User.save", null, null));
    assertThat(a).isEqualTo(b);
  }

  @Test
  void v2_tagsDifferentiateSameFamily() {
    // Both are family "ebean.dml" with no hash; tags must keep them distinct (no collapse).
    String save = MetricKey.of(metric("ebean.dml", null, "label:User.save"));
    String del = MetricKey.of(metric("ebean.dml", null, "label:User.delete"));
    assertThat(save).isNotEqualTo(del);
  }

  @Test
  void v2_differsFromV1FlatNameKey() {
    String v1 = MetricKey.of(metric("iud.User.save", null, null));
    String v2 = MetricKey.of(metric("ebean.dml", null, "label:User.save"));
    assertThat(v1).isNotEqualTo(v2);
  }
}
