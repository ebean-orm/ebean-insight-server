package org.ebean.monitor.api;

import io.avaje.jsonb.Jsonb;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricRequestV2ParseTest {

  private final Jsonb jsonb = Jsonb.builder().build();

  @Test
  void parsesV2EnvelopeAndTags() {
    String json = "{"
      + "\"v\":2,"
      + "\"eventTime\":1700000000000,"
      + "\"appName\":\"myapp\","
      + "\"dbs\":[{\"db\":\"db\",\"metrics\":["
      + "{\"name\":\"ebean.query\",\"tags\":\"kind:orm,label:Customer.findList,type:Customer\",\"hash\":\"h1\",\"count\":3,\"mean\":10,\"max\":20,\"total\":30},"
      + "{\"name\":\"ebean.dml\",\"tags\":\"label:User.save\",\"count\":5,\"mean\":1,\"max\":2,\"total\":7}"
      + "]}]"
      + "}";

    MetricRequest req = jsonb.type(MetricRequest.class).fromJson(json);

    assertThat(req.v()).isEqualTo(2);
    assertThat(req.dbs()).hasSize(1);
    var metrics = req.dbs().get(0).metrics();
    assertThat(metrics).hasSize(2);
    assertThat(metrics.get(0).name()).isEqualTo("ebean.query");
    assertThat(metrics.get(0).tags()).isEqualTo("kind:orm,label:Customer.findList,type:Customer");
    assertThat(metrics.get(1).name()).isEqualTo("ebean.dml");
    assertThat(metrics.get(1).tags()).isEqualTo("label:User.save");
  }

  @Test
  void v1PayloadHasNoVersionOrTags() {
    String json = "{"
      + "\"eventTime\":1700000000000,"
      + "\"appName\":\"myapp\","
      + "\"dbs\":[{\"db\":\"db\",\"metrics\":["
      + "{\"name\":\"orm.Customer.findList\",\"hash\":\"h1\",\"count\":3}"
      + "]}]"
      + "}";

    MetricRequest req = jsonb.type(MetricRequest.class).fromJson(json);

    assertThat(req.v()).isZero();
    var md = req.dbs().get(0).metrics().get(0);
    assertThat(md.name()).isEqualTo("orm.Customer.findList");
    assertThat(md.tags()).isNull();
  }
}
